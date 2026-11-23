//! Reads the shared, static app catalogue update feed. The website owns release availability; this client only compares
//! the version for its platform and never downloads or installs an update itself.

use std::io::Read;
use std::time::Duration;

pub const UPDATE_FEED_URL: &str = "https://app.agseyl.ir/updates.json";
pub const EMBER_RELEASE_PAGE: &str = "https://app.agseyl.ir/apps/ember/";

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct AvailableUpdate {
    pub version: String,
}

/// Returns a newer version from the shared catalogue feed. Bad, unreachable, or unsupported feeds are ignored quietly.
pub fn check(platform: &str, current_version: &str) -> Option<AvailableUpdate> {
    let agent = ureq::AgentBuilder::new()
        .timeout_connect(Duration::from_secs(3))
        .timeout_read(Duration::from_secs(5))
        .timeout_write(Duration::from_secs(5))
        .build();
    let response = agent
        .get(UPDATE_FEED_URL)
        .set("Accept", "application/json")
        .set("Cache-Control", "no-cache")
        .call()
        .ok()?;
    let mut body = String::new();
    response
        .into_reader()
        .take(64 * 1024)
        .read_to_string(&mut body)
        .ok()?;
    let feed: serde_json::Value = serde_json::from_str(&body).ok()?;
    if feed.get("schemaVersion")?.as_u64()? != 1 {
        return None;
    }
    let version = feed
        .get("apps")?
        .get("ember")?
        .get(platform)?
        .get("version")?
        .as_str()?;
    is_newer(version, current_version).then(|| AvailableUpdate {
        version: version.to_owned(),
    })
}

fn is_newer(candidate: &str, current: &str) -> bool {
    let parse = |version: &str| -> Option<Vec<u64>> {
        let parts = version
            .split('.')
            .map(str::parse)
            .collect::<Result<Vec<_>, _>>()
            .ok()?;
        (!parts.is_empty()).then_some(parts)
    };
    let (Some(candidate), Some(current)) = (parse(candidate), parse(current)) else {
        return false;
    };
    for index in 0..candidate.len().max(current.len()) {
        match (
            candidate.get(index).copied().unwrap_or(0),
            current.get(index).copied().unwrap_or(0),
        ) {
            (a, b) if a > b => return true,
            (a, b) if a < b => return false,
            _ => {}
        }
    }
    false
}

#[cfg(windows)]
pub fn open_release_page() {
    use windows::core::{w, PCWSTR};
    use windows::Win32::Foundation::HWND;
    use windows::Win32::UI::Shell::ShellExecuteW;
    use windows::Win32::UI::WindowsAndMessaging::SW_SHOWNORMAL;

    let page: Vec<u16> = EMBER_RELEASE_PAGE
        .encode_utf16()
        .chain(std::iter::once(0))
        .collect();
    // SAFETY: the verb and URL are NUL-terminated UTF-16 constants, and ShellExecuteW opens the user's default browser.
    unsafe {
        let _ = ShellExecuteW(
            HWND::default(),
            w!("open"),
            PCWSTR(page.as_ptr()),
            PCWSTR::null(),
            PCWSTR::null(),
            SW_SHOWNORMAL,
        );
    }
}

#[cfg(not(windows))]
pub fn open_release_page() {}
