//! The bits of CPython's string behavior the Python builder's output depends on:
//! `html.escape`, `str.isspace`/`strip`, `str(value)` of a JSON value, `f"{x:g}"`,
//! `json.dumps(..., ensure_ascii=False, separators=(",", ":"))`, jaconv's kana
//! tables and `random.Random(seed).randrange(n)`.

use crate::json::J;

/// `html.escape(s)` (quote=True), appended to `out`.
pub fn escape_into(out: &mut String, s: &str) {
    let mut last = 0;
    for (i, b) in s.bytes().enumerate() {
        let rep = match b {
            b'&' => "&amp;",
            b'<' => "&lt;",
            b'>' => "&gt;",
            b'"' => "&quot;",
            b'\'' => "&#x27;",
            _ => continue,
        };
        out.push_str(&s[last..i]);
        out.push_str(rep);
        last = i + 1;
    }
    out.push_str(&s[last..]);
}

#[cfg(test)]
pub fn escape(s: &str) -> String {
    let mut out = String::with_capacity(s.len() + 8);
    escape_into(&mut out, s);
    out
}

/// `html.escape(s).replace("\n", "<br>")`, appended to `out`.
pub fn escape_br_into(out: &mut String, s: &str) {
    let mut first = true;
    for part in s.split('\n') {
        if !first {
            out.push_str("<br>");
        }
        first = false;
        escape_into(out, part);
    }
}

/// `str.isspace()` for one character (Unicode White_Space plus the C0
/// separators U+001C..U+001F that CPython also counts).
pub fn is_space(c: char) -> bool {
    matches!(c, '\u{1c}'..='\u{1f}') || c.is_whitespace()
}

/// `str.strip()`.
pub fn strip(s: &str) -> &str {
    s.trim_matches(is_space)
}

/// `str.isdigit()` restricted to what the inputs contain (ASCII digits).
pub fn is_digits(s: &str) -> bool {
    !s.is_empty() && s.bytes().all(|b| b.is_ascii_digit())
}

/// `repr(float)`: shortest round-trip digits, fixed notation for 1e-4 <= |x| < 1e16.
pub fn float_repr(x: f64) -> String {
    if x == 0.0 {
        return if x.is_sign_negative() { "-0.0".into() } else { "0.0".into() };
    }
    let sci = format!("{x:e}");
    let (mant, exp) = sci.split_once('e').unwrap();
    let exp: i32 = exp.parse().unwrap();
    if (-4..16).contains(&exp) {
        let s = format!("{x}");
        if s.contains('.') { s } else { s + ".0" }
    } else {
        let sign = if exp < 0 { '-' } else { '+' };
        format!("{mant}e{sign}{:02}", exp.abs())
    }
}

/// `f"{x:g}"` (6 significant digits, trailing zeros dropped).
pub fn format_g(x: f64) -> String {
    let sci = format!("{x:.5e}");
    let (_, exp) = sci.split_once('e').unwrap();
    let exp: i32 = exp.parse().unwrap();
    let strip_zeros = |s: String| -> String {
        if s.contains('.') {
            s.trim_end_matches('0').trim_end_matches('.').to_string()
        } else {
            s
        }
    };
    if !(-4..6).contains(&exp) {
        let (mant, _) = sci.split_once('e').unwrap();
        let sign = if exp < 0 { '-' } else { '+' };
        format!("{}e{sign}{:02}", strip_zeros(mant.to_string()), exp.abs())
    } else {
        strip_zeros(format!("{:.*}", (5 - exp) as usize, x))
    }
}

/// `repr(str)`.
fn str_repr(out: &mut String, s: &str) {
    let q = if s.contains('\'') && !s.contains('"') { '"' } else { '\'' };
    out.push(q);
    for c in s.chars() {
        match c {
            '\\' => out.push_str("\\\\"),
            '\n' => out.push_str("\\n"),
            '\r' => out.push_str("\\r"),
            '\t' => out.push_str("\\t"),
            c if c == q => {
                out.push('\\');
                out.push(c);
            }
            c if (c as u32) < 0x20 || c as u32 == 0x7f => out.push_str(&format!("\\x{:02x}", c as u32)),
            c => out.push(c),
        }
    }
    out.push(q);
}

fn repr_into(out: &mut String, v: &J) {
    match v {
        J::Str(s) => str_repr(out, s),
        _ => str_into(out, v),
    }
}

/// `str(v)` for a value `json.loads` produced.
pub fn str_into(out: &mut String, v: &J) {
    match v {
        J::Null => out.push_str("None"),
        J::Bool(b) => out.push_str(if *b { "True" } else { "False" }),
        J::Int(i) => out.push_str(&i.to_string()),
        J::UInt(u) => out.push_str(&u.to_string()),
        J::Float(f) => out.push_str(&float_repr(*f)),
        J::Str(s) => out.push_str(s),
        J::Arr(a) => {
            out.push('[');
            for (i, x) in a.iter().enumerate() {
                if i > 0 {
                    out.push_str(", ");
                }
                repr_into(out, x);
            }
            out.push(']');
        }
        J::Obj(m) => {
            out.push('{');
            for (i, (k, x)) in m.iter().enumerate() {
                if i > 0 {
                    out.push_str(", ");
                }
                str_repr(out, k);
                out.push_str(": ");
                repr_into(out, x);
            }
            out.push('}');
        }
    }
}

pub fn py_str(v: &J) -> String {
    let mut s = String::new();
    str_into(&mut s, v);
    s
}

