//! zlib with a preset dictionary, called the way CPython's `zlib` module calls it,
//! against the same bundled zlib release (1.3.2) so every row blob, and with it
//! the pack version, is byte-identical to the Python builder's.

use std::mem::MaybeUninit;
use std::os::raw::c_int;

use anyhow::{Result, bail};
use libz_sys as z;

/// The straightforward stream: reset and re-prime with the zdict per row. Kept
/// as the oracle `PrimedDeflater` is tested against.
#[cfg(test)]
pub struct Deflater {
    strm: Box<z::z_stream>,
}

#[cfg(test)]
impl Deflater {
    /// `zlib.compressobj(9, zlib.DEFLATED, 15, 9, zlib.Z_DEFAULT_STRATEGY, zdict)`.
    pub fn new() -> Self {
        // zlib takes null zalloc/zfree as "use the defaults" and fills them in, so
        // the stream is only a valid Rust value once deflateInit2 has run.
        let mut strm: Box<MaybeUninit<z::z_stream>> = Box::new(MaybeUninit::zeroed());
        let rc = unsafe {
            z::deflateInit2_(
                strm.as_mut_ptr(),
                9,
                z::Z_DEFLATED,
                15,
                9,
                z::Z_DEFAULT_STRATEGY,
                z::zlibVersion(),
                std::mem::size_of::<z::z_stream>() as c_int,
            )
        };
        assert_eq!(rc, z::Z_OK, "deflateInit2");
        // SAFETY: initialized by deflateInit2 above.
        Deflater { strm: unsafe { strm.assume_init() } }
    }

    /// `c.compress(raw) + c.flush()` on a fresh stream primed with `zdict`.
    pub fn compress(&mut self, raw: &[u8], zdict: &[u8], out: &mut Vec<u8>) {
        let s = &mut *self.strm;
        unsafe {
            assert_eq!(z::deflateReset(s), z::Z_OK);
            assert_eq!(z::deflateSetDictionary(s, zdict.as_ptr(), zdict.len() as z::uInt), z::Z_OK);
        }
        run(s, raw, out);
    }
}

/// zalloc/zfree that recycle blocks: a row's `deflateCopy` asks for the same few
/// buffer sizes the previous row's `deflateEnd` gave back, so after the first
/// row no allocation reaches malloc (glibc would mmap these and page-fault them in).
#[derive(Default)]
struct Pool {
    free: Vec<(usize, usize)>,
    live: Vec<(usize, usize)>,
}

extern "C" fn pool_alloc(opaque: z::voidpf, items: z::uInt, size: z::uInt) -> z::voidpf {
    // SAFETY: opaque is the Box<Pool> owned by the PrimedDeflater using this stream.
    let pool = unsafe { &mut *(opaque as *mut Pool) };
    let n = items as usize * size as usize;
    let p = match pool.free.iter().position(|&(_, s)| s == n) {
        Some(i) => pool.free.swap_remove(i).0,
        None => unsafe { libc::malloc(n) as usize },
    };
    if p != 0 {
        pool.live.push((p, n));
    }
    p as z::voidpf
}

extern "C" fn pool_free(opaque: z::voidpf, address: z::voidpf) {
    // SAFETY: as in pool_alloc.
    let pool = unsafe { &mut *(opaque as *mut Pool) };
    if let Some(i) = pool.live.iter().position(|&(p, _)| p == address as usize) {
        let block = pool.live.swap_remove(i);
        pool.free.push(block);
    }
}

impl Drop for Pool {
    fn drop(&mut self) {
        for &(p, _) in self.free.iter().chain(&self.live) {
            unsafe { libc::free(p as *mut libc::c_void) };
        }
    }
}

