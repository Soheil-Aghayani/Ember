//! Only one Ember at a time: a named mutex held for the life of the process.

/// Holds the mutex; dropping it lets another Ember start.
pub struct Guard {
    #[cfg(windows)]
    handle: windows::Win32::Foundation::HANDLE,
}

/// Some(guard) for the first Ember, None when another one is already running.
#[cfg(windows)]
pub fn acquire() -> Option<Guard> {
    use windows::core::w;
    use windows::Win32::Foundation::{GetLastError, ERROR_ALREADY_EXISTS};
    use windows::Win32::System::Threading::CreateMutexW;

    // SAFETY: plain Win32 call with a static NUL-terminated name; the handle is owned by the returned Guard.
    let handle = unsafe { CreateMutexW(None, false, w!("Local\\EmberDesktopSingleInstance")) }.ok()?;
    // SAFETY: reads the calling thread's last error, set by CreateMutexW just above.
    if unsafe { GetLastError() } == ERROR_ALREADY_EXISTS {
        // SAFETY: `handle` is valid and not used again.
        let _ = unsafe { windows::Win32::Foundation::CloseHandle(handle) };
        return None;
    }
    Some(Guard { handle })
}

#[cfg(not(windows))]
pub fn acquire() -> Option<Guard> {
    Some(Guard {})
}

#[cfg(windows)]
impl Drop for Guard {
    fn drop(&mut self) {
        // SAFETY: the handle came from CreateMutexW and is closed exactly once, here.
        let _ = unsafe { windows::Win32::Foundation::CloseHandle(self.handle) };
    }
}

/// Starts a fresh copy of this program with [args] (used after a restore or a language change). The caller exits next.
pub fn relaunch(args: &[&str]) -> std::io::Result<()> {
    let exe = std::env::current_exe()?;
    std::process::Command::new(exe).args(args).spawn().map(|_| ())
}
