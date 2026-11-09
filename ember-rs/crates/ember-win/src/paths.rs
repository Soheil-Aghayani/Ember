//! Where Ember keeps its files (same folders as the Kotlin app, so either app reads the other's data).

use std::path::{Path, PathBuf};

/// `%APPDATA%\Ember`, created when missing. Falls back to the user profile when APPDATA is unset.
pub fn data_dir() -> PathBuf {
    let dir = data_dir_in(std::env::var_os("APPDATA"), std::env::var_os("USERPROFILE"));
    let _ = std::fs::create_dir_all(&dir);
    dir
}

fn data_dir_in(appdata: Option<std::ffi::OsString>, profile: Option<std::ffi::OsString>) -> PathBuf {
    let base = appdata.or(profile).map(PathBuf::from).unwrap_or_else(|| PathBuf::from("."));
    base.join("Ember")
}

/// The folder that holds `ember-helper.exe`, `ember-tunnel.ps1` and `tunnel\`. The Kotlin app reads it from
/// `compose.application.resources.dir`; here `EMBER_RESOURCES_DIR` overrides it, and otherwise it is the `resources`
/// folder next to the exe (an install keeps every helper there, so the program folder holds one exe), or the exe's own
/// folder when there is none (a build tree).
pub fn resources_dir() -> Option<PathBuf> {
    std::env::var_os("EMBER_RESOURCES_DIR").map(PathBuf::from).or_else(|| {
        let exe_dir = std::env::current_exe().ok().and_then(|p| p.parent().map(PathBuf::from))?;
        let inside = exe_dir.join("resources");
        Some(if inside.is_dir() { inside } else { exe_dir })
    })
}

/// First file called [name] anywhere under [dir] (like Kotlin's `walkTopDown().firstOrNull`).
pub fn find_file(dir: &Path, name: &str) -> Option<PathBuf> {
    let mut stack = vec![dir.to_path_buf()];
    while let Some(d) = stack.pop() {
        let Ok(entries) = std::fs::read_dir(&d) else { continue };
        for entry in entries.flatten() {
            let path = entry.path();
            if path.is_dir() {
                stack.push(path);
            } else if path.file_name().is_some_and(|n| n.eq_ignore_ascii_case(name)) {
                return Some(path);
            }
        }
    }
    None
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn appdata_is_preferred() {
        assert_eq!(data_dir_in(Some("C:\\A".into()), Some("C:\\U".into())), PathBuf::from("C:\\A").join("Ember"));
        assert_eq!(data_dir_in(None, Some("C:\\U".into())), PathBuf::from("C:\\U").join("Ember"));
    }

    #[test]
    fn find_file_walks_subfolders() {
        let root = std::env::temp_dir().join(format!("ember-win-find-{}", std::process::id()));
        let sub = root.join("a").join("b");
        std::fs::create_dir_all(&sub).unwrap();
        std::fs::write(sub.join("Sing-Box.exe"), b"").unwrap();
        assert!(find_file(&root, "sing-box.exe").is_some());
        assert!(find_file(&root, "nope.exe").is_none());
        let _ = std::fs::remove_dir_all(&root);
    }
}
