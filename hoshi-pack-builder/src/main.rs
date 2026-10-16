//! hoshi-pack-builder: builds the Hoshi enrichment pack (`hoshi-pack.sqlite` +
//! `pack.json`) straight from Yomitan zips, as `anki-enrich pack` does.

mod accent;
mod prof;
mod compare;
mod defs;
mod examples;
mod freq;
mod json;
mod pack;
mod py;
mod sentence;
mod sha;
mod sources;
mod strtab;
mod yomitan;
mod z;

use std::path::{Path, PathBuf};

use std::time::Instant;

use anyhow::{Context, Result, bail};
use clap::{Parser, Subcommand};

#[derive(Parser)]
#[command(version, about = "Build the Hoshi enrichment pack from Yomitan zips")]
struct Cli {
    #[command(subcommand)]
    cmd: Cmd,
}

#[derive(Subcommand)]
enum Cmd {
    /// Build hoshi-pack.sqlite + pack.json into --out.
    Build(BuildArgs),
    /// Decode every row of two packs and diff their values per key.
    Compare {
        a: PathBuf,
        b: PathBuf,
        /// Examples shown per differing key.
        #[arg(long, default_value_t = 5)]
        samples: usize,
    },
}

#[derive(Parser)]
struct BuildArgs {
    /// Directory of Yomitan zips; each is identified by its index.json title.
    #[arg(long)]
    dicts: Option<PathBuf>,
    /// Explicit dictionary zip, ID=PATH (sankoku8, koujien7, jitsuyo, daijisen,
    /// jmdict, jitendex, saito, yourei, jpdb). Wins over --dicts.
    #[arg(long = "dict", value_parser = parse_dict)]
    dict: Vec<(String, PathBuf)>,
    /// JMdict English zip: a path or an http(s) URL.
    #[arg(long)]
    jmdict: Option<String>,
    /// File holding the X-Hoshi-Token sent with a --jmdict URL.
    #[arg(long)]
    token_file: Option<PathBuf>,
    /// Tatoeba dump directory (jpn_indices.csv, jpn_sentences.tsv, eng_sentences.tsv).
    #[arg(long)]
    tatoeba: Option<PathBuf>,
    /// anki-enrich's accent_index.marshal.gz.
    #[arg(long)]
    accent: PathBuf,
    /// Output directory.
    #[arg(long)]
    out: PathBuf,
    /// Sudachi system.dic for the sentence model; without it pack.json has no
    /// "sentence" block.
    #[arg(long)]
    sudachi_dic: Option<PathBuf>,
    /// Label for the dictionary, e.g. "sudachidict_full 20260428" (read from the
    /// dist-info beside an installed sudachidict_full when omitted).
    #[arg(long)]
    sudachi_source: Option<String>,
    /// Worker threads (default: all cores).
    #[arg(long)]
    threads: Option<usize>,
    /// Banks larger than this many MB are parsed while inflating instead of from
    /// an inflated buffer, which bounds memory but parses them on one thread
    /// (default: 64 with up to 4 threads, else 512).
    #[arg(long)]
    stream_over_mb: Option<u64>,
    /// After building, compare against this pack (a .sqlite or its directory).
    #[arg(long)]
    compare: Option<PathBuf>,
}

fn parse_dict(s: &str) -> Result<(String, PathBuf), String> {
    let (id, path) = s.split_once('=').ok_or("expected ID=PATH")?;
    Ok((id.to_string(), PathBuf::from(path)))
}

fn fetch(url: &str, token: Option<&Path>, dest: &Path) -> Result<()> {
    let mut req = ureq::get(url);
    if let Some(t) = token {
        let tok = std::fs::read_to_string(t).with_context(|| format!("read {}", t.display()))?;
        req = req.header("X-Hoshi-Token", tok.trim());
    }
    let resp = req.call().with_context(|| format!("GET {url}"))?;
    let mut body = resp.into_body().into_reader();
    let tmp = dest.with_extension("part");
    std::io::copy(&mut body, &mut std::fs::File::create(&tmp)?)?;
    std::fs::rename(&tmp, dest)?;
    Ok(())
}

