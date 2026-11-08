//! Cloudflare WARP, the free VPN of Cloudflare's 1.1.1.1 app. It is WireGuard: Ember registers a device with Cloudflare,
//! gets the tunnel's addresses, and the result runs like any WireGuard server. Port of `Warp.kt`.

use std::time::{SystemTime, UNIX_EPOCH};

use base64::engine::general_purpose::STANDARD;
use base64::Engine;
use serde_json::{json, Value};

use crate::error::{file_error, ParseError};
use crate::http::{Http, HttpError};
use crate::util::{before_last, decode_base64, is_blank, opt_string};

const API: &str = "https://api.cloudflareclient.com/v0a2158/reg";

/// The ports WARP listens on; the first is the usual one, the others help when a network blocks it.
const PORTS: [u16; 3] = [2408, 500, 4500];

pub struct Registration {
    pub private_key: String,
    pub addresses: Vec<String>,
    pub peer_public_key: String,
    pub endpoint_host: String,
    /// Three bytes Cloudflare ties to the device; WARP drops traffic that does not carry them.
    pub reserved: Vec<i32>,
}

/// A fresh X25519 key pair as the base64 text WireGuard uses: (private, public).
pub fn new_key_pair() -> Result<(String, String), getrandom::Error> {
    let mut private = [0u8; 32];
    getrandom::fill(&mut private)?;
    Ok((STANDARD.encode(private), STANDARD.encode(x25519_public_key(&private))))
}

/// Reads Cloudflare's answer to the registration.
pub fn parse_registration(json: &str, private_key: &str) -> Result<Registration, ParseError> {
    let bad = || ParseError::File("Cloudflare sent an answer Ember could not read.".into());
    let root: Value = serde_json::from_str(json).map_err(|_| bad())?;
    let config = root.get("config").filter(|c| c.is_object()).ok_or_else(bad)?;
    let peer = config.get("peers").and_then(Value::as_array).and_then(|p| p.first()).filter(|p| p.is_object()).ok_or_else(bad)?;
    let addresses = config.get("interface").and_then(|i| i.get("addresses")).filter(|a| a.is_object()).ok_or_else(bad)?;
    let list: Vec<String> = [opt_string(addresses, "v4"), opt_string(addresses, "v6")]
        .into_iter()
        .filter(|a| !is_blank(a))
        .map(|a| {
            if a.contains('/') {
                a
            } else if a.contains(':') {
                format!("{a}/128")
            } else {
                format!("{a}/32")
            }
        })
        .collect();
    if list.is_empty() {
        return file_error("Cloudflare sent no tunnel address.");
    }
    let host = peer.get("endpoint").map(|e| before_last(&opt_string(e, "host"), ':').to_string()).filter(|h| !is_blank(h));
    let reserved = config
        .get("client_id")
        .and_then(Value::as_str)
        .and_then(|id| decode_base64(id).ok())
        .map(|bytes| bytes.into_iter().take(3).map(i32::from).collect())
        .unwrap_or_default();
    let public_key = peer.get("public_key").and_then(Value::as_str).ok_or_else(bad)?;
    Ok(Registration {
        private_key: private_key.to_string(),
        addresses: list,
        peer_public_key: public_key.to_string(),
        endpoint_host: host.unwrap_or_else(|| "engage.cloudflareclient.com".into()),
        reserved,
    })
}

/// One WireGuard file per port, named for it.
pub fn configs(reg: &Registration) -> Vec<(String, String)> {
    PORTS
        .iter()
        .map(|port| {
            let mut text = format!("[Interface]\nPrivateKey = {}\nAddress = {}\nMTU = 1280\n", reg.private_key, reg.addresses.join(", "));
            if reg.reserved.len() == 3 {
                let r: Vec<String> = reg.reserved.iter().map(i32::to_string).collect();
                text.push_str(&format!("Reserved = {}\n", r.join(", ")));
            }
            text.push_str(&format!(
                "\n[Peer]\nPublicKey = {}\nAllowedIPs = 0.0.0.0/0, ::/0\nEndpoint = {}:{port}\n",
                reg.peer_public_key, reg.endpoint_host
            ));
            let name = if *port == PORTS[0] { "Cloudflare WARP".to_string() } else { format!("Cloudflare WARP ({port})") };
            (name, text)
        })
        .collect()
}

