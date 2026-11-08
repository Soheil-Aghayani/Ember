//! Small helpers that reproduce the exact behaviour of the Kotlin/Java calls the original code relies on.

use base64::alphabet;
use base64::engine::{DecodePaddingMode, GeneralPurpose, GeneralPurposeConfig};
use base64::Engine;
use serde_json::Value;

use crate::error::ParseError;

pub(crate) fn before(s: &str, c: char) -> &str {
    s.split_once(c).map_or(s, |p| p.0)
}
pub(crate) fn after(s: &str, c: char) -> Option<&str> {
    s.split_once(c).map(|p| p.1)
}
pub(crate) fn before_last(s: &str, c: char) -> &str {
    s.rsplit_once(c).map_or(s, |p| p.0)
}
pub(crate) fn after_last(s: &str, c: char) -> &str {
    s.rsplit_once(c).map_or(s, |p| p.1)
}

/// Kotlin `String.lines()`: splits on `\r\n`, `\n` and `\r`; a trailing newline yields a final empty line.
pub(crate) fn kt_lines(s: &str) -> Vec<&str> {
    let mut out = Vec::new();
    let bytes = s.as_bytes();
    let mut start = 0;
    let mut i = 0;
    while i < bytes.len() {
        match bytes[i] {
            b'\n' => {
                out.push(&s[start..i]);
                start = i + 1;
            }
            b'\r' => {
                out.push(&s[start..i]);
                if bytes.get(i + 1) == Some(&b'\n') {
                    i += 1;
                }
                start = i + 1;
            }
            _ => {}
        }
        i += 1;
    }
    out.push(&s[start..]);
    out
}

/// Java's `\s` without flags: ASCII whitespace only.
pub(crate) fn is_re_space(c: char) -> bool {
    matches!(c, ' ' | '\t' | '\n' | '\u{0B}' | '\u{0C}' | '\r')
}

/// `Character.isWhitespace` (no-break spaces and NEL are not whitespace there).
pub(crate) fn is_java_whitespace(c: char) -> bool {
    (c.is_whitespace() && !matches!(c, '\u{00A0}' | '\u{2007}' | '\u{202F}' | '\u{0085}')) || matches!(c, '\u{1C}'..='\u{1F}')
}

/// `URLDecoder.decode(text.replace("+", "%2B"), "UTF-8")`: `+` stays a plus; a malformed `%` escape is an error.
pub(crate) fn url_decode(text: &str) -> Result<String, ParseError> {
    let bytes = text.as_bytes();
    let mut out = Vec::with_capacity(bytes.len());
    let mut i = 0;
    while i < bytes.len() {
        if bytes[i] == b'%' {
            let bad = ParseError::Invalid("bad percent escape");
            let hex = bytes.get(i + 1..i + 3).ok_or_else(|| bad.clone())?;
            if !hex.iter().all(u8::is_ascii_hexdigit) {
                return Err(bad);
            }
            let hex = std::str::from_utf8(hex).map_err(|_| bad.clone())?;
            out.push(u8::from_str_radix(hex, 16).map_err(|_| bad)?);
            i += 3;
        } else {
            out.push(bytes[i]);
            i += 1;
        }
    }
    Ok(String::from_utf8_lossy(&out).into_owned())
}

const LENIENT: GeneralPurpose = GeneralPurpose::new(
    &alphabet::STANDARD,
    GeneralPurposeConfig::new()
        .with_decode_allow_trailing_bits(true)
        .with_decode_padding_mode(DecodePaddingMode::Indifferent),
);

/// Standard or URL-safe base64, with or without padding (whitespace ignored).
pub fn decode_base64(text: &str) -> Result<Vec<u8>, ParseError> {
    let clean: String = text
        .chars()
        .filter(|c| !is_java_whitespace(*c))
        .map(|c| match c {
            '-' => '+',
            '_' => '/',
            c => c,
        })
        .collect();
    LENIENT.decode(clean.trim_end_matches('=')).map_err(|_| ParseError::Invalid("bad base64"))
}

pub(crate) fn decode_base64_text(text: &str) -> Result<String, ParseError> {
    Ok(String::from_utf8_lossy(&decode_base64(text)?).into_owned())
}

pub(crate) fn b64(text: &str) -> String {
    base64::engine::general_purpose::STANDARD.encode(text.as_bytes())
}

/// First 12 hex digits of SHA-256: the node id.
pub(crate) fn short_sha256(text: &str) -> String {
    sha256(text.as_bytes()).iter().take(6).map(|b| format!("{b:02x}")).collect()
}