fn build(a: BuildArgs) -> Result<()> {
    let start = Instant::now();
    if let Some(n) = a.threads {
        rayon::ThreadPoolBuilder::new().num_threads(n).build_global()?;
    }
    let stream_over = a.stream_over_mb.unwrap_or(if rayon::current_num_threads() <= 4 { 64 } else { 512 });
    yomitan::STREAM_OVER.store(stream_over << 20, std::sync::atomic::Ordering::Relaxed);
    std::fs::create_dir_all(&a.out)?;

    let mut overrides = a.dict.clone();
    let mut downloaded = None;
    if let Some(j) = &a.jmdict {
        let path = if j.starts_with("http://") || j.starts_with("https://") {
            let dest = a.out.join(".jmdict-download.zip");
            eprintln!("fetching {j}");
            fetch(j, a.token_file.as_deref(), &dest)?;
            downloaded = Some(dest.clone());
            dest
        } else {
            PathBuf::from(j)
        };
        overrides.push(("jmdict".into(), path));
    }
    let td = Instant::now();
    let dicts = yomitan::discover(a.dicts.as_deref(), &overrides)?;
    prof::log("discover", td);
    for (id, z) in &dicts {
        eprintln!("  {id:9} {} [{}]  {}", z.title, z.revision, z.path.display());
    }
    if !defs::JJ_CHAIN.iter().chain([&defs::JE_DICT]).any(|id| dicts.contains_key(id)) {
        bail!("no definition dictionary found");
    }
    for id in yomitan::IDS.iter().map(|x| x.0) {
        if !dicts.contains_key(id) {
            eprintln!("  {id:9} missing — its fields are left out, as in anki-enrich");
        }
    }

    let t = Instant::now();
    let src = sources::Sources::load(sources::Inputs {
        dicts: &dicts,
        tatoeba: a.tatoeba.as_deref(),
        accent: &a.accent,
        scratch: &a.out,
    })?;
    eprintln!("sources loaded in {:.1}s: {} headwords", t.elapsed().as_secs_f64(), src.pairs.len());
    for (k, n) in &src.counts {
        eprintln!("  {k:20} {n}");
    }
    drop(dicts);
    if let Some(d) = downloaded {
        let _ = std::fs::remove_file(d);
    }

    // The sentence model only needs the accent index, so it is written while
    // the pack is being compressed.
    let (built, sentence) = std::thread::scope(|scope| -> Result<_> {
        let sentence = a.sudachi_dic.as_ref().map(|dic| {
            let (out, accent, label) = (&a.out, &src.accent, a.sudachi_source.clone());
            scope.spawn(move || {
                let t = Instant::now();
                let s = sentence::build(out, accent, dic, label);
                prof::log("sentence", t);
                s
            })
        });
        let t = Instant::now();
        let built = pack::build(&src, &a.out, true)?;
        eprintln!("pack {} · {} entries in {:.1}s", built.version, built.entries, t.elapsed().as_secs_f64());
        let sentence = match sentence {
            Some(h) => {
                let s = h.join().unwrap()?;
                eprintln!("sentence model {}", s["accent"]["version"]);
                Some(s)
            }
            None => {
                eprintln!("sentence model skipped (no --sudachi-dic): pack.json has no \"sentence\" block");
                None
            }
        };
        Ok((built, sentence))
    })?;
    drop(src);
    let tm = Instant::now();
    let manifest = pack::write_manifest(&a.out, &built, sentence)?;
    prof::log("manifest", tm);
    eprintln!("wrote {} ({} bytes) in {:.1}s total", a.out.join(pack::MANIFEST_FILE).display(), manifest["size"],
        start.elapsed().as_secs_f64());

    if let Some(other) = &a.compare {
        let diffs = compare::compare(&a.out.join(pack::PACK_FILE), other, 5)?;
        if diffs > 0 {
            bail!("{diffs} differences against {}", other.display());
        }
    }
    Ok(())
}

fn main() -> Result<()> {
    match Cli::parse().cmd {
        Cmd::Build(a) => build(a),
        Cmd::Compare { a, b, samples } => {
            let n = compare::compare(&a, &b, samples)?;
            if n > 0 {
                std::process::exit(1);
            }
            Ok(())
        }
    }
}