/// Registers a new free device with Cloudflare.
pub fn register(http: &Http) -> Result<Registration, WarpError> {
    let (private_key, public_key) = new_key_pair().map_err(|_| WarpError::NoRandomness)?;
    let body = json!({
        "key": public_key, "install_id": "", "fcm_token": "", "tos": iso_now(),
        "model": "PC", "serial_number": "", "locale": "en_US",
    });
    let headers = [("User-Agent", "okhttp/3.12.1"), ("CF-Client-Version", "a-6.3-1922")];
    let answer = http.send_json("POST", API, &headers, &body).map_err(WarpError::Http)?;
    let registration = parse_registration(&answer, &private_key).map_err(WarpError::Parse)?;
    // switch WARP on for the new device (best effort; the tunnel details above are already usable)
    if let Ok(root) = serde_json::from_str::<Value>(&answer) {
        if let (Some(id), Some(token)) = (root["id"].as_str(), root["token"].as_str()) {
            let auth = format!("Bearer {token}");
            let headers = [("User-Agent", "okhttp/3.12.1"), ("CF-Client-Version", "a-6.3-1922"), ("Authorization", auth.as_str())];
            let _ = http.send_json("PATCH", &format!("{API}/{id}"), &headers, &json!({"warp_enabled": true}));
        }
    }
    Ok(registration)
}

#[derive(Debug, thiserror::Error)]
pub enum WarpError {
    #[error("{0}")]
    Http(HttpError),
    #[error("{0}")]
    Parse(ParseError),
    #[error("This computer gave no random numbers for the WARP key.")]
    NoRandomness,
}

/// `Instant.now().toString()`-style UTC time (millisecond precision).
fn iso_now() -> String {
    let now = SystemTime::now().duration_since(UNIX_EPOCH).unwrap_or_default();
    iso_utc(now.as_secs() as i64, now.subsec_millis())
}

fn iso_utc(secs: i64, millis: u32) -> String {
    let days = secs.div_euclid(86_400);
    let rem = secs.rem_euclid(86_400);
    // civil-from-days (Howard Hinnant)
    let z = days + 719_468;
    let era = z.div_euclid(146_097);
    let doe = z.rem_euclid(146_097);
    let yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let day = doy - (153 * mp + 2) / 5 + 1;
    let month = if mp < 10 { mp + 3 } else { mp - 9 };
    let year = yoe + era * 400 + i64::from(month <= 2);
    format!("{year:04}-{month:02}-{day:02}T{:02}:{:02}:{:02}.{millis:03}Z", rem / 3600, rem % 3600 / 60, rem % 60)
}

// ---- X25519 (RFC 7748), public key only -----------------------------------------------------------------------------
// Ported from the BigInteger version in Warp.kt; the field arithmetic is the compact 16 x 16-bit-limb form of TweetNaCl.

type Gf = [i64; 16];

fn car25519(o: &mut Gf) {
    for i in 0..16 {
        o[i] += 1 << 16;
        let c = o[i] >> 16;
        if i < 15 {
            o[i + 1] += c - 1;
        } else {
            o[0] += 38 * (c - 1);
        }
        o[i] -= c << 16;
    }
}

fn sel25519(p: &mut Gf, q: &mut Gf, b: i64) {
    let c = !(b - 1);
    for i in 0..16 {
        let t = c & (p[i] ^ q[i]);
        p[i] ^= t;
        q[i] ^= t;
    }
}