pub(crate) fn sha256(data: &[u8]) -> [u8; 32] {
    const K: [u32; 64] = [
        0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5, 0xd807aa98, 0x12835b01,
        0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174, 0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc,
        0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da, 0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147,
        0x06ca6351, 0x14292967, 0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
        0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070, 0x19a4c116, 0x1e376c08,
        0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3, 0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208,
        0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2,
    ];
    let mut h: [u32; 8] = [0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19];
    let mut msg = data.to_vec();
    msg.push(0x80);
    while msg.len() % 64 != 56 {
        msg.push(0);
    }
    msg.extend_from_slice(&((data.len() as u64) * 8).to_be_bytes());
    for chunk in msg.chunks(64) {
        let mut w = [0u32; 64];
        for i in 0..16 {
            w[i] = u32::from_be_bytes([chunk[4 * i], chunk[4 * i + 1], chunk[4 * i + 2], chunk[4 * i + 3]]);
        }
        for i in 16..64 {
            let s0 = w[i - 15].rotate_right(7) ^ w[i - 15].rotate_right(18) ^ (w[i - 15] >> 3);
            let s1 = w[i - 2].rotate_right(17) ^ w[i - 2].rotate_right(19) ^ (w[i - 2] >> 10);
            w[i] = w[i - 16].wrapping_add(s0).wrapping_add(w[i - 7]).wrapping_add(s1);
        }
        let [mut a, mut b, mut c, mut d, mut e, mut f, mut g, mut hh] = h;
        for i in 0..64 {
            let s1 = e.rotate_right(6) ^ e.rotate_right(11) ^ e.rotate_right(25);
            let ch = (e & f) ^ (!e & g);
            let t1 = hh.wrapping_add(s1).wrapping_add(ch).wrapping_add(K[i]).wrapping_add(w[i]);
            let s0 = a.rotate_right(2) ^ a.rotate_right(13) ^ a.rotate_right(22);
            let maj = (a & b) ^ (a & c) ^ (b & c);
            let t2 = s0.wrapping_add(maj);
            hh = g;
            g = f;
            f = e;
            e = d.wrapping_add(t1);
            d = c;
            c = b;
            b = a;
            a = t1.wrapping_add(t2);
        }
        for (x, y) in h.iter_mut().zip([a, b, c, d, e, f, g, hh]) {
            *x = x.wrapping_add(y);
        }
    }
    let mut out = [0u8; 32];
    for (i, v) in h.iter().enumerate() {
        out[4 * i..4 * i + 4].copy_from_slice(&v.to_be_bytes());
    }
    out
}

// ---- org.json style accessors -------------------------------------------------------------------------------------

/// `optString(key)`: missing and null give "", numbers and booleans their text.
pub(crate) fn opt_string(v: &Value, key: &str) -> String {
    match v.get(key) {
        Some(Value::String(s)) => s.clone(),
        None | Some(Value::Null) => String::new(),
        Some(other) => other.to_string(),
    }
}

pub(crate) fn opt_string_or(v: &Value, key: &str, default: &str) -> String {
    match v.get(key) {
        None | Some(Value::Null) => default.to_string(),
        _ => opt_string(v, key),
    }
}

/// `optInt(key, default)`.
pub(crate) fn opt_int(v: &Value, key: &str, default: i32) -> i32 {
    match v.get(key) {
        Some(Value::Number(n)) => n.as_i64().map(|i| i as i32).or_else(|| n.as_f64().map(|f| f as i32)).unwrap_or(default),
        Some(Value::String(s)) => {
            s.trim().parse::<i32>().ok().or_else(|| s.trim().parse::<f64>().ok().map(|f| f as i32)).unwrap_or(default)
        }
        _ => default,
    }
}

pub(crate) fn is_blank(s: &str) -> bool {
    s.trim().is_empty()
}

/// The value of `key` when present and not blank.
pub(crate) fn non_blank<'a>(map: &'a std::collections::HashMap<String, String>, key: &str) -> Option<&'a str> {
    map.get(key).map(String::as_str).filter(|s| !is_blank(s))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn sha256_known_answers() {
        let hex = |d: [u8; 32]| d.iter().map(|b| format!("{b:02x}")).collect::<String>();
        assert_eq!(hex(sha256(b"abc")), "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
        assert_eq!(hex(sha256(b"")), "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
        let long = "a".repeat(1000);
        assert_eq!(hex(sha256(long.as_bytes())), "41edece42d63e8d9bf515a9ba6932e1c20cbc9f5a5d134645adb5db1b9737ea3");
    }

    #[test]
    fn lines_and_decoding_behave_like_kotlin() {
        assert_eq!(kt_lines("a\r\nb\rc\n"), ["a", "b", "c", ""]);
        assert_eq!(url_decode("a+b%20c%F0%9F%87%A9").unwrap(), "a+b c\u{1F1E9}");
        assert!(url_decode("100%").is_err() && url_decode("%zz").is_err() && url_decode("%+1").is_err());
        assert_eq!(decode_base64("YWJj\n").unwrap(), b"abc");
        assert_eq!(decode_base64("YWJjZA").unwrap(), b"abcd"); // no padding
        assert_eq!(decode_base64("YW-_").unwrap(), decode_base64("YW+/").unwrap()); // URL-safe alphabet
        assert!(decode_base64("Y").is_err() && decode_base64("a:b").is_err());
    }
}
