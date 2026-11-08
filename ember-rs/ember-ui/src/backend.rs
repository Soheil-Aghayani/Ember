//! Where the Rust app keeps its files and finds `xray.exe`.
//!
//! While the Rust app is being built it has its own data folder, `%APPDATA%\Ember-Rust`, so the two apps cannot corrupt
//! each other's files. On first run it copies the servers and settings from the Kotlin app's folder (`%APPDATA%\Ember`);
//! the originals are only read. The copied settings have whole-device mode switched off, because this build only does
//! proxy mode.

use std::path::{Path, PathBuf};

pub fn data_dir(args: &[String]) -> PathBuf {
    if let Some(i) = args.iter().position(|a| a == "--data-dir") {
        if let Some(path) = args.get(i + 1) {
            return PathBuf::from(path);
        }
    }
    if let Ok(path) = std::env::var("EMBER_DATA_DIR") {
        return PathBuf::from(path);
    }
    appdata().join("Ember-Rust")
}

fn appdata() -> PathBuf {
    std::env::var_os("APPDATA").map(PathBuf::from).unwrap_or_else(|| PathBuf::from("."))
}

/// First run only: copy `subscriptions.json` and `settings.properties` from the Kotlin app's folder. Returns what it
/// copied, for the log.
pub fn import_from_kotlin_app(data_dir: &Path) -> Vec<&'static str> {
    let from = appdata().join("Ember");
    let mut copied = Vec::new();
    if std::fs::create_dir_all(data_dir).is_err() || from == data_dir {
        return copied;
    }
    for name in ["subscriptions.json", "settings.properties"] {
        let (source, target) = (from.join(name), data_dir.join(name));
        if source.is_file() && !target.exists() && std::fs::copy(&source, &target).is_ok() {
            copied.push(name);
        }
    }
    if copied.contains(&"settings.properties") {
        // proxy mode only, for now: the copy must not ask for the whole-device mode this build cannot do
        let target = data_dir.join("settings.properties");
        let settings = ember_engine::Settings::open(&target, "en");
        settings.set_whole_device(false);
    }
    copied
}

/// `xray.exe`: `EMBER_XRAY`, then the packaged copy next to this program, then the installed Kotlin app's copy.
pub fn find_xray() -> Option<PathBuf> {
    if let Ok(path) = std::env::var("EMBER_XRAY") {
        return Some(PathBuf::from(path));
    }
    let exe_dir = std::env::current_exe().ok().and_then(|p| p.parent().map(Path::to_path_buf));
    let mut roots: Vec<PathBuf> = Vec::new();
    if let Some(dir) = &exe_dir {
        roots.push(dir.join("tunnel"));
        roots.push(dir.join("resources").join("tunnel"));
    }
    roots.push(PathBuf::from(r"C:\Program Files\Ember\app\resources\tunnel"));
    roots.into_iter().find_map(|root| find_file(&root, "xray.exe", 3))
}

fn find_file(dir: &Path, name: &str, depth: u32) -> Option<PathBuf> {
    for entry in std::fs::read_dir(dir).ok()?.flatten() {
        let path = entry.path();
        if path.is_file() && path.file_name().is_some_and(|n| n.eq_ignore_ascii_case(name)) {
            return Some(path);
        }
        if depth > 0 && path.is_dir() {
            if let Some(found) = find_file(&path, name, depth - 1) {
                return Some(found);
            }
        }
    }
    None
}
