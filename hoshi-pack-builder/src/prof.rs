//! Phase timings on stderr with HOSHI_PACK_PROFILE=1.

use std::sync::OnceLock;
use std::time::Instant;

pub fn on() -> bool {
    static ON: OnceLock<bool> = OnceLock::new();
    *ON.get_or_init(|| std::env::var_os("HOSHI_PACK_PROFILE").is_some())
}

pub fn log(what: &str, since: Instant) {
    if on() {
        eprintln!("  [prof] {what}: {:.2}s", since.elapsed().as_secs_f64());
    }
}
