//! Serves one directory over HTTP. A download is `sendfile` from disk: the
//! process does not open the pack, the audio database, or an Anki collection.

use std::fs::File;
use std::io::{self, Read, Write};
use std::net::{TcpListener, TcpStream};
use std::path::{Component, Path, PathBuf};
use std::sync::Arc;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::thread;
use std::time::Duration;

const SERVER: &str = "hoshi-reader-pack-server";
const MAX_HEADER: usize = 8 * 1024;
const MAX_CONNECTIONS: usize = 16;
/// Request header that carries the shared download token.
pub const TOKEN_HEADER: &str = "x-hoshi-token";
const FORBIDDEN_ROOTS: &[&str] = &["/opt/anki-enrich", "/data/apps/anki-enrich"];

static ACTIVE: AtomicUsize = AtomicUsize::new(0);

#[derive(Debug, PartialEq, Eq)]
enum Range {
    Full,
    Partial { start: u64, end: u64 },
    Unsatisfiable,
}

struct Request {
    method: String,
    path: String,
    range: Option<String>,
    token: Option<String>,
}

pub fn prepare_root(path: &Path) -> Result<PathBuf, String> {
    let root = path
        .canonicalize()
        .map_err(|error| format!("cannot read {}: {error}", path.display()))?;
    if !root.is_dir() {
        return Err(format!("{} is not a directory", root.display()));
    }
    let text = root.to_string_lossy().replace('\\', "/");
    if FORBIDDEN_ROOTS.iter().any(|snippet| text.contains(snippet)) {
        return Err(format!("refusing to serve {}", root.display()));
    }
    Ok(root)
}

/// Serves `root`. With a `token`, every request must send it in `X-Hoshi-Token`.
pub fn serve(listener: TcpListener, root: PathBuf, token: Option<String>) -> io::Result<()> {
    let token = token.map(Arc::new);
    for connection in listener.incoming() {
        let stream = match connection {
            Ok(stream) => stream,
            Err(error) => {
                eprintln!("accept failed: {error}");
                continue;
            }
        };
        if ACTIVE.fetch_add(1, Ordering::Relaxed) >= MAX_CONNECTIONS {
            ACTIVE.fetch_sub(1, Ordering::Relaxed);
            let mut stream = stream;
            let _ = write_status(&mut stream, 503, "Service Unavailable", &[]);
            continue;
        }
        let root = root.clone();
        let token = token.clone();
        thread::spawn(move || {
            if let Err(error) = handle(stream, &root, token.as_deref().map(String::as_str)) {
                eprintln!("request failed: {error}");
            }
            ACTIVE.fetch_sub(1, Ordering::Relaxed);
        });
    }
    Ok(())
}

fn handle(mut stream: TcpStream, root: &Path, token: Option<&str>) -> io::Result<()> {
    stream.set_read_timeout(Some(Duration::from_secs(15)))?;
    let request = match read_request(&mut stream) {
        Ok(request) => request,
        Err(error) => {
            write_status(&mut stream, 400, "Bad Request", &[])?;
            return Err(error);
        }
    };
    stream.set_read_timeout(None)?;
    if let Some(expected) = token {
        let sent = request.token.as_deref().unwrap_or("");
        if !constant_time_eq(sent.as_bytes(), expected.as_bytes()) {
            return write_status(&mut stream, 401, "Unauthorized", &[]);
        }
    }
    if request.method != "GET" && request.method != "HEAD" {
        return write_status(&mut stream, 405, "Method Not Allowed", &[("Allow", "GET, HEAD")]);
    }
    let path = match safe_file(root, &request.path) {
        Lookup::File(path) => path,
        Lookup::Forbidden => return write_status(&mut stream, 403, "Forbidden", &[]),
        Lookup::Missing => return write_status(&mut stream, 404, "Not Found", &[]),
    };
    let file = match File::open(&path) {
        Ok(file) if file.metadata()?.is_file() => file,
        _ => return write_status(&mut stream, 404, "Not Found", &[]),
    };
    let size = file.metadata()?.len();
    let range = request.range.as_deref().map(|header| parse_range(header, size)).unwrap_or(Range::Full);
    let (status, reason, start, length) = match range {
        Range::Full => (200, "OK", 0, size),
        Range::Partial { start, end } => (206, "Partial Content", start, end - start + 1),
        Range::Unsatisfiable => {
            return write_status(
                &mut stream,
                416,
                "Range Not Satisfiable",
                &[
                    ("Content-Range", &format!("bytes */{size}")),
                    ("Accept-Ranges", "bytes"),
                ],
            );
        }
    };
    let mut headers = vec![
        ("Content-Type", content_type(&path)),
        ("Content-Length", length.to_string()),
        ("Accept-Ranges", "bytes".to_string()),
        ("Cache-Control", NO_STORE.to_string()),
        ("Connection", "close".to_string()),
    ];
    if status == 206 {
        let end = start + length - 1;
        headers.push(("Content-Range", format!("bytes {start}-{end}/{size}")));
    }
    write_head(&mut stream, status, reason, &headers)?;
    if request.method == "HEAD" || length == 0 {
        return Ok(());
    }
    send_slice(&mut stream, &file, start, length)
}

