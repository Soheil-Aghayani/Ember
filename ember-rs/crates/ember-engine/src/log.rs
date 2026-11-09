//! The app log: a ring of recent entries for the Logs page and an append-only `ember.log` in the data folder, in the
//! same line format as the Kotlin app. A subscription link is a secret: log hosts, never links.

use std::collections::VecDeque;
use std::fs::OpenOptions;
use std::io::Write;
use std::path::PathBuf;
use std::sync::atomic::{AtomicI32, Ordering};
use std::sync::Mutex;

const KEEP: usize = 2000;
const FILE_LIMIT: u64 = 2_000_000;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Level {
    Debug,
    Info,
    Warn,
    Error,
}

impl Level {
    fn letter(self) -> &'static str {
        match self {
            Level::Debug => "D",
            Level::Info => "I",
            Level::Warn => "W",
            Level::Error => "E",
        }
    }
    pub fn name(self) -> &'static str {
        match self {
            Level::Debug => "DEBUG",
            Level::Info => "INFO",
            Level::Warn => "WARN",
            Level::Error => "ERROR",
        }
    }
}

#[derive(Debug, Clone)]
pub struct Entry {
    pub timestamp_ms: i64,
    pub level: Level,
    pub tag: String,
    pub message: String,
}

struct Logger {
    entries: VecDeque<Entry>,
    file: Option<PathBuf>,
}

/// Minutes the log's clock is ahead of UTC; the engine sets it from the computer's time zone when it starts.
static UTC_OFFSET_MINUTES: AtomicI32 = AtomicI32::new(0);

pub fn set_utc_offset_minutes(minutes: i32) {
    UTC_OFFSET_MINUTES.store(minutes, Ordering::Relaxed);
}

static LOGGER: Mutex<Logger> = Mutex::new(Logger { entries: VecDeque::new(), file: None });

fn lock() -> std::sync::MutexGuard<'static, Logger> {
    LOGGER.lock().unwrap_or_else(|e| e.into_inner())
}

/// Starts writing to `file` (a fresh file when the old one is large). Entries logged earlier stay in memory.
pub fn init(file: PathBuf) {
    if std::fs::metadata(&file).map(|m| m.len() > FILE_LIMIT).unwrap_or(false) {
        let _ = std::fs::remove_file(&file);
    }
    lock().file = Some(file);
}

/// `2026-11-08T23:41:05.123` on the computer's clock (see [`set_utc_offset_minutes`]).
pub fn format_time(ms: i64) -> String {
    format_clock(ms + i64::from(UTC_OFFSET_MINUTES.load(Ordering::Relaxed)) * 60_000)
}

/// The same for a time already shifted to the wanted zone.
fn format_clock(ms: i64) -> String {
    let secs = ms.div_euclid(1000);
    let days = secs.div_euclid(86_400);
    let rem = secs.rem_euclid(86_400);
    // civil-from-days (Howard Hinnant)
    let z = days + 719_468;
    let era = z.div_euclid(146_097);
    let doe = z.rem_euclid(146_097);
    let yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365;
    let y = yoe + era * 400;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let d = doy - (153 * mp + 2) / 5 + 1;
    let m = if mp < 10 { mp + 3 } else { mp - 9 };
    let y = if m <= 2 { y + 1 } else { y };
    format!("{y:04}-{m:02}-{d:02}T{:02}:{:02}:{:02}.{:03}", rem / 3600, rem % 3600 / 60, rem % 60, ms.rem_euclid(1000))
}

fn write(level: Level, tag: &str, message: &str) {
    let entry = Entry { timestamp_ms: crate::now_ms(), level, tag: tag.to_string(), message: message.to_string() };
    let line = format!("{} {}/{}: {}\n", format_time(entry.timestamp_ms), level.letter(), tag, message);
    let mut logger = lock();
    if logger.entries.len() >= KEEP {
        logger.entries.pop_front();
    }
    logger.entries.push_back(entry);
    if let Some(path) = &logger.file {
        if let Ok(mut f) = OpenOptions::new().create(true).append(true).open(path) {
            let _ = f.write_all(line.as_bytes());
        }
    }
}

pub fn debug(tag: &str, message: &str) {
    write(Level::Debug, tag, message);
}
pub fn info(tag: &str, message: &str) {
    write(Level::Info, tag, message);
}
pub fn warn(tag: &str, message: &str) {
    write(Level::Warn, tag, message);
}
pub fn error(tag: &str, message: &str) {
    write(Level::Error, tag, message);
}

/// The recent entries, oldest first.
pub fn entries() -> Vec<Entry> {
    lock().entries.iter().cloned().collect()
}

pub fn clear() {
    lock().entries.clear();
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn formats_a_clock_without_an_offset() {
        assert_eq!(format_clock(0), "1970-01-01T00:00:00.000");
        assert_eq!(format_clock(1_700_000_000_123), "2023-11-14T22:13:20.123");
        assert_eq!(format_clock(1_709_164_800_000), "2024-02-29T00:00:00.000"); // a leap day
    }
}
