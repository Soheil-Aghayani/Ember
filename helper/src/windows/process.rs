use ember_helper::Result;
use std::{
    os::windows::process::CommandExt,
    path::Path,
    process::{Child, Command, Stdio},
};
use windows::Win32::{
    Foundation::{CloseHandle, ERROR_ACCESS_DENIED, ERROR_NO_MORE_FILES, HANDLE, WAIT_TIMEOUT},
    Security::{GetTokenInformation, TokenElevation, TOKEN_ELEVATION, TOKEN_QUERY},
    System::{Diagnostics::ToolHelp::*, SystemInformation::GetLocalTime, Threading::*},
};

struct Handle(HANDLE);
impl Drop for Handle {
    fn drop(&mut self) {
        // SAFETY: Handle owns a non-null valid process/token/snapshot handle, closed once.
        let _ = unsafe { CloseHandle(self.0) };
    }
}

pub fn elevated() -> Result<bool> {
    let mut token = HANDLE::default();
    // SAFETY: current-process pseudo handle and a valid token output pointer.
    unsafe { OpenProcessToken(GetCurrentProcess(), TOKEN_QUERY, &mut token) }
        .map_err(|e| e.to_string())?;
    let token = Handle(token);
    let mut elevation = TOKEN_ELEVATION::default();
    let mut returned = 0;
    // SAFETY: buffer exactly matches TOKEN_ELEVATION with its size supplied to the API.
    unsafe {
        GetTokenInformation(
            token.0,
            TokenElevation,
            Some((&mut elevation as *mut TOKEN_ELEVATION).cast()),
            std::mem::size_of::<TOKEN_ELEVATION>() as u32,
            &mut returned,
        )
    }
    .map_err(|e| e.to_string())?;
    Ok(elevation.TokenIsElevated != 0)
}

/// Starts the tunnel process. Its output goes to `log` (it says why it stops when it fails to start).
pub fn spawn(hev: &Path, config: &Path, log: &Path) -> Result<Child> {
    let output = std::fs::File::create(log).map_err(|e| e.to_string())?;
    let errors = output.try_clone().map_err(|e| e.to_string())?;
    Command::new(hev)
        .arg(plain_path(config))
        .stdout(Stdio::from(output))
        .stderr(Stdio::from(errors))
        .current_dir(
            hev.parent()
                .ok_or("tunnel executable has no parent folder")?,
        )
        .creation_flags(CREATE_NO_WINDOW.0)
        .spawn()
        .map_err(|e| e.to_string())
}

/// The path as a plain Windows path. `canonicalize` returns the `\\?\` form, which the tunnel program (an MSYS build)
/// cannot open: it logged "Open ... failed!" and exited at once.
pub fn plain_path(path: &Path) -> String {
    let text = path.to_string_lossy();
    match text.strip_prefix(r"\\?\") {
        Some(rest) if !rest.starts_with(r"UNC\") => rest.to_string(),
        _ => text.into_owned(),
    }
}

pub fn alive(pid: i32) -> Result<bool> {
    if pid <= 0 {
        return Ok(false);
    }
    // SAFETY: requests only synchronization rights on a numeric process identifier.
    let handle = match unsafe { OpenProcess(PROCESS_SYNCHRONIZE, false, pid as u32) } {
        Ok(handle) => Handle(handle),
        Err(e) if e.code() == ERROR_ACCESS_DENIED.to_hresult() => return Ok(true),
        Err(e) if e.code() == windows::Win32::Foundation::ERROR_INVALID_PARAMETER.to_hresult() => {
            return Ok(false)
        }
        Err(e) => return Err(e.to_string()),
    };
    // SAFETY: valid process handle; zero timeout never blocks.
    Ok(unsafe { WaitForSingleObject(handle.0, 0) } == WAIT_TIMEOUT)
}

fn tunnel_pids() -> Result<Vec<u32>> {
    // SAFETY: the snapshot API takes no pointers for this call.
    let snapshot = Handle(
        unsafe { CreateToolhelp32Snapshot(TH32CS_SNAPPROCESS, 0) }.map_err(|e| e.to_string())?,
    );
    let mut entry = PROCESSENTRY32W {
        dwSize: std::mem::size_of::<PROCESSENTRY32W>() as u32,
        ..Default::default()
    };
    let mut result = Vec::new();
    // SAFETY: writable structure has the documented dwSize; snapshot remains owned throughout enumeration.
    let mut next = unsafe { Process32FirstW(snapshot.0, &mut entry) };
    loop {
        match next {
            Ok(()) => {}
            Err(e) if e.code() == ERROR_NO_MORE_FILES.to_hresult() => break,
            Err(e) => return Err(e.to_string()),
        }
        let end = entry
            .szExeFile
            .iter()
            .position(|c| *c == 0)
            .unwrap_or(entry.szExeFile.len());
        if String::from_utf16_lossy(&entry.szExeFile[..end])
            .eq_ignore_ascii_case("hev-socks5-tunnel.exe")
        {
            result.push(entry.th32ProcessID);
        }
        // SAFETY: same initialized entry and live process snapshot.
        next = unsafe { Process32NextW(snapshot.0, &mut entry) };
    }
    Ok(result)
}

pub fn kill_tunnels(mut log: impl FnMut(&str)) {
    let pids = match tunnel_pids() {
        Ok(pids) => pids,
        Err(e) => {
            log(&format!("cleanup (processes): {e}"));
            return;
        }
    };
    for pid in pids {
        let kill = || -> Result<()> {
            // SAFETY: opens a numeric PID with only the rights needed to terminate/wait.
            let handle = Handle(
                unsafe { OpenProcess(PROCESS_TERMINATE | PROCESS_SYNCHRONIZE, false, pid) }
                    .map_err(|e| e.to_string())?,
            );
            // SAFETY: handle is an owned process handle with terminate rights.
            unsafe { TerminateProcess(handle.0, 1) }.map_err(|e| e.to_string())?;
            // SAFETY: waiting for termination avoids an old adapter racing the next startup.
            unsafe {
                WaitForSingleObject(handle.0, 2000);
            }
            Ok(())
        };
        if let Err(e) = kill() {
            log(&format!("cleanup (process {pid}): {e}"));
        }
    }
}

pub fn timestamp() -> String {
    // SAFETY: GetLocalTime has no caller-provided memory or other preconditions.
    let t = unsafe { GetLocalTime() };
    format!(
        "{:04}-{:02}-{:02}T{:02}:{:02}:{:02}",
        t.wYear, t.wMonth, t.wDay, t.wHour, t.wMinute, t.wSecond
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn strips_the_verbatim_prefix_but_keeps_other_paths() {
        assert_eq!(plain_path(Path::new(r"\\?\C:\Users\a\hev.yaml")), r"C:\Users\a\hev.yaml");
        assert_eq!(plain_path(Path::new(r"C:\Users\a\hev.yaml")), r"C:\Users\a\hev.yaml");
        assert_eq!(plain_path(Path::new(r"\\?\UNC\server\share\x")), r"\\?\UNC\server\share\x");
    }
}
