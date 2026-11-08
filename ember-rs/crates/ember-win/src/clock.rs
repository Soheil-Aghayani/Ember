//! The computer's time zone, so the log shows the time on the user's clock (the Kotlin app writes local time).

/// Minutes the local time is ahead of UTC (negative west of it), daylight saving included.
#[cfg(windows)]
pub fn utc_offset_minutes() -> i32 {
    use windows::Win32::System::Time::{GetTimeZoneInformation, TIME_ZONE_INFORMATION};
    let mut info = TIME_ZONE_INFORMATION::default();
    // SAFETY: `info` is a valid, writable TIME_ZONE_INFORMATION that lives for the whole call.
    let kind = unsafe { GetTimeZoneInformation(&mut info) };
    // 2 means daylight saving time is in effect; 0 (unknown) and 1 use the standard bias
    let bias = if kind == 2 { info.Bias + info.DaylightBias } else { info.Bias + info.StandardBias };
    -bias
}

#[cfg(not(windows))]
pub fn utc_offset_minutes() -> i32 {
    0
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_offset_is_a_real_time_zone() {
        let minutes = utc_offset_minutes();
        assert!((-12 * 60..=14 * 60).contains(&minutes), "{minutes}");
        // zones are whole quarter hours
        assert_eq!(minutes % 15, 0, "{minutes}");
    }
}