/// A proxy in front (Cloudflare) must never cache a file and serve it without the token.
const NO_STORE: &str = "private, no-store";

fn constant_time_eq(a: &[u8], b: &[u8]) -> bool {
    if a.len() != b.len() {
        return false;
    }
    a.iter().zip(b).fold(0u8, |acc, (x, y)| acc | (x ^ y)) == 0
}

enum Lookup {
    File(PathBuf),
    Missing,
    Forbidden,
}

fn safe_file(root: &Path, url_path: &str) -> Lookup {
    let raw = url_path.split('?').next().unwrap_or("");
    let Some(decoded) = percent_decode(raw) else {
        return Lookup::Missing;
    };
    if decoded.contains('\0') {
        return Lookup::Missing;
    }
    let mut joined = root.to_path_buf();
    for component in Path::new(&decoded).components() {
        match component {
            Component::Normal(part) => joined.push(part),
            Component::RootDir | Component::CurDir => {}
            Component::ParentDir | Component::Prefix(_) => return Lookup::Missing,
        }
    }
    let Ok(canonical) = joined.canonicalize() else {
        return Lookup::Missing;
    };
    if canonical.starts_with(root) {
        Lookup::File(canonical)
    } else {
        Lookup::Forbidden
    }
}

fn parse_range(header: &str, size: u64) -> Range {
    let header = header.trim();
    if header.contains(',') || !header.to_ascii_lowercase().starts_with("bytes=") {
        return Range::Full;
    }
    let spec = &header["bytes=".len()..];
    let Some((start_text, end_text)) = spec.split_once('-') else {
        return Range::Full;
    };
    if start_text.is_empty() && end_text.is_empty() {
        return Range::Full;
    }
    if size == 0 {
        return Range::Unsatisfiable;
    }
    if start_text.is_empty() {
        let Ok(suffix) = end_text.parse::<u64>() else {
            return Range::Full;
        };
        if suffix == 0 {
            return Range::Unsatisfiable;
        }
        let start = size.saturating_sub(suffix);
        return Range::Partial { start, end: size - 1 };
    }
    let Ok(start) = start_text.parse::<u64>() else {
        return Range::Full;
    };
    let end = if end_text.is_empty() {
        size - 1
    } else {
        match end_text.parse::<u64>() {
            Ok(end) => end.min(size - 1),
            Err(_) => return Range::Full,
        }
    };
    if start >= size || start > end {
        Range::Unsatisfiable
    } else {
        Range::Partial { start, end }
    }
}

fn percent_decode(input: &str) -> Option<String> {
    let bytes = input.as_bytes();
    let mut out = Vec::with_capacity(bytes.len());
    let mut index = 0;
    while index < bytes.len() {
        if bytes[index] == b'%' {
            if index + 2 >= bytes.len() {
                return None;
            }
            let high = from_hex(bytes[index + 1])?;
            let low = from_hex(bytes[index + 2])?;
            out.push((high << 4) | low);
            index += 3;
        } else {
            out.push(bytes[index]);
            index += 1;
        }
    }
    String::from_utf8(out).ok()
}

fn from_hex(byte: u8) -> Option<u8> {
    match byte {
        b'0'..=b'9' => Some(byte - b'0'),
        b'a'..=b'f' => Some(byte - b'a' + 10),
        b'A'..=b'F' => Some(byte - b'A' + 10),
        _ => None,
    }
}

