//! The rows of the About and What's new pages.

use std::path::Path;

use slint::{ModelRc, SharedString, VecModel};

use crate::releases::RELEASES;
use crate::strings::Strings;
use crate::{InfoItem, ReleaseRow};

// Keep in step with the pinned downloads in desktop/build.gradle.kts (the Rust build uses the same programs).
const XRAY_VERSION: &str = "26.10.31";
const TUNNEL_VERSION: &str = "hev-socks5-tunnel 2.19.1 \u{b7} Wintun 0.14.1";

pub const CONTACT_LINK: &str = "https://t.me/env_soh";

const LICENSES: [(&str, &str); 8] = [
    ("Xray-core", "MPL-2.0"),
    ("hev-socks5-tunnel", "MIT"),
    ("Wintun", "WireGuard LLC prebuilt binaries licence"),
    ("Inter (font)", "SIL Open Font License 1.1"),
    ("Solar icons (480 Design)", "CC BY 4.0"),
    ("circle-flags", "MIT"),
    ("Slint (user interface)", "GPL-3.0 or the Slint royalty-free licence"),
    ("Rust libraries (winit, ureq, regex, ...)", "MIT / Apache-2.0"),
];

fn item(label: &str, value: String) -> InfoItem {
    InfoItem { label: label.into(), value: value.into(), breakable: false }
}

fn model<T: Clone + 'static>(rows: Vec<T>) -> ModelRc<T> {
    ModelRc::new(VecModel::from(rows))
}

pub fn info(data_dir: &Path, s: &Strings) -> ModelRc<InfoItem> {
    let device = [Some(ember_engine::diagnostics::windows_version()), std::env::var("COMPUTERNAME").ok()]
        .into_iter()
        .flatten()
        .collect::<Vec<_>>()
        .join(" \u{b7} ");
    model(vec![
        item(s.about_program_version, format!("{} \u{b7} {}", env!("CARGO_PKG_VERSION"), s.development_build)),
        item(s.about_xray, XRAY_VERSION.to_string()),
        item(s.about_tunnel, TUNNEL_VERSION.to_string()),
        item(s.about_device, device),
        InfoItem { breakable: true, ..item(s.about_log, data_dir.join("ember.log").display().to_string()) },
    ])
}

pub fn licenses() -> ModelRc<InfoItem> {
    model(LICENSES.iter().map(|(name, licence)| item(name, licence.to_string())).collect())
}

/// The release history in the chosen language. `running` marks the entry for the version that is running, if listed.
pub fn releases(language: &str, running: &str) -> ModelRc<ReleaseRow> {
    model(
        RELEASES
            .iter()
            .map(|r| ReleaseRow {
                version: r.version.into(),
                current: r.version == running,
                // a right-to-left mark first, so a Persian line that starts with a Latin word (Xray, OpenGL) is laid out right to left
                lines: model(
                    if language == "fa" { r.fa } else { r.en }
                        .iter()
                        .map(|l| SharedString::from(if language == "fa" { format!("\u{200f}{l}") } else { (*l).to_string() }))
                        .collect(),
                ),
            })
            .collect(),
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn every_release_has_the_same_number_of_lines_in_both_languages() {
        assert_eq!(RELEASES.len(), 32);
        for r in RELEASES {
            assert!(!r.en.is_empty(), "{}", r.version);
            assert_eq!(r.en.len(), r.fa.len(), "{}", r.version);
        }
        assert_eq!(RELEASES[0].version, "1.0.101");
    }

    #[test]
    fn the_running_version_is_marked_only_when_it_is_listed() {
        use slint::Model;
        let rows = releases("en", "1.0.99");
        assert_eq!(rows.iter().filter(|r| r.current).count(), 1);
        assert_eq!(releases("fa", "0.1.0").iter().filter(|r| r.current).count(), 0);
    }
}
