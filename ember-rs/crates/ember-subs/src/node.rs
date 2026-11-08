use std::fmt;

use crate::util::{is_blank, is_java_whitespace, is_re_space, short_sha256};

const INFO_HOSTS: [&str; 4] = ["0.0.0.0", "127.0.0.1", "localhost", "::1"];
const SHARE_SCHEMES: [&str; 6] = ["vless://", "vmess://", "trojan://", "ss://", "hysteria2://", "hy2://"];
const INFO_WORDS: [&str; 12] = ["⏳", "⌛", "📊", "روز", "اعتبار", "حجم", "باقی", "expire", "remaining", "days", "left", "traffic"];

const REGIONAL_A: u32 = 0x1F1E6;
const REGIONAL_Z: u32 = 0x1F1FF;

/// One server from a subscription. `raw` is the share link (or stored form) it came from, so the node can be rebuilt
/// later. `raw` can hold the account's key: it is left out of `Debug`.
#[derive(Clone, PartialEq, Eq)]
pub struct Node {
    pub id: String,
    pub name: String,
    pub protocol: String,
    pub host: String,
    pub port: i32,
    pub raw: String,
}

impl fmt::Debug for Node {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("Node")
            .field("id", &self.id)
            .field("name", &self.name)
            .field("protocol", &self.protocol)
            .field("host", &self.host)
            .field("port", &self.port)
            .finish_non_exhaustive()
    }
}

impl Node {
    pub(crate) fn new(raw: &str, protocol: &str, name: &str, host: &str, port: i32) -> Node {
        Node {
            id: short_sha256(raw),
            name: if is_blank(name) { format!("{host}:{port}") } else { name.to_string() },
            protocol: protocol.to_string(),
            host: host.to_string(),
            port,
            raw: raw.to_string(),
        }
    }

    /// The link another app can import for this server, or `None` for kinds that have none (files, WireGuard, OpenVPN).
    pub fn share_link(&self) -> Option<&str> {
        SHARE_SCHEMES.iter().any(|s| self.raw.starts_with(s)).then_some(self.raw.as_str())
    }

    /// Placeholder rows panels add to show traffic left or expiry ("99 GB left"); they cannot be connected to.
    pub fn is_info(&self) -> bool {
        INFO_HOSTS.contains(&self.host.to_lowercase().as_str()) || self.port <= 1 || looks_like_information(&self.name)
    }

    /// `title` without emoji and symbols, for places that cannot draw them (the Windows tray menu).
    pub fn plain_title(&self) -> String {
        let title = self.title();
        let stripped: String = title.chars().filter(|c| !is_emoji_like(*c as u32)).collect();
        let mut collapsed = String::new();
        let mut in_space = false;
        for c in stripped.chars() {
            if is_re_space(c) {
                if !in_space {
                    collapsed.push(' ');
                }
                in_space = true;
            } else {
                collapsed.push(c);
                in_space = false;
            }
        }
        let collapsed = collapsed.trim();
        if collapsed.is_empty() { title } else { collapsed.to_string() }
    }

    /// Country code from the flag emoji at the start of the name. With two flags (entry, then exit) the last wins.
    pub fn flag_code(&self) -> Option<String> {
        self.leading_flags().0.pop()
    }

    /// The name without the leading flag emojis (the app draws the flag itself).
    pub fn title(&self) -> String {
        let rest = self.leading_flags().1;
        if is_blank(&rest) { self.name.clone() } else { rest }
    }

    fn leading_flags(&self) -> (Vec<String>, String) {
        let chars: Vec<char> = self.name.chars().collect();
        let mut codes = Vec::new();
        let mut i = 0;
        while i < chars.len() {
            while i < chars.len() && is_java_whitespace(chars[i]) {
                i += 1;
            }
            let is_flag = |c: char| (REGIONAL_A..=REGIONAL_Z).contains(&(c as u32));
            if i + 1 < chars.len() && is_flag(chars[i]) && is_flag(chars[i + 1]) {
                let letter = |c: char| char::from_u32('a' as u32 + (c as u32 - REGIONAL_A)).unwrap_or('?');
                codes.push(format!("{}{}", letter(chars[i]), letter(chars[i + 1])));
                i += 2;
            } else {
                break;
            }
        }
        (codes, chars[i..].iter().collect::<String>().trim().to_string())
    }
}

fn is_emoji_like(cp: u32) -> bool {
    (0x1F000..=0x1FAFF).contains(&cp)
        || (0x2300..=0x23FF).contains(&cp)
        || (0x2600..=0x27BF).contains(&cp)
        || (0x2B00..=0x2BFF).contains(&cp)
        || cp == 0xFE0F
        || cp == 0x200D
}

/// Panels put usage messages in the list as if they were servers ("96 GB ⏳∞ days left").
fn looks_like_information(name: &str) -> bool {
    let lower = name.to_lowercase();
    has_data_amount(&lower) && INFO_WORDS.iter().any(|w| lower.contains(&w.to_lowercase()))
}

/// `(?i)\d+(\.\d+)?\s*(GB|MB|TB)`: a digit, optional whitespace, then a unit. [lower] is already lower-cased.
fn has_data_amount(lower: &str) -> bool {
    let chars: Vec<char> = lower.chars().collect();
    (0..chars.len().saturating_sub(1)).any(|k| {
        let unit = matches!((chars[k], chars[k + 1]), ('g' | 'm' | 't', 'b'));
        if !unit {
            return false;
        }
        let mut j = k;
        while j > 0 && is_re_space(chars[j - 1]) {
            j -= 1;
        }
        j > 0 && chars[j - 1].is_ascii_digit()
    })
}
