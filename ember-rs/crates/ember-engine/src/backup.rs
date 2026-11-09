//! Backup and restore (port of `desktop/.../engine/Backup.kt`). A backup is a zip of the two files that hold what the
//! user set up: the settings and the subscriptions. The subscriptions carry each account's key, so the file is as private
//! as a password. The zip layout is the Kotlin app's, so either app reads the other's backups.

use std::collections::BTreeMap;
use std::io::{Read, Write};
use std::path::Path;

use crate::settings::{latin1, parse_properties, store_properties};

const SETTINGS: &str = "settings.properties";
const SUBSCRIPTIONS: &str = "subscriptions.json";
const MAX_FILE_BYTES: u64 = 5_000_000;

/// Settings that describe this computer or this moment, not a choice: a backup neither carries nor overwrites them.
fn is_local(key: &str) -> bool {
    matches!(key, "wasConnected" | "adminNoticeSeen" | "trustedNetworks" | "lastCity" | "lastCountry") || key.starts_with("quota")
}

fn read_settings(path: &Path) -> BTreeMap<String, String> {
    std::fs::read(path).map(|b| parse_properties(&latin1(&b))).unwrap_or_default()
}

/// Writes the settings (without the local ones) and the subscriptions in `data_dir` to the zip `target`.
pub fn write(data_dir: &Path, target: &Path) -> Result<(), String> {
    let mut settings = read_settings(&data_dir.join(SETTINGS));
    settings.retain(|k, _| !is_local(k));
    let file = std::fs::File::create(target).map_err(|e| e.to_string())?;
    let mut zip = zip::ZipWriter::new(std::io::BufWriter::new(file));
    let options = zip::write::SimpleFileOptions::default().compression_method(zip::CompressionMethod::Deflated);
    let mut put = |name: &str, bytes: &[u8]| -> Result<(), String> {
        zip.start_file(name, options).map_err(|e| e.to_string())?;
        zip.write_all(bytes).map_err(|e| e.to_string())
    };
    put(SETTINGS, store_properties(&settings).as_bytes())?;
    if let Ok(bytes) = std::fs::read(data_dir.join(SUBSCRIPTIONS)) {
        put(SUBSCRIPTIONS, &bytes)?;
    }
    zip.finish().map_err(|e| e.to_string())?;
    Ok(())
}

/// Replaces the settings and subscriptions in `data_dir` with the ones in `source`. Nothing is touched unless the whole
/// file checks out. The caller must have stopped the app first, or it would write its own copies over these.
pub fn restore(data_dir: &Path, source: &Path) -> Result<(), String> {
    let file = std::fs::File::open(source).map_err(|e| e.to_string())?;
    let mut archive = zip::ZipArchive::new(std::io::BufReader::new(file)).map_err(|_| "that is not a backup file".to_string())?;
    let mut found: BTreeMap<String, Vec<u8>> = BTreeMap::new();
    for i in 0..archive.len() {
        let entry = archive.by_index(i).map_err(|e| e.to_string())?;
        let name = entry.name().to_string();
        if name != SETTINGS && name != SUBSCRIPTIONS {
            return Err("unexpected file in the backup".into());
        }
        let mut bytes = Vec::new();
        entry.take(MAX_FILE_BYTES + 1).read_to_end(&mut bytes).map_err(|e| e.to_string())?;
        if bytes.len() as u64 > MAX_FILE_BYTES {
            return Err("the backup is too large".into());
        }
        found.insert(name, bytes);
    }
    if found.is_empty() {
        return Err("the backup is empty".into());
    }
    if let Some(bytes) = found.get(SUBSCRIPTIONS) {
        // not a subscriptions file when this fails
        if !serde_json::from_slice::<serde_json::Value>(bytes).is_ok_and(|v| v.is_object()) {
            return Err("the subscriptions in the backup are damaged".into());
        }
    }
    if let Some(bytes) = found.get(SETTINGS) {
        let mut restored = parse_properties(&latin1(bytes));
        restored.retain(|k, _| !is_local(k));
        // this computer's own values stay
        for (k, v) in read_settings(&data_dir.join(SETTINGS)) {
            if is_local(&k) {
                restored.insert(k, v);
            }
        }
        std::fs::write(data_dir.join(SETTINGS), store_properties(&restored)).map_err(|e| e.to_string())?;
    }
    if let Some(bytes) = found.get(SUBSCRIPTIONS) {
        std::fs::write(data_dir.join(SUBSCRIPTIONS), bytes).map_err(|e| e.to_string())?;
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn dir(name: &str) -> std::path::PathBuf {
        let d = std::env::temp_dir().join(format!("ember-backup-{name}-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&d);
        std::fs::create_dir_all(&d).unwrap();
        d
    }

    fn put(dir: &Path, settings: &[(&str, &str)], subs: &str) {
        let map: BTreeMap<String, String> = settings.iter().map(|(k, v)| (k.to_string(), v.to_string())).collect();
        std::fs::write(dir.join(SETTINGS), store_properties(&map)).unwrap();
        std::fs::write(dir.join(SUBSCRIPTIONS), subs).unwrap();
    }

    #[test]
    fn round_trip_keeps_choices_and_skips_this_computers_values() {
        let (a, b, zip) = (dir("a"), dir("b"), dir("z").join("backup.zip"));
        put(&a, &[("theme", "Dark"), ("wasConnected", "true"), ("quotaX", "1")], r#"{"subscriptions":[]}"#);
        write(&a, &zip).unwrap();
        put(&b, &[("theme", "Light"), ("trustedNetworks", "wifi:home")], r#"{"subscriptions":[1]}"#);
        restore(&b, &zip).unwrap();
        let got = read_settings(&b.join(SETTINGS));
        assert_eq!(got.get("theme").map(String::as_str), Some("Dark"));
        assert_eq!(got.get("trustedNetworks").map(String::as_str), Some("wifi:home"), "this computer's value stays");
        assert!(!got.contains_key("wasConnected") && !got.contains_key("quotaX"));
        assert_eq!(std::fs::read_to_string(b.join(SUBSCRIPTIONS)).unwrap(), r#"{"subscriptions":[]}"#);
    }

    #[test]
    fn a_bad_file_changes_nothing() {
        let (a, bad) = (dir("c"), dir("d").join("bad.zip"));
        put(&a, &[("theme", "Dark")], "{}");
        std::fs::write(&bad, b"not a zip").unwrap();
        assert!(restore(&a, &bad).is_err());
        assert_eq!(read_settings(&a.join(SETTINGS)).get("theme").map(String::as_str), Some("Dark"));
        // a zip with a stranger inside
        let odd = dir("e").join("odd.zip");
        let mut z = zip::ZipWriter::new(std::fs::File::create(&odd).unwrap());
        z.start_file("evil.txt", zip::write::SimpleFileOptions::default()).unwrap();
        z.write_all(b"x").unwrap();
        z.finish().unwrap();
        assert_eq!(restore(&a, &odd).unwrap_err(), "unexpected file in the backup");
        // damaged subscriptions
        let broken = dir("f").join("broken.zip");
        let mut z = zip::ZipWriter::new(std::fs::File::create(&broken).unwrap());
        z.start_file(SUBSCRIPTIONS, zip::write::SimpleFileOptions::default()).unwrap();
        z.write_all(b"[1,2").unwrap();
        z.finish().unwrap();
        assert!(restore(&a, &broken).is_err());
        assert_eq!(read_settings(&a.join(SETTINGS)).get("theme").map(String::as_str), Some("Dark"));
    }
}
