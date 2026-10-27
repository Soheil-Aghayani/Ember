use std::{fmt, net::Ipv4Addr};

/// Like Test-Ipv4: four decimal octets, including leading zeroes (never octal).
pub fn ipv4(text: &str) -> Option<Ipv4Addr> {
    let parts: Vec<_> = text.split('.').collect();
    if parts.len() != 4 {
        return None;
    }
    let mut octets = [0; 4];
    for (slot, part) in octets.iter_mut().zip(parts) {
        if part.is_empty() || part.len() > 3 || !part.bytes().all(|b| b.is_ascii_digit()) {
            return None;
        }
        *slot = part.parse().ok()?;
    }
    Some(Ipv4Addr::from(octets))
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Cidr {
    pub address: Ipv4Addr,
    pub bits: u8,
}

impl Cidr {
    pub fn parse(text: &str, minimum: u8, allow_host: bool) -> Option<Self> {
        let (address, bits) = match text.split_once('/') {
            Some((ip, suffix)) => {
                if suffix.is_empty()
                    || suffix.len() > 2
                    || !suffix.bytes().all(|b| b.is_ascii_digit())
                {
                    return None;
                }
                (ipv4(ip)?, suffix.parse::<u8>().ok()?)
            }
            None if allow_host => (ipv4(text)?, 32),
            None => return None,
        };
        if bits < minimum || bits > 32 {
            return None;
        }
        let mask = u32::MAX.checked_shl(32 - u32::from(bits)).unwrap_or(0);
        Some(Self {
            address: Ipv4Addr::from(u32::from(address) & mask),
            bits,
        })
    }

    pub fn host(address: Ipv4Addr) -> Self {
        Self { address, bits: 32 }
    }

    pub fn range(self) -> (u64, u64) {
        let start = u64::from(u32::from(self.address));
        (start, start + (1u64 << (32 - self.bits)) - 1)
    }
}

impl fmt::Display for Cidr {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "{}/{}", self.address, self.bits)
    }
}

/// u64 deliberately includes the sentinel 2^32, avoiding wraparound at the last IP.
pub fn complement(intervals: &[(u64, u64)]) -> Vec<String> {
    let mut sorted = intervals.to_vec();
    sorted.sort_unstable();
    let mut next = 0;
    let mut result = Vec::new();
    for (start, end) in sorted {
        if start > next {
            result.push(format!(
                "{}-{}",
                Ipv4Addr::from(next as u32),
                Ipv4Addr::from((start - 1) as u32)
            ));
        }
        next = next.max(end + 1);
    }
    if next <= u64::from(u32::MAX) {
        result.push(format!("{}-255.255.255.255", Ipv4Addr::from(next as u32)));
    }
    result
}

pub fn blocked_ranges(edges: &[Ipv4Addr], exclusions: &[Cidr]) -> Vec<String> {
    let mut allowed: Vec<_> = [
        "0.0.0.0/8",
        "10.0.0.0/8",
        "127.0.0.0/8",
        "169.254.0.0/16",
        "172.16.0.0/12",
        "192.168.0.0/16",
    ]
    .iter()
    .map(|s| Cidr::parse(s, 0, false).unwrap().range())
    .collect();
    allowed.push((3758096384, 4294967295));
    allowed.extend(edges.iter().map(|ip| Cidr::host(*ip).range()));
    allowed.extend(exclusions.iter().map(|cidr| cidr.range()));
    complement(&allowed)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn dotted_decimal_validation() {
        assert_eq!(ipv4("010.001.002.003").unwrap().to_string(), "10.1.2.3");
        for invalid in [
            "256.0.0.1",
            "1.2.3",
            "1.2.3.4.5",
            " 1.2.3.4",
            "1.2.3.4\n",
            "1.2.3.-1",
            "0x1.2.3.4",
            "0000.1.2.3",
        ] {
            assert!(ipv4(invalid).is_none(), "{invalid}");
        }
    }

    #[test]
    fn exclusion_bounds_and_normalization() {
        assert_eq!(
            Cidr::parse("172.31.44.9/12", 8, true).unwrap().to_string(),
            "172.16.0.0/12"
        );
        assert_eq!(
            Cidr::parse("1.2.3.4", 8, true).unwrap().to_string(),
            "1.2.3.4/32"
        );
        assert_eq!(
            Cidr::parse("255.255.255.255/8", 8, true)
                .unwrap()
                .to_string(),
            "255.0.0.0/8"
        );
        assert_eq!(
            Cidr::parse("1.2.3.4/0", 0, false).unwrap().range(),
            (0, 4294967295)
        );
        for s in [
            "1.2.3.4/7",
            "1.2.3.4/33",
            "1.2.3.4/008",
            "1.2.3.4/8/9",
            "::1",
            "1.2.3.4/",
            "1.2.3.4/+8",
        ] {
            assert!(Cidr::parse(s, 8, true).is_none(), "{s}");
        }
    }

    #[test]
    fn powershell_seven_ranges() {
        assert_eq!(
            blocked_ranges(&[ipv4("23.235.42.11").unwrap()], &[]),
            [
                "1.0.0.0-9.255.255.255",
                "11.0.0.0-23.235.42.10",
                "23.235.42.12-126.255.255.255",
                "128.0.0.0-169.253.255.255",
                "169.255.0.0-172.15.255.255",
                "172.32.0.0-192.167.255.255",
                "192.169.0.0-223.255.255.255",
            ]
        );
    }

    #[test]
    fn complement_merges_overlap_adjacency_and_duplicates() {
        assert_eq!(
            complement(&[
                (10, 20),
                (0, 4),
                (4, 9),
                (15, 18),
                (10, 20),
                (u32::MAX as u64, u32::MAX as u64)
            ]),
            ["0.0.0.21-255.255.255.254"]
        );
        assert!(complement(&[(0, u32::MAX as u64)]).is_empty());
        assert_eq!(complement(&[]), ["0.0.0.0-255.255.255.255"]);
        assert_eq!(
            complement(&[(1, u32::MAX as u64 - 1)]),
            ["0.0.0.0-0.0.0.0", "255.255.255.255-255.255.255.255"]
        );
    }

    #[test]
    fn exclusions_and_edges_cannot_be_blocked() {
        let exclusions = [Cidr::parse("23.235.42.11/8", 8, true).unwrap()];
        let ranges = blocked_ranges(&[ipv4("23.235.42.11").unwrap()], &exclusions);
        assert!(ranges.contains(&"11.0.0.0-22.255.255.255".to_string()));
        assert!(ranges.contains(&"24.0.0.0-126.255.255.255".to_string()));
    }
}