/// A stream primed with the zdict once; each row starts from a `deflateCopy`
/// of it, which is exactly the state reset + `deflateSetDictionary` produces,
/// without re-hashing 32 KB of dictionary per row.
pub struct PrimedDeflater {
    primed: Box<MaybeUninit<z::z_stream>>,
    work: Box<MaybeUninit<z::z_stream>>,
    live: bool,
    // Dropped after the streams (fields drop in declaration order).
    _pool: Box<Pool>,
}

// SAFETY: the streams and their pool are owned and only touched through &mut self.
unsafe impl Send for PrimedDeflater {}

impl PrimedDeflater {
    /// `zlib.compressobj(9, zlib.DEFLATED, 15, 9, zlib.Z_DEFAULT_STRATEGY, zdict)`.
    pub fn new(zdict: &[u8]) -> Self {
        let mut pool = Box::new(Pool::default());
        let mut primed: Box<MaybeUninit<z::z_stream>> = Box::new(MaybeUninit::zeroed());
        unsafe {
            let p = primed.as_mut_ptr();
            std::ptr::addr_of_mut!((*p).zalloc).write(pool_alloc);
            std::ptr::addr_of_mut!((*p).zfree).write(pool_free);
            std::ptr::addr_of_mut!((*p).opaque).write(&mut *pool as *mut Pool as z::voidpf);
            let rc = z::deflateInit2_(
                p,
                9,
                z::Z_DEFLATED,
                15,
                9,
                z::Z_DEFAULT_STRATEGY,
                z::zlibVersion(),
                std::mem::size_of::<z::z_stream>() as c_int,
            );
            assert_eq!(rc, z::Z_OK, "deflateInit2");
            assert_eq!(z::deflateSetDictionary(p, zdict.as_ptr(), zdict.len() as z::uInt), z::Z_OK);
        }
        PrimedDeflater { primed, work: Box::new(MaybeUninit::zeroed()), live: false, _pool: pool }
    }

    /// `c.compress(raw) + c.flush()` on a fresh stream primed with the zdict.
    pub fn compress(&mut self, raw: &[u8], out: &mut Vec<u8>) {
        unsafe {
            if self.live {
                z::deflateEnd(self.work.as_mut_ptr());
            }
            assert_eq!(z::deflateCopy(self.work.as_mut_ptr(), self.primed.as_mut_ptr()), z::Z_OK);
            self.live = true;
            run(&mut *self.work.as_mut_ptr(), raw, out);
        }
    }
}

impl Drop for PrimedDeflater {
    fn drop(&mut self) {
        unsafe {
            if self.live {
                z::deflateEnd(self.work.as_mut_ptr());
            }
            z::deflateEnd(self.primed.as_mut_ptr());
        }
    }
}

/// Feeds `raw` with Z_NO_FLUSH, then Z_FINISH, as CPython's compress + flush do.
fn run(s: &mut z::z_stream, raw: &[u8], out: &mut Vec<u8>) {
    out.clear();
    {
        unsafe {
            let bound = z::deflateBound(s, raw.len() as z::uLong) as usize + 64;
            out.reserve(bound);
            s.next_in = raw.as_ptr() as *mut _;
            s.avail_in = raw.len() as z::uInt;
            for flush in [z::Z_NO_FLUSH, z::Z_FINISH] {
                loop {
                    if out.capacity() - out.len() < 64 {
                        out.reserve(out.capacity());
                    }
                    let spare = out.capacity() - out.len();
                    s.next_out = out.as_mut_ptr().add(out.len());
                    s.avail_out = spare as z::uInt;
                    let rc = z::deflate(s, flush);
                    let written = spare - s.avail_out as usize;
                    out.set_len(out.len() + written);
                    if flush == z::Z_FINISH {
                        if rc == z::Z_STREAM_END {
                            break;
                        }
                        assert!(rc == z::Z_OK || rc == z::Z_BUF_ERROR, "deflate {rc}");
                    } else {
                        assert!(rc == z::Z_OK || rc == z::Z_BUF_ERROR, "deflate {rc}");
                        if s.avail_in == 0 && s.avail_out != 0 {
                            break;
                        }
                    }
                }
            }
        }
    }
}

