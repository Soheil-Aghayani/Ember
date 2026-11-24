//! Opening a web link in the user's browser.

/// True for a link that is safe to hand to the shell: `http://` or `https://` with a host and nothing that could be read
/// as a command (spaces, quotes, control characters).
pub fn is_web_link(url: &str) -> bool {
    let rest = url.strip_prefix("https://").or_else(|| url.strip_prefix("http://"));
    let Some(rest) = rest else { return false };
    !rest.is_empty()
        && !rest.starts_with('/')
        && url.chars().all(|c| !c.is_control() && !c.is_whitespace() && !matches!(c, '"' | '<' | '>' | '^' | '`' | '|'))
}

/// Opens `url` with the default browser. False when it is not a web link or Windows could not open it.
#[cfg(windows)]
pub fn open_url(url: &str) -> bool {
    if !is_web_link(url) {
        return false;
    }
    shell_execute(url, None)
}

/// Opens desktop Firefox at its privacy settings, where the built-in VPN setup is available.
#[cfg(windows)]
pub fn open_firefox() -> bool {
    shell_execute("firefox.exe", Some("about:preferences#privacy"))
}

#[cfg(windows)]
fn shell_execute(file: &str, parameters: Option<&str>) -> bool {
    use windows::core::{w, HSTRING};
    use windows::Win32::UI::Shell::ShellExecuteW;
    use windows::Win32::UI::WindowsAndMessaging::SW_SHOWNORMAL;

    let target = HSTRING::from(file);
    let parameters = parameters.map(HSTRING::from);
    // SAFETY: all strings are valid, NUL-terminated wide strings that outlive the call; no window handle is passed.
    let result = unsafe {
        ShellExecuteW(None, w!("open"), &target, parameters.as_ref(), None, SW_SHOWNORMAL)
    };
    // ShellExecuteW reports success with a value above 32
    result.0 as usize > 32
}

#[cfg(not(windows))]
pub fn open_url(_url: &str) -> bool {
    false
}

#[cfg(not(windows))]
pub fn open_firefox() -> bool {
    false
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn only_plain_web_links_pass() {
        assert!(is_web_link("https://t.me/env_soh"));
        assert!(is_web_link("http://example.com/a?b=c&d=e#f"));
        for bad in ["", "https://", "https:///x", "ftp://example.com", "file:///C:/x", "javascript:alert(1)", "https://a b", "https://a\"b", "https://a|calc", "cmd /c calc", "https://a\nb"] {
            assert!(!is_web_link(bad), "{bad:?}");
        }
    }
}
