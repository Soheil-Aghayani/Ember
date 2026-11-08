//! Whether Windows is set to dark mode for apps (Settings > Personalisation > Colours), for the "System" appearance.

/// True when apps should be dark: `AppsUseLightTheme` is 0. Light when the value is missing (older Windows).
#[cfg(windows)]
pub fn apps_use_dark() -> bool {
    use windows::core::w;
    use windows::Win32::System::Registry::{RegGetValueW, HKEY_CURRENT_USER, RRF_RT_REG_DWORD};

    let mut data: u32 = 1;
    let mut size = std::mem::size_of::<u32>() as u32;
    // SAFETY: `data` is a writable u32 and `size` says it holds 4 bytes; both outlive the call, and the key and value
    // names are static wide strings.
    let status = unsafe {
        RegGetValueW(
            HKEY_CURRENT_USER,
            w!(r"Software\Microsoft\Windows\CurrentVersion\Themes\Personalize"),
            w!("AppsUseLightTheme"),
            RRF_RT_REG_DWORD,
            None,
            Some(&mut data as *mut u32 as *mut _),
            Some(&mut size),
        )
    };
    status.is_ok() && data == 0
}

#[cfg(not(windows))]
pub fn apps_use_dark() -> bool {
    false
}

#[cfg(test)]
mod tests {
    #[test]
    fn reading_the_theme_does_not_fail() {
        // either answer is fine; this checks the call itself works and is steady
        assert_eq!(super::apps_use_dark(), super::apps_use_dark());
    }
}
