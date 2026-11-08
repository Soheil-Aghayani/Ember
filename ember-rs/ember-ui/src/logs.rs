//! Feeds the Logs page from the engine's in-memory log, and does what its buttons ask for.

use ember_engine::log::{self, Entry, Level};
use slint::{ModelRc, SharedString, VecModel};

use crate::App;

/// Lines drawn at most; the log can hold thousands and every line is a text item.
const SHOWN: usize = 300;

/// The log filter: 0 all, 1 info, 2 problems.
fn keep(filter: i32, entry: &Entry) -> bool {
    match filter {
        1 => entry.level == Level::Info,
        2 => matches!(entry.level, Level::Warn | Level::Error),
        _ => true,
    }
}

/// `2026-11-09 01:22:03.456 INFO [Tag] message`, as in the Kotlin app (the time is UTC here).
pub fn line(entry: &Entry) -> String {
    format!("{} {} [{}] {}", log::format_time(entry.timestamp_ms).replace('T', " "), entry.level.name(), entry.tag, entry.message)
}

/// The entries that pass `filter`, newest first.
pub fn filtered(filter: i32) -> Vec<String> {
    log::entries().iter().rev().filter(|e| keep(filter, e)).map(line).collect()
}

/// What the page is showing, so it is only redrawn when the log or the filter changed.
#[derive(Default)]
pub struct LogsUi {
    last: std::cell::RefCell<(usize, i64, i32)>,
}

pub fn apply(app: &App, ui: &LogsUi, force: bool) {
    let filter = app.get_log_filter();
    let entries = log::entries();
    let key = (entries.len(), entries.last().map(|e| e.timestamp_ms).unwrap_or(0), filter);
    if !force && *ui.last.borrow() == key {
        return;
    }
    *ui.last.borrow_mut() = key;
    let shown: Vec<SharedString> = entries.iter().rev().filter(|e| keep(filter, e)).take(SHOWN).map(|e| line(e).into()).collect();
    app.set_log_lines(ModelRc::new(VecModel::from(shown)));
    app.set_log_has_entries(!entries.is_empty());
}

#[cfg(test)]
mod tests {
    use super::*;

    fn entry(level: Level, message: &str) -> Entry {
        Entry { timestamp_ms: 1_700_000_000_123, level, tag: "Xray".to_string(), message: message.to_string() }
    }

    #[test]
    fn formats_a_line_like_the_kotlin_app() {
        assert_eq!(line(&entry(Level::Warn, "could not look up sv0.example.ir")), "2023-11-14 22:13:20.123 WARN [Xray] could not look up sv0.example.ir");
    }

    #[test]
    fn filters_pick_info_or_problems() {
        let (info, warn, error, debug) =
            (entry(Level::Info, "a"), entry(Level::Warn, "b"), entry(Level::Error, "c"), entry(Level::Debug, "d"));
        assert!([&info, &warn, &error, &debug].iter().all(|e| keep(0, e)));
        assert_eq!([&info, &warn, &error, &debug].iter().map(|e| keep(1, e)).collect::<Vec<_>>(), [true, false, false, false]);
        assert_eq!([&info, &warn, &error, &debug].iter().map(|e| keep(2, e)).collect::<Vec<_>>(), [false, true, true, false]);
    }
}
