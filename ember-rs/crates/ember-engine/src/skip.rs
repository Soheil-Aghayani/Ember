//! The "skip the VPN" list: addresses and names the user wants to reach directly. Port of `resolveExclusions` in
//! `EmberEngine.kt`. Names are looked up on the real network (before the tunnel is up), so the helper can route their
//! addresses around it.

use std::net::Ipv4Addr;

use regex::Regex;

use crate::log;

const TAG: &str = "EmberEngine";
const MAX_RANGES: usize = 300;

/// What the list turns into: IPv4 ranges for the helper, and each name with its addresses for Xray.
#[derive(Debug, Default, PartialEq, Eq)]
pub struct SkipList {
    pub cidrs: Vec<String>,
    pub domains: Vec<(String, Vec<String>)>,
}

/// True for an IPv4 address or range (`1.2.3.4` or `10.0.0.0/8`), as far as the shape goes.
pub fn is_address(entry: &str) -> bool {
    let (ip, bits) = match entry.split_once('/') {
        Some((ip, bits)) => (ip, Some(bits)),
        None => (entry, None),
    };
    ip.parse::<Ipv4Addr>().is_ok() && bits.is_none_or(|b| b.len() <= 2 && b.parse::<u8>().is_ok_and(|n| n <= 32))
}

/// True for a host name with at least one dot.
pub fn is_domain(entry: &str) -> bool {
    let re = Regex::new(r"^[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?(\.[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?)+$").expect("static pattern");
    re.is_match(entry)
}

/// Turns the saved entries into a [`SkipList`]. `lookup` returns the IPv4 addresses of a name (empty when it has none).
pub fn resolve(entries: &[String], lookup: impl Fn(&str) -> Vec<String>) -> SkipList {
    let mut cidrs: Vec<String> = Vec::new();
    let mut domains: Vec<(String, Vec<String>)> = Vec::new();
    for entry in entries {
        if is_address(entry) {
            if !cidrs.contains(entry) {
                cidrs.push(entry.clone());
            }
        } else if is_domain(entry) {
            let ips = lookup(entry);
            for ip in &ips {
                let range = format!("{ip}/32");
                if !cidrs.contains(&range) {
                    cidrs.push(range);
                }
            }
            if ips.is_empty() {
                log::warn(TAG, &format!("could not look up {entry} for the skip list"));
            } else {
                domains.push((entry.to_lowercase(), ips));
            }
        } else {
            log::warn(TAG, &format!("ignored an invalid skip-list entry: {entry}"));
        }
        if cidrs.len() >= MAX_RANGES {
            break;
        }
    }
    SkipList { cidrs, domains }
}

/// The IPv4 addresses a name resolves to on this computer.
pub fn lookup_ipv4(name: &str) -> Vec<String> {
    use std::net::ToSocketAddrs;
    let Ok(found) = (name, 0).to_socket_addrs() else { return Vec::new() };
    let mut out: Vec<String> = Vec::new();
    for addr in found {
        if let std::net::IpAddr::V4(v4) = addr.ip() {
            let text = v4.to_string();
            if !out.contains(&text) {
                out.push(text);
            }
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    fn strings(items: &[&str]) -> Vec<String> {
        items.iter().map(|s| s.to_string()).collect()
    }

    #[test]
    fn addresses_ranges_and_names_are_told_apart() {
        assert!(is_address("10.0.0.0/8") && is_address("1.2.3.4") && is_address("192.168.1.0/24"));
        assert!(!is_address("1.2.3") && !is_address("1.2.3.4/33") && !is_address("999.1.1.1") && !is_address("example.com"));
        assert!(is_domain("example.com") && is_domain("a-b.example.co.uk"));
        assert!(!is_domain("localhost") && !is_domain("-bad.com") && !is_domain("bad_name.com") && !is_domain("1.2.3.4/8"));
    }

    #[test]
    fn names_are_looked_up_and_become_slash_32_ranges() {
        let list = resolve(&strings(&["10.0.0.0/8", "Bank.Example.com", "10.0.0.0/8", "nothing.example.net", "not valid"]), |name| {
            if name == "Bank.Example.com" {
                vec!["192.0.2.7".into(), "192.0.2.8".into()]
            } else {
                Vec::new()
            }
        });
        assert_eq!(list.cidrs, ["10.0.0.0/8", "192.0.2.7/32", "192.0.2.8/32"]);
        assert_eq!(list.domains, [("bank.example.com".to_string(), vec!["192.0.2.7".to_string(), "192.0.2.8".to_string()])]);
    }

    #[test]
    fn the_list_is_capped() {
        let many: Vec<String> = (0..400).map(|i| format!("10.{}.{}.0/24", i / 200, i % 200)).collect();
        assert_eq!(resolve(&many, |_| Vec::new()).cidrs.len(), MAX_RANGES);
    }
}