fn content_type(path: &Path) -> String {
    match path.extension().and_then(|ext| ext.to_str()).unwrap_or("") {
        "json" => "application/json",
        "zip" => "application/zip",
        _ => "application/octet-stream",
    }
    .to_string()
}

fn read_request(stream: &mut TcpStream) -> io::Result<Request> {
    let mut buffer = Vec::new();
    let mut byte = [0; 1];
    while !buffer.windows(4).any(|window| window == b"\r\n\r\n") {
        if buffer.len() >= MAX_HEADER {
            return Err(io::Error::new(io::ErrorKind::InvalidData, "header too large"));
        }
        if stream.read(&mut byte)? == 0 {
            return Err(io::Error::new(io::ErrorKind::UnexpectedEof, "closed before headers"));
        }
        buffer.push(byte[0]);
    }
    let text = String::from_utf8(buffer).map_err(|error| io::Error::new(io::ErrorKind::InvalidData, error))?;
    let mut lines = text.split("\r\n");
    let Some(request_line) = lines.next() else {
        return Err(io::Error::new(io::ErrorKind::InvalidData, "empty request"));
    };
    let mut parts = request_line.split(' ');
    let Some(method) = parts.next() else {
        return Err(io::Error::new(io::ErrorKind::InvalidData, "missing method"));
    };
    let Some(path) = parts.next() else {
        return Err(io::Error::new(io::ErrorKind::InvalidData, "missing path"));
    };
    if !path.starts_with('/') {
        return Err(io::Error::new(io::ErrorKind::InvalidData, "path must be absolute"));
    }
    let mut range = None;
    let mut token = None;
    for line in lines {
        let Some((name, value)) = line.split_once(':') else { continue };
        if name.eq_ignore_ascii_case("range") {
            range = Some(value.trim().to_string());
        } else if name.eq_ignore_ascii_case(TOKEN_HEADER) {
            token = Some(value.trim().to_string());
        }
    }
    Ok(Request { method: method.to_string(), path: path.to_string(), range, token })
}

fn write_status(mut stream: impl Write, status: u16, reason: &str, extra: &[(&str, &str)]) -> io::Result<()> {
    let headers: Vec<(String, String)> = extra
        .iter()
        .map(|(name, value)| ((*name).to_string(), (*value).to_string()))
        .chain([
            ("Content-Length".to_string(), "0".to_string()),
            ("Cache-Control".to_string(), NO_STORE.to_string()),
            ("Connection".to_string(), "close".to_string()),
        ])
        .collect();
    write_head(&mut stream, status, reason, &headers)
}

fn write_head(stream: &mut impl Write, status: u16, reason: &str, headers: &[(impl AsRef<str>, impl AsRef<str>)]) -> io::Result<()> {
    let mut head = format!("HTTP/1.1 {status} {reason}\r\nServer: {SERVER}\r\n");
    for (name, value) in headers {
        head.push_str(name.as_ref());
        head.push_str(": ");
        head.push_str(value.as_ref());
        head.push_str("\r\n");
    }
    head.push_str("\r\n");
    stream.write_all(head.as_bytes())
}

