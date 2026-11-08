//! The text a user pastes when something goes wrong on their computer: what runs where, then the recent log. Links to
//! servers, account ids and IP addresses are removed first; server and site names stay, because they are what explains
//! most failures. Port of `Diagnostics.kt` (same rules, same tests).

use regex::{Captures, Regex};
use std::sync::OnceLock;

struct Rules {
    link: Regex,
    uuid: Regex,
    ipv4: Regex,
    long_token: Regex,
}

fn rules() -> &'static Rules {
    static RULES: OnceLock<Rules> = OnceLock::new();
    RULES.get_or_init(|| Rules {
        link: Regex::new(r"(?i)\b(vless|vmess|trojan|ss|hysteria2|hy2|wireguard|socks5?)://\S+").unwrap(),
        uuid: Regex::new(r"(?i)\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\b").unwrap(),
        ipv4: Regex::new(r"\b\d{1,3}(\.\d{1,3}){3}\b").unwrap(),
        long_token: Regex::new(r"\b[A-Za-z0-9_\-+/=]{32,}\b").unwrap(), // keys and tokens
    })
}

/// `text` with server links, ids, IP addresses and long key-like strings removed.
pub fn redact(text: &str) -> String {
    let r = rules();
    let text = r.link.replace_all(text, |c: &Captures| format!("{}://<removed>", &c[1]));
    let text = r.uuid.replace_all(&text, "<id>");
    let text = r.ipv4.replace_all(&text, "<ip>");
    r.long_token.replace_all(&text, "<token>").into_owned()
}

/// What runs where. The caller supplies the lines (version of Windows, graphics, ...), so this stays plain text.
pub fn report(version: &str, system: &[String], lines: &[String]) -> String {
    let mut out = format!("Ember {version}\n");
    for line in system {
        out.push_str(line);
        out.push('\n');
    }
    out.push_str(&format!("--- last {} log lines (oldest first) ---\n", lines.len()));
    for line in lines {
        out.push_str(&redact(line));
        out.push('\n');
    }
    out
}

/// Windows version and the machine, one line each. Reads the version from `cmd /C ver` without opening a window.
pub fn system_lines(renderer: &str) -> Vec<String> {
    let cores = std::thread::available_parallelism().map(|n| n.get()).unwrap_or(1);
    vec![
        format!("Windows: {} ({})", windows_version(), std::env::consts::ARCH),
        format!("Rust build, {cores} cores"),
        format!("Graphics: {renderer}"),
    ]
}

fn windows_version() -> String {
    use std::os::windows::process::CommandExt;
    const CREATE_NO_WINDOW: u32 = 0x0800_0000;
    std::process::Command::new("cmd")
        .args(["/C", "ver"])
        .creation_flags(CREATE_NO_WINDOW)
        .output()
        .ok()
        .map(|o| String::from_utf8_lossy(&o.stdout).trim().to_string())
        .filter(|s| !s.is_empty())
        .unwrap_or_else(|| "unknown".to_string())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn removes_links_ids_and_addresses_but_keeps_names() {
        let out = redact(
            "connect vless://e12466ae-4b09-4951-a0cd-5d262beb81e7@fdh11.example.top:8080?security=reality&sni=x#UK now; \
             id e12466ae-4b09-4951-a0cd-5d262beb81e7 to 203.0.113.7; key 3q2-9Ab_cdEFghIJklMNopQRstUVwxYZ012345; \
             could not look up sv0.example.ir",
        );
        assert!(!out.contains("e12466ae"));
        assert!(!out.contains("203.0.113.7"));
        assert!(!out.contains("fdh11"));
        assert!(!out.contains("3q2-9Ab"));
        assert!(out.contains("vless://<removed>"));
        assert!(out.contains("sv0.example.ir"));
    }

    #[test]
    fn report_starts_with_the_version_and_redacts_the_log() {
        let out = report("1.0.101", &["Graphics: software".to_string()], &["a vmess://secret@host:1 b".to_string()]);
        assert!(out.starts_with("Ember 1.0.101"));
        assert!(!out.contains("secret"));
        assert!(out.contains("last 1 log lines"));
        assert!(out.contains("Graphics: software"));
    }

    #[test]
    fn scheme_case_is_kept_and_plain_text_is_untouched() {
        assert_eq!(redact("see TROJAN://pw@h:1 ok"), "see TROJAN://<removed> ok");
        assert_eq!(redact("started xray.exe (pid 12584)"), "started xray.exe (pid 12584)");
        // a version number is not an address
        assert_eq!(redact("Ember 1.0.101"), "Ember 1.0.101");
    }

    #[test]
    fn the_system_lines_are_not_empty() {
        let lines = system_lines("software");
        assert_eq!(lines.len(), 3);
        assert!(lines[0].starts_with("Windows: "));
    }
}