#[cfg(test)]
impl Drop for Deflater {
    fn drop(&mut self) {
        unsafe { z::deflateEnd(&mut *self.strm) };
    }
}

/// `pack.decompress` / `EnrichPackFormat.decode`: inflate a row blob with the zdict.
pub fn inflate(blob: &[u8], zdict: &[u8]) -> Result<Vec<u8>> {
    let mut out: Vec<u8> = Vec::with_capacity(blob.len() * 4 + 256);
    unsafe {
        let mut init: Box<MaybeUninit<z::z_stream>> = Box::new(MaybeUninit::zeroed());
        let rc = z::inflateInit2_(init.as_mut_ptr(), 15, z::zlibVersion(), std::mem::size_of::<z::z_stream>() as c_int);
        if rc != z::Z_OK {
            bail!("inflateInit2 {rc}");
        }
        // SAFETY: initialized by inflateInit2 above.
        let mut strm = init.assume_init();
        strm.next_in = blob.as_ptr() as *mut _;
        strm.avail_in = blob.len() as z::uInt;
        let result = loop {
            if out.capacity() - out.len() < 256 {
                out.reserve(out.capacity());
            }
            let spare = out.capacity() - out.len();
            strm.next_out = out.as_mut_ptr().add(out.len());
            strm.avail_out = spare as z::uInt;
            let mut rc = z::inflate(&mut *strm, z::Z_NO_FLUSH);
            if rc == z::Z_NEED_DICT {
                rc = z::inflateSetDictionary(&mut *strm, zdict.as_ptr(), zdict.len() as z::uInt);
                if rc == z::Z_OK {
                    out.set_len(out.len() + spare - strm.avail_out as usize);
                    continue;
                }
            }
            out.set_len(out.len() + spare - strm.avail_out as usize);
            match rc {
                z::Z_STREAM_END => break Ok(()),
                z::Z_OK | z::Z_BUF_ERROR if strm.avail_in > 0 || strm.avail_out == 0 => continue,
                rc => break Err(rc),
            }
        };
        z::inflateEnd(&mut *strm);
        if let Err(rc) = result {
            bail!("inflate failed ({rc})");
        }
    }
    Ok(out)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn round_trips_with_dictionary() {
        let zdict = b"<div class=\"aa-jj\" data-dict=\"daijisen\">".repeat(10);
        let raw = br#"{"definition":"<div class=\"aa-jj\" data-dict=\"daijisen\">x</div>"}"#;
        let mut d = Deflater::new();
        let mut out = Vec::new();
        d.compress(raw, &zdict, &mut out);
        assert_eq!(inflate(&out, &zdict).unwrap(), raw);
        let first = out.clone();
        d.compress(raw, &zdict, &mut out);
        assert_eq!(out, first, "a reset stream compresses the same");
    }

    #[test]
    fn primed_copies_compress_like_a_fresh_stream() {
        let unit = b"<span class=\"x\">ab</span>";
        let zdict: Vec<u8> = (0..40_000).map(|i| unit[i % unit.len()]).collect();
        let zdict = &zdict[zdict.len() - 32768..];
        let rows: Vec<Vec<u8>> = (0..50u32)
            .map(|i| format!("{{\"k\":\"<span class=\\\"x\\\">{}</span>{}\"}}", "ab".repeat(i as usize * 37), i).into_bytes())
            .collect();
        let (mut fresh, mut primed) = (Deflater::new(), PrimedDeflater::new(zdict));
        let (mut a, mut b) = (Vec::new(), Vec::new());
        for raw in rows.iter().chain(std::iter::once(&vec![0u8; 100_000])) {
            fresh.compress(raw, zdict, &mut a);
            primed.compress(raw, &mut b);
            assert_eq!(a, b);
            assert_eq!(inflate(&b, zdict).unwrap(), *raw);
        }
    }
}