fn send_slice(stream: &mut TcpStream, file: &File, start: u64, length: u64) -> io::Result<()> {
    stream.set_write_timeout(None)?;
    let out_fd = std::os::fd::AsRawFd::as_raw_fd(stream);
    let in_fd = std::os::fd::AsRawFd::as_raw_fd(file);
    let mut offset = start as libc::off_t;
    let end = (start + length) as libc::off_t;
    while offset < end {
        let count = usize::try_from(end - offset).unwrap_or(usize::MAX);
        let sent = unsafe { libc::sendfile(out_fd, in_fd, &mut offset, count) };
        if sent < 0 {
            let error = io::Error::last_os_error();
            if error.kind() == io::ErrorKind::Interrupted {
                continue;
            }
            return Err(error);
        }
        if sent == 0 {
            return Err(io::Error::new(io::ErrorKind::UnexpectedEof, "sendfile ended early"));
        }
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::net::SocketAddr;
    use std::os::unix::fs::symlink;

    fn serve_dir(root: PathBuf) -> u16 {
        serve_dir_with_token(root, None)
    }

    fn serve_dir_with_token(root: PathBuf, token: Option<&str>) -> u16 {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let port = listener.local_addr().unwrap().port();
        let token = token.map(str::to_string);
        thread::spawn(move || serve(listener, root, token).unwrap());
        port
    }

    fn exchange(port: u16, request: &str) -> (u16, String, Vec<u8>) {
        let mut stream = TcpStream::connect(SocketAddr::from(([127, 0, 0, 1], port))).unwrap();
        stream.set_read_timeout(Some(Duration::from_secs(2))).unwrap();
        stream.write_all(request.as_bytes()).unwrap();
        let mut raw = Vec::new();
        let _ = stream.read_to_end(&mut raw);
        let split = raw.windows(4).position(|window| window == b"\r\n\r\n").unwrap();
        let head = String::from_utf8(raw[..split].to_vec()).unwrap();
        let status = head.split(' ').nth(1).unwrap().parse().unwrap();
        (status, head, raw[split + 4..].to_vec())
    }

    #[test]
    fn resumes_from_a_byte_offset() {
        assert_eq!(parse_range("bytes=10-", 32), Range::Partial { start: 10, end: 31 });
        assert_eq!(parse_range("bytes=-4", 32), Range::Partial { start: 28, end: 31 });
        assert_eq!(parse_range("bytes=40-", 32), Range::Unsatisfiable);
        assert_eq!(parse_range("bytes=0-1,2-3", 32), Range::Full);
    }

    #[test]
    fn serves_a_partial_file_with_sendfile() {
        let root = std::env::temp_dir().join(format!("hoshi-pack-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&root);
        std::fs::create_dir(&root).unwrap();
        std::fs::write(root.join("blob.bin"), b"abcdefghijklmnopqrstuvwxyz").unwrap();
        let port = serve_dir(root.clone());
        let (status, head, body) = exchange(
            port,
            "GET /blob.bin HTTP/1.1\r\nHost: localhost\r\nRange: bytes=20-\r\nConnection: close\r\n\r\n",
        );
        assert_eq!(status, 206);
        assert!(head.to_ascii_lowercase().contains("content-range: bytes 20-25/26"));
        assert_eq!(body, b"uvwxyz");
        std::fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn rejects_paths_that_leave_the_root() {
        let root = std::env::temp_dir().join(format!("hoshi-pack-escape-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&root);
        std::fs::create_dir(&root).unwrap();
        let outside = std::env::temp_dir().join(format!("hoshi-pack-outside-{}", std::process::id()));
        std::fs::write(&outside, b"secret").unwrap();
        symlink(&outside, root.join("link")).unwrap();
        let port = serve_dir(root.clone());
        let (dotdot, _, _) = exchange(port, "GET /../blob.bin HTTP/1.1\r\nHost: localhost\r\n\r\n");
        let (link, _, link_body) = exchange(port, "GET /link HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");
        assert_eq!(dotdot, 404);
        assert_eq!(link, 403);
        assert!(link_body.is_empty());
        std::fs::remove_dir_all(root).unwrap();
        std::fs::remove_file(outside).unwrap();
    }

    #[test]
    fn requires_the_token_when_one_is_set() {
        let root = std::env::temp_dir().join(format!("hoshi-pack-token-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&root);
        std::fs::create_dir(&root).unwrap();
        std::fs::write(root.join("setup.json"), b"{}").unwrap();
        let port = serve_dir_with_token(root.clone(), Some("s3cret"));
        let (missing, missing_head, missing_body) =
            exchange(port, "GET /setup.json HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");
        let (wrong, _, _) = exchange(
            port,
            "GET /setup.json HTTP/1.1\r\nHost: localhost\r\nX-Hoshi-Token: nope\r\nConnection: close\r\n\r\n",
        );
        let (ok, ok_head, ok_body) = exchange(
            port,
            "GET /setup.json HTTP/1.1\r\nHost: localhost\r\nX-Hoshi-Token: s3cret\r\nConnection: close\r\n\r\n",
        );
        assert_eq!(missing, 401);
        assert!(missing_body.is_empty());
        assert!(missing_head.to_ascii_lowercase().contains("cache-control: private, no-store"));
        assert_eq!(wrong, 401);
        assert_eq!(ok, 200);
        assert!(ok_head.to_ascii_lowercase().contains("cache-control: private, no-store"));
        assert_eq!(ok_body, b"{}");
        std::fs::remove_dir_all(root).unwrap();
    }
}
