//! The language in use. Every page reads its text through [`current`], so changing the language in Settings changes all
//! of them at once.

use std::sync::atomic::{AtomicBool, Ordering};

use crate::strings::{self, Strings};

static PERSIAN: AtomicBool = AtomicBool::new(false);

pub fn set(code: &str) {
    PERSIAN.store(code == "fa", Ordering::SeqCst);
}

pub fn code() -> &'static str {
    if PERSIAN.load(Ordering::SeqCst) { "fa" } else { "en" }
}

pub fn current() -> &'static Strings {
    strings::for_language(code())
}

/// Shown on settings whose feature is not in the Rust build yet (not part of the Kotlin string table).
pub fn not_yet() -> &'static str {
    if PERSIAN.load(Ordering::SeqCst) { "\u{647}\u{646}\u{648}\u{632} \u{62f}\u{631} \u{627}\u{6cc}\u{646} \u{646}\u{633}\u{62e}\u{647} \u{646}\u{6cc}\u{633}\u{62a}" } else { "Not in this build yet" }
}
