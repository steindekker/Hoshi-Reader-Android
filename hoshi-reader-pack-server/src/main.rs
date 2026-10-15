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
    eprintln!("serving {} on http://{}/", root.display(), config.bind);
    if serve(listener, root).is_err() {
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
}

fn parse_args(args: impl IntoIterator<Item = String>) -> Result<Parsed, String> {
    let mut root = None;
    let mut bind = "127.0.0.1".to_string();
    let mut port: u16 = 8094;
    let mut args = args.into_iter();
    while let Some(arg) = args.next() {
        match arg.as_str() {
            "--root" => root = Some(args.next().ok_or("--root needs a directory")?),
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
    Ok(Parsed::Run(Args { root: root.into(), bind }))
}

fn help() -> String {
    "\
hoshi-reader-pack-server --root DIR [--bind 127.0.0.1] [--port 8094]

Serves one directory. Downloads are sendfile'd and can resume with
Range: bytes=N-. The process does not open the pack, the audio database,
or an Anki collection.

The box already publishes ~/hoshi-setup with tailscale serve on
port 8094, which adds no extra process. Run this binary when that job
should be its own process, then point tailscale serve at it."
    .to_string()
}
