//! Backup and restore (port of `desktop/.../engine/Backup.kt`). A backup is a zip of the two files that hold what the
//! user set up: the settings and the subscriptions. The subscriptions carry each account's key, so the file is as private
//! as a password. The zip layout is the Kotlin app's, so either app reads the other's backups.

use std::collections::BTreeMap;
use std::io::{Cursor, Read, Write};
use std::path::Path;

use aes_gcm::{aead::{Aead, Payload}, Aes256Gcm, KeyInit, Nonce};
use pbkdf2::pbkdf2_hmac;
use rand::{rngs::OsRng, RngCore};
use sha2::Sha256;
use crate::settings::{latin1, parse_properties, store_properties};

const SETTINGS: &str = "settings.properties";
const SUBSCRIPTIONS: &str = "subscriptions.json";
const MAX_FILE_BYTES: u64 = 5_000_000;
const MAGIC: &[u8; 8] = b"EMBERBK1";
const SALT_BYTES: usize = 16;
const NONCE_BYTES: usize = 12;
const KEY_BYTES: usize = 32;
const PBKDF2_ITERATIONS: u32 = 150_000;
const MAX_BACKUP_BYTES: usize = 10_000_000;

/// Settings that describe this computer or this moment, not a choice: a backup neither carries nor overwrites them.
fn is_local(key: &str) -> bool {
    matches!(key, "wasConnected" | "adminNoticeSeen" | "trustedNetworks" | "lastCity" | "lastCountry") || key.starts_with("quota")
}

fn read_settings(path: &Path) -> BTreeMap<String, String> {
    std::fs::read(path).map(|b| parse_properties(&latin1(&b))).unwrap_or_default()
}

/// Writes the settings (without the local ones) and the subscriptions in `data_dir` to the zip `target`.
pub fn write(data_dir: &Path, target: &Path) -> Result<(), String> {
    std::fs::write(target, archive_bytes(data_dir)?).map_err(|e| e.to_string())
}

/// Writes the same portable zip layout in a password-protected cross-platform envelope.
pub fn write_encrypted(data_dir: &Path, target: &Path, password: &str) -> Result<(), String> {
    let archive = archive_bytes(data_dir)?;
    std::fs::write(target, encrypt(&archive, password)?).map_err(|e| e.to_string())
}