fn pack25519(n: &Gf) -> [u8; 32] {
    let mut t = *n;
    car25519(&mut t);
    car25519(&mut t);
    car25519(&mut t);
    for _ in 0..2 {
        let mut m: Gf = [0; 16];
        m[0] = t[0] - 0xffed;
        for i in 1..15 {
            m[i] = t[i] - 0xffff - ((m[i - 1] >> 16) & 1);
            m[i - 1] &= 0xffff;
        }
        m[15] = t[15] - 0x7fff - ((m[14] >> 16) & 1);
        let b = (m[15] >> 16) & 1;
        m[14] &= 0xffff;
        sel25519(&mut t, &mut m, 1 - b);
    }
    let mut o = [0u8; 32];
    for i in 0..16 {
        o[2 * i] = (t[i] & 0xff) as u8;
        o[2 * i + 1] = (t[i] >> 8) as u8;
    }
    o
}

fn add(a: &Gf, b: &Gf) -> Gf {
    std::array::from_fn(|i| a[i] + b[i])
}
fn sub(a: &Gf, b: &Gf) -> Gf {
    std::array::from_fn(|i| a[i] - b[i])
}
fn mul(a: &Gf, b: &Gf) -> Gf {
    let mut t = [0i64; 31];
    for i in 0..16 {
        for j in 0..16 {
            t[i + j] += a[i] * b[j];
        }
    }
    for i in 0..15 {
        t[i] += 38 * t[i + 16];
    }
    let mut o: Gf = std::array::from_fn(|i| t[i]);
    car25519(&mut o);
    car25519(&mut o);
    o
}

fn invert(i: &Gf) -> Gf {
    let mut c = *i;
    for a in (0..=253).rev() {
        c = mul(&c, &c);
        if a != 2 && a != 4 {
            c = mul(&c, i);
        }
    }
    c
}

/// The public half of an X25519 key: the private scalar (clamped here) times the base point.
pub fn x25519_public_key(private_key: &[u8; 32]) -> [u8; 32] {
    let mut z = *private_key;
    z[31] = (z[31] & 127) | 64;
    z[0] &= 248;
    // base point x = 9
    let x: Gf = std::array::from_fn(|i| if i == 0 { 9 } else { 0 });
    let mut a: Gf = [0; 16];
    let mut b = x;
    let mut c: Gf = [0; 16];
    let mut d: Gf = [0; 16];
    a[0] = 1;
    d[0] = 1;
    let k121665: Gf = std::array::from_fn(|i| match i {
        0 => 0xDB41,
        1 => 1,
        _ => 0,
    });
    for i in (0..=254).rev() {
        let r = i64::from((z[i >> 3] >> (i & 7)) & 1);
        sel25519(&mut a, &mut b, r);
        sel25519(&mut c, &mut d, r);
        let mut e = add(&a, &c);
        a = sub(&a, &c);
        c = add(&b, &d);
        b = sub(&b, &d);
        d = mul(&e, &e);
        let f = mul(&a, &a);
        a = mul(&c, &a);
        c = mul(&b, &e);
        e = add(&a, &c);
        a = sub(&a, &c);
        b = mul(&a, &a);
        c = sub(&d, &f);
        a = mul(&c, &k121665);
        a = add(&a, &d);
        c = mul(&c, &a);
        a = mul(&d, &f);
        d = mul(&b, &x);
        b = mul(&e, &e);
        sel25519(&mut a, &mut b, r);
        sel25519(&mut c, &mut d, r);
    }
    let inv = invert(&c);
    pack25519(&mul(&a, &inv))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn unhex(s: &str) -> [u8; 32] {
        let v: Vec<u8> = (0..32).map(|i| u8::from_str_radix(&s[2 * i..2 * i + 2], 16).unwrap()).collect();
        v.try_into().unwrap()
    }

    #[test]
    fn x25519_matches_rfc_7748_vectors() {
        let alice = unhex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a");
        assert_eq!(x25519_public_key(&alice), unhex("8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a"));
        let bob = unhex("5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb");
        assert_eq!(x25519_public_key(&bob), unhex("de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f"));
    }

    #[test]
    fn formats_utc_time() {
        assert_eq!(iso_utc(0, 0), "1970-01-01T00:00:00.000Z");
        assert_eq!(iso_utc(1_700_000_000, 7), "2023-11-14T22:13:20.007Z");
    }
}
