//! zlib with a preset dictionary, called the way CPython's `zlib` module calls it,
//! against the same bundled zlib release (1.3.2) so every row blob, and with it
//! the pack version, is byte-identical to the Python builder's.

use std::mem::MaybeUninit;
use std::os::raw::c_int;

use anyhow::{Result, bail};
use libz_sys as z;

pub struct Deflater {
    strm: Box<z::z_stream>,
}

// SAFETY: the stream is owned and only touched through &mut self.
unsafe impl Send for Deflater {}

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
        out.clear();
        let s = &mut *self.strm;
        unsafe {
            assert_eq!(z::deflateReset(s), z::Z_OK);
            assert_eq!(z::deflateSetDictionary(s, zdict.as_ptr(), zdict.len() as z::uInt), z::Z_OK);
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
        let raw = br#"{"ae-definition":"<div class=\"aa-jj\" data-dict=\"daijisen\">x</div>"}"#;
        let mut d = Deflater::new();
        let mut out = Vec::new();
        d.compress(raw, &zdict, &mut out);
        assert_eq!(inflate(&out, &zdict).unwrap(), raw);
        let first = out.clone();
        d.compress(raw, &zdict, &mut out);
        assert_eq!(out, first, "a reset stream compresses the same");
    }
}