/// A JSON string literal as `json.dumps(ensure_ascii=False)` writes it.
pub fn json_str_into(out: &mut Vec<u8>, s: &str) {
    out.push(b'"');
    let bytes = s.as_bytes();
    let mut last = 0;
    for (i, &b) in bytes.iter().enumerate() {
        let rep: &[u8] = match b {
            b'"' => b"\\\"",
            b'\\' => b"\\\\",
            b'\n' => b"\\n",
            b'\r' => b"\\r",
            b'\t' => b"\\t",
            0x08 => b"\\b",
            0x0c => b"\\f",
            0..=0x1f => {
                out.extend_from_slice(&bytes[last..i]);
                out.extend_from_slice(format!("\\u{b:04x}").as_bytes());
                last = i + 1;
                continue;
            }
            _ => continue,
        };
        out.extend_from_slice(&bytes[last..i]);
        out.extend_from_slice(rep);
        last = i + 1;
    }
    out.extend_from_slice(&bytes[last..]);
    out.push(b'"');
}

/// jaconv.hira2kata for one character.
pub fn hira2kata(c: char) -> char {
    match c as u32 {
        0x3041..=0x3096 | 0x309d..=0x309e => char::from_u32(c as u32 + 0x60).unwrap(),
        _ => c,
    }
}

/// jaconv.kata2hira.
pub fn kata2hira(s: &str) -> String {
    s.chars()
        .map(|c| match c as u32 {
            0x30a1..=0x30f6 | 0x30fd..=0x30fe => char::from_u32(c as u32 - 0x60).unwrap(),
            _ => c,
        })
        .collect()
}

/// CPython's Mersenne Twister, seeded like `random.Random(int)`.
pub struct PyRandom {
    mt: [u32; 624],
    idx: usize,
}

impl PyRandom {
    pub fn new(seed: u32) -> Self {
        let mut r = PyRandom { mt: [0; 624], idx: 624 };
        r.init_genrand(19650218);
        let key = [seed];
        let (mut i, mut j) = (1usize, 0usize);
        let n = 624;
        for _ in 0..n.max(key.len()) {
            let prev = r.mt[i - 1] ^ (r.mt[i - 1] >> 30);
            r.mt[i] = (r.mt[i] ^ prev.wrapping_mul(1664525)).wrapping_add(key[j]).wrapping_add(j as u32);
            i += 1;
            j += 1;
            if i >= n {
                r.mt[0] = r.mt[n - 1];
                i = 1;
            }
            if j >= key.len() {
                j = 0;
            }
        }
        for _ in 0..n - 1 {
            let prev = r.mt[i - 1] ^ (r.mt[i - 1] >> 30);
            r.mt[i] = (r.mt[i] ^ prev.wrapping_mul(1566083941)).wrapping_sub(i as u32);
            i += 1;
            if i >= n {
                r.mt[0] = r.mt[n - 1];
                i = 1;
            }
        }
        r.mt[0] = 0x8000_0000;
        r
    }

    fn init_genrand(&mut self, s: u32) {
        self.mt[0] = s;
        for i in 1..624 {
            let prev = self.mt[i - 1];
            self.mt[i] = 1812433253u32.wrapping_mul(prev ^ (prev >> 30)).wrapping_add(i as u32);
        }
        self.idx = 624;
    }

    fn next_u32(&mut self) -> u32 {
        const UPPER: u32 = 0x8000_0000;
        const LOWER: u32 = 0x7fff_ffff;
        const MATRIX: u32 = 0x9908_b0df;
        if self.idx >= 624 {
            for k in 0..624 {
                let y = (self.mt[k] & UPPER) | (self.mt[(k + 1) % 624] & LOWER);
                let mut v = self.mt[(k + 397) % 624] ^ (y >> 1);
                if y & 1 != 0 {
                    v ^= MATRIX;
                }
                self.mt[k] = v;
            }
            self.idx = 0;
        }
        let mut y = self.mt[self.idx];
        self.idx += 1;
        y ^= y >> 11;
        y ^= (y << 7) & 0x9d2c_5680;
        y ^= (y << 15) & 0xefc6_0000;
        y ^= y >> 18;
        y
    }

    /// `randrange(n)` for 0 < n < 2**32 (`_randbelow_with_getrandbits`).
    pub fn randrange(&mut self, n: u32) -> u32 {
        let k = 32 - n.leading_zeros();
        loop {
            let r = self.next_u32() >> (32 - k);
            if r < n {
                return r;
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn random_matches_cpython() {
        // random.Random(1).randrange(50) x 20, from CPython 3.12.
        let mut r = PyRandom::new(1);
        let got: Vec<u32> = (0..20).map(|_| r.randrange(50)).collect();
        assert_eq!(got, [8, 36, 48, 4, 16, 7, 31, 48, 28, 30, 41, 24, 13, 6, 31, 1, 24, 27, 38, 48]);
    }

    #[test]
    fn formats_like_python() {
        assert_eq!(format_g(137.0 * 0.6), "82.2");
        assert_eq!(format_g(67.0 * 0.6), "40.2");
        assert_eq!(format_g(102.0 * 0.6), "61.2");
        assert_eq!(format_g(100.0), "100");
        assert_eq!(float_repr(1.0), "1.0");
        assert_eq!(float_repr(0.5), "0.5");
        assert_eq!(float_repr(1e-5), "1e-05");
        assert_eq!(float_repr(1e16), "1e+16");
        assert_eq!(escape("<a href=\"x\">'&'</a>"), "&lt;a href=&quot;x&quot;&gt;&#x27;&amp;&#x27;&lt;/a&gt;");
        let mut out = Vec::new();
        json_str_into(&mut out, "a\"\\\n\u{1}é");
        assert_eq!(String::from_utf8(out).unwrap(), "\"a\\\"\\\\\\n\\u0001é\"");
    }
}
