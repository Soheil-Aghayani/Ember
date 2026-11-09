//! Run Ember when the user signs in: `HKCU\...\Run\Ember`, written with `reg.exe` exactly like the Kotlin app.

use std::time::Duration;

use crate::proc;

const KEY: &str = r"HKCU\Software\Microsoft\Windows\CurrentVersion\Run";
const VALUE: &str = "Ember";

pub fn supported() -> bool {
    cfg!(windows)
}

pub fn is_enabled() -> bool {
    supported() && reg(&query_args()) == 0
}

/// Adds or removes the Run value. True when the registry now matches [enabled].
pub fn set_enabled(enabled: bool) -> bool {
    if !supported() {
        return false;
    }
    if !enabled && !is_enabled() {
        return true;
    }
    let args = if enabled {
        let Ok(exe) = std::env::current_exe() else { return false };
        add_args(&exe.to_string_lossy())
    } else {
        delete_args()
    };
    reg(&args) == 0
}

/// The command stored in the Run value: the quoted program, started hidden in the tray.
pub fn run_value(executable: &str) -> String {
    format!("\"{executable}\" --minimized")
}

fn query_args() -> Vec<String> {
    ["query", KEY, "/v", VALUE].map(String::from).to_vec()
}

fn add_args(executable: &str) -> Vec<String> {
    ["add", KEY, "/v", VALUE, "/t", "REG_SZ", "/d", &run_value(executable), "/f"].map(String::from).to_vec()
}

fn delete_args() -> Vec<String> {
    ["delete", KEY, "/v", VALUE, "/f"].map(String::from).to_vec()
}

fn reg(args: &[String]) -> i32 {
    let refs: Vec<&str> = args.iter().map(String::as_str).collect();
    proc::run("reg.exe", &refs, Duration::from_secs(5)).0
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn run_value_quotes_the_path_and_starts_minimized() {
        assert_eq!(run_value(r"C:\Program Files\Ember\Ember.exe"), "\"C:\\Program Files\\Ember\\Ember.exe\" --minimized");
    }

    #[test]
    fn reg_arguments_match_the_kotlin_app() {
        assert_eq!(query_args(), ["query", KEY, "/v", "Ember"]);
        assert_eq!(delete_args(), ["delete", KEY, "/v", "Ember", "/f"]);
        let add = add_args(r"C:\E.exe");
        assert_eq!(add[..8], ["add", KEY, "/v", "Ember", "/t", "REG_SZ", "/d", "\"C:\\E.exe\" --minimized"]);
        assert_eq!(add[8], "/f");
    }
}

#[cfg(all(test, windows))]
mod registry_roundtrip {
    /// Touches the real Run key of the current user, so it only runs on request: `cargo test -p ember-win -- --ignored autostart`.
    /// Skips (passes) when the user already has an Ember entry, so it never replaces one.
    #[test]
    #[ignore]
    fn autostart_roundtrip() {
        if super::is_enabled() {
            return;
        }
        assert!(super::set_enabled(true));
        assert!(super::is_enabled());
        assert!(super::set_enabled(false));
        assert!(!super::is_enabled());
    }
}
