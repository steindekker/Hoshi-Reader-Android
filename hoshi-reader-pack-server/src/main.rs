use std::env;
use std::net::TcpListener;
use std::process::ExitCode;

use hoshi_reader_pack_server::{prepare_root, serve};

fn main() -> ExitCode {
    let config = match parse_args(env::args().skip(1)) {
        Ok(Parsed::Help) => {
            println!("{}", help());
            return ExitCode::SUCCESS;
        }
        Ok(Parsed::Run(config)) => config,
        Err(message) => {
            eprintln!("{message}");
            return ExitCode::from(2);
        }
    };
    let root = match prepare_root(&config.root) {
        Ok(root) => root,
        Err(message) => {
            eprintln!("{message}");
            return ExitCode::from(1);
        }
    };
    let listener = match TcpListener::bind(config.bind) {
        Ok(listener) => listener,
        Err(error) => {
            eprintln!("cannot listen on {}: {error}", config.bind);
            return ExitCode::from(1);
        }
    };
    let token = match config.token_file.as_deref().map(read_token).transpose() {
        Ok(token) => token,
        Err(message) => {
            eprintln!("{message}");
            return ExitCode::from(1);
        }
    };
    eprintln!(
        "serving {} on http://{}/{}",
        root.display(),
        config.bind,
        if token.is_some() { " (token required)" } else { "" },
    );
    if serve(listener, root, token).is_err() {
        return ExitCode::from(1);
    }
    ExitCode::SUCCESS
}

enum Parsed {
    Run(Args),
    Help,
}

struct Args {
    root: std::path::PathBuf,
    bind: std::net::SocketAddr,
    token_file: Option<std::path::PathBuf>,
}

fn read_token(path: &std::path::Path) -> Result<String, String> {
    let token = std::fs::read_to_string(path)
        .map_err(|error| format!("cannot read token file {}: {error}", path.display()))?;
    let token = token.trim().to_string();
    if token.len() < 16 {
        return Err(format!("token in {} is shorter than 16 characters", path.display()));
    }
    Ok(token)
}

fn parse_args(args: impl IntoIterator<Item = String>) -> Result<Parsed, String> {
    let mut root = None;
    let mut bind = "127.0.0.1".to_string();
    let mut port: u16 = 8094;
    let mut token_file = None;
    let mut args = args.into_iter();
    while let Some(arg) = args.next() {
        match arg.as_str() {
            "--root" => root = Some(args.next().ok_or("--root needs a directory")?),
            "--token-file" => token_file = Some(args.next().ok_or("--token-file needs a path")?.into()),
            "--bind" => bind = args.next().ok_or("--bind needs an address")?,
            "--port" => {
                port = args
                    .next()
                    .ok_or("--port needs a number")?
                    .parse()
                    .map_err(|_| "port is not a number".to_string())?;
            }
            "-h" | "--help" => return Ok(Parsed::Help),
            other => return Err(format!("unknown argument {other}\n{}", help())),
        }
    }
    let root = root.ok_or_else(|| format!("--root is required\n{}", help()))?;
    let bind = format!("{bind}:{port}")
        .parse()
        .map_err(|_| format!("cannot use address {bind}:{port}"))?;
    Ok(Parsed::Run(Args { root: root.into(), bind, token_file }))
}

fn help() -> String {
    "\
hoshi-reader-pack-server --root DIR [--bind 127.0.0.1] [--port 8094] [--token-file PATH]

Serves one directory. Downloads are sendfile'd and can resume with
Range: bytes=N-. The process does not open the pack, the audio database,
or an Anki collection.

With --token-file, every request must send the file's token in the
X-Hoshi-Token header; anything else gets 401. Responses carry
Cache-Control: private, no-store so a proxy in front never caches them."
    .to_string()
}