fn archive_bytes(data_dir: &Path) -> Result<Vec<u8>, String> {
    let mut settings = read_settings(&data_dir.join(SETTINGS));
    settings.retain(|k, _| !is_local(k));
    let mut zip = zip::ZipWriter::new(Cursor::new(Vec::new()));
    let options = zip::write::SimpleFileOptions::default().compression_method(zip::CompressionMethod::Deflated);
    let mut put = |name: &str, bytes: &[u8]| -> Result<(), String> {
        zip.start_file(name, options).map_err(|e| e.to_string())?;
        zip.write_all(bytes).map_err(|e| e.to_string())
    };
    put(SETTINGS, store_properties(&settings).as_bytes())?;
    let subscriptions = std::fs::read(data_dir.join(SUBSCRIPTIONS)).unwrap_or_else(|_| br#"{"subscriptions":[]}"#.to_vec());
    put(SUBSCRIPTIONS, &subscriptions)?;
    zip.finish().map(|cursor| cursor.into_inner()).map_err(|e| e.to_string())
}

/// Replaces the settings and subscriptions in `data_dir` with the ones in `source`. Nothing is touched unless the whole
/// file checks out. The caller must have stopped the app first, or it would write its own copies over these.
pub fn restore(data_dir: &Path, source: &Path) -> Result<(), String> {
    let bytes = read_source(source)?;
    restore_archive(data_dir, &bytes)
}

/// Restores an encrypted backup; legacy unencrypted zip files remain readable for backward compatibility.
pub fn restore_encrypted(data_dir: &Path, source: &Path, password: &str) -> Result<(), String> {
    let bytes = read_source(source)?;
    let plain = if bytes.starts_with(MAGIC) { decrypt(&bytes, password)? } else { bytes };
    restore_archive(data_dir, &plain)
}

pub fn validate_backup(source: &Path, password: Option<&str>) -> Result<(), String> {
    let bytes = read_source(source)?;
    let plain = if bytes.starts_with(MAGIC) {
        decrypt(&bytes, password.ok_or_else(|| "a password is required for this backup".to_string())?)?
    } else { bytes };
    read_archive(&plain).map(|_| ())
}

pub fn is_encrypted_backup(source: &Path) -> bool {
    let mut file = match std::fs::File::open(source) { Ok(file) => file, Err(_) => return false };
    let mut magic = [0; MAGIC.len()];
    file.read_exact(&mut magic).is_ok() && &magic == MAGIC
}

fn read_source(source: &Path) -> Result<Vec<u8>, String> {
    if std::fs::metadata(source).map_err(|e| e.to_string())?.len() > MAX_BACKUP_BYTES as u64 {
        return Err("the backup is too large".into());
    }
    std::fs::read(source).map_err(|e| e.to_string())
}

fn read_archive(bytes: &[u8]) -> Result<BTreeMap<String, Vec<u8>>, String> {
    let mut archive = zip::ZipArchive::new(Cursor::new(bytes)).map_err(|_| "that is not a backup file".to_string())?;
    let mut found: BTreeMap<String, Vec<u8>> = BTreeMap::new();
    for i in 0..archive.len() {
        let entry = archive.by_index(i).map_err(|e| e.to_string())?;
        let name = entry.name().to_string();
        if name != SETTINGS && name != SUBSCRIPTIONS {
            return Err("unexpected file in the backup".into());
        }
        if found.contains_key(&name) {
            return Err("the backup contains a duplicate file".into());
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
    Ok(found)
}

fn restore_archive(data_dir: &Path, bytes: &[u8]) -> Result<(), String> {
    let found = read_archive(bytes)?;
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

fn derive_key(password: &str, salt: &[u8]) -> [u8; KEY_BYTES] {
    let mut key = [0u8; KEY_BYTES];
    pbkdf2_hmac::<Sha256>(password.as_bytes(), salt, PBKDF2_ITERATIONS, &mut key);
    key
}

fn encrypt(plain: &[u8], password: &str) -> Result<Vec<u8>, String> {
    if !(8..=128).contains(&password.chars().count()) {
        return Err("Use a backup password between 8 and 128 characters.".into());
    }
    if plain.len() > MAX_BACKUP_BYTES { return Err("the backup is too large".into()); }
    let mut salt = [0u8; SALT_BYTES];
    let mut nonce = [0u8; NONCE_BYTES];
    OsRng.fill_bytes(&mut salt);
    OsRng.fill_bytes(&mut nonce);
    let key = derive_key(password, &salt);
    let cipher = Aes256Gcm::new_from_slice(&key).map_err(|_| "could not encrypt the backup".to_string())?;
    let ciphertext = cipher.encrypt(Nonce::from_slice(&nonce), Payload { msg: plain, aad: MAGIC })
        .map_err(|_| "could not encrypt the backup".to_string())?;
    let mut out = Vec::with_capacity(MAGIC.len() + salt.len() + nonce.len() + ciphertext.len());
    out.extend_from_slice(MAGIC);
    out.extend_from_slice(&salt);
    out.extend_from_slice(&nonce);
    out.extend_from_slice(&ciphertext);
    Ok(out)
}

fn decrypt(data: &[u8], password: &str) -> Result<Vec<u8>, String> {
    let header = MAGIC.len() + SALT_BYTES + NONCE_BYTES;
    if data.len() < header + 16 || data.len() > MAX_BACKUP_BYTES { return Err("that is not an Ember backup".into()); }
    if !data.starts_with(MAGIC) { return Err("that is not an encrypted Ember backup".into()); }
    let salt = &data[MAGIC.len()..MAGIC.len() + SALT_BYTES];
    let nonce_start = MAGIC.len() + SALT_BYTES;
    let nonce = &data[nonce_start..nonce_start + NONCE_BYTES];
    let key = derive_key(password, salt);
    let cipher = Aes256Gcm::new_from_slice(&key)
        .map_err(|_| "The password is incorrect or the backup is damaged.".to_string())?;
    cipher.decrypt(Nonce::from_slice(nonce), Payload { msg: &data[header..], aad: MAGIC })
        .map_err(|_| "The password is incorrect or the backup is damaged.".to_string())
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
    fn encrypted_round_trip_hides_contents_and_preserves_local_values() {
        let (a, b, file) = (dir("encrypted-a"), dir("encrypted-b"), dir("encrypted-z").join("backup.emberbackup"));
        put(&a, &[("theme", "Dark"), ("wasConnected", "true")], r#"{"subscriptions":[]}"#);
        write_encrypted(&a, &file, "correct horse battery").unwrap();
        let bytes = std::fs::read(&file).unwrap();
        assert!(bytes.starts_with(MAGIC));
        assert!(!String::from_utf8_lossy(&bytes).contains("Dark"));
        put(&b, &[("theme", "Light"), ("trustedNetworks", "wifi:home")], r#"{"subscriptions":[1]}"#);
        restore_encrypted(&b, &file, "correct horse battery").unwrap();
        let got = read_settings(&b.join(SETTINGS));
        assert_eq!(got.get("theme").map(String::as_str), Some("Dark"));
        assert_eq!(got.get("trustedNetworks").map(String::as_str), Some("wifi:home"));
        assert!(!got.contains_key("wasConnected"));
        assert_eq!(std::fs::read_to_string(b.join(SUBSCRIPTIONS)).unwrap(), r#"{"subscriptions":[]}"#);
    }

    #[test]
    fn a_wrong_password_leaves_the_current_backup_data_untouched() {
        let (a, b, file) = (dir("wrong-a"), dir("wrong-b"), dir("wrong-z").join("backup.emberbackup"));
        put(&a, &[("theme", "Dark")], r#"{"subscriptions":[]}"#);
        put(&b, &[("theme", "Light")], r#"{"subscriptions":[1]}"#);
        write_encrypted(&a, &file, "correct horse battery").unwrap();
        assert!(restore_encrypted(&b, &file, "wrong password").is_err());
        assert_eq!(read_settings(&b.join(SETTINGS)).get("theme").map(String::as_str), Some("Light"));
        assert_eq!(std::fs::read_to_string(b.join(SUBSCRIPTIONS)).unwrap(), r#"{"subscriptions":[1]}"#);
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
