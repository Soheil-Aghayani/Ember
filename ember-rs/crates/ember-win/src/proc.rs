//! Running programs, quoting and the elevated PowerShell hop shared by the runners.

use std::io::Read;
use std::process::{Command, Stdio};
use std::time::{Duration, Instant};

use crate::{Error, Result};

/// A command that never flashes a console window.
pub fn command(program: impl AsRef<std::ffi::OsStr>) -> Command {
    let mut c = Command::new(program);
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        const CREATE_NO_WINDOW: u32 = 0x0800_0000;
        c.creation_flags(CREATE_NO_WINDOW);
    }
    c
}

/// Runs a program, returns (exit code, stdout+stderr as text). `(-1, "")` when it cannot start or times out.
pub fn run(program: &str, args: &[&str], timeout: Duration) -> (i32, String) {
    let Ok(mut child) = command(program).args(args).stdin(Stdio::null()).stdout(Stdio::piped()).stderr(Stdio::piped()).spawn() else {
        return (-1, String::new());
    };
    let mut out = child.stdout.take();
    let mut err = child.stderr.take();
    let reader = std::thread::spawn(move || {
        let mut bytes = Vec::new();
        if let Some(o) = out.as_mut() {
            let _ = o.read_to_end(&mut bytes);
        }
        if let Some(e) = err.as_mut() {
            let _ = e.read_to_end(&mut bytes);
        }
        bytes
    });
    let deadline = Instant::now() + timeout;
    let code = loop {
        match child.try_wait() {
            Ok(Some(status)) => break status.code().unwrap_or(-1),
            Ok(None) if Instant::now() < deadline => std::thread::sleep(Duration::from_millis(20)),
            _ => {
                let _ = child.kill();
                let _ = child.wait();
                break -1;
            }
        }
    };
    let bytes = reader.join().unwrap_or_default();
    (code, decode_output(&bytes))
}

/// Text from a program's output: UTF-16 leftovers (schtasks /xml) are stripped of NULs.
pub fn decode_output(bytes: &[u8]) -> String {
    let cleaned: Vec<u8> = bytes.iter().copied().filter(|b| *b != 0).collect();
    String::from_utf8_lossy(&cleaned).into_owned()
}

/// Runs a program with a 15 s limit, like the Kotlin `run` helpers.
pub fn run15(program: &str, args: &[&str]) -> (i32, String) {
    run(program, args, Duration::from_secs(15))
}

/// `DOMAIN\user` for the scheduled-task principal.
pub fn current_user() -> String {
    let domain = std::env::var("USERDOMAIN").unwrap_or_default();
    let user = std::env::var("USERNAME").unwrap_or_default();
    format!("{domain}\\{user}")
}

/// Fails when a value that ends up inside a PowerShell single-quoted string could break out of it.
pub fn check_quotes(values: &[&str]) -> Result<()> {
    if values.iter().any(|v| v.contains('\'') || v.contains('"')) {
        Err(Error::msg("Unsupported character in a path or user name."))
    } else {
        Ok(())
    }
}

/// Standard Base64 (with padding).
pub fn base64(bytes: &[u8]) -> String {
    const T: &[u8; 64] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    let mut out = String::with_capacity(bytes.len().div_ceil(3) * 4);
    for chunk in bytes.chunks(3) {
        let n = (u32::from(chunk[0]) << 16) | (u32::from(*chunk.get(1).unwrap_or(&0)) << 8) | u32::from(*chunk.get(2).unwrap_or(&0));
        out.push(T[(n >> 18) as usize & 63] as char);
        out.push(T[(n >> 12) as usize & 63] as char);
        out.push(if chunk.len() > 1 { T[(n >> 6) as usize & 63] as char } else { '=' });
        out.push(if chunk.len() > 2 { T[n as usize & 63] as char } else { '=' });
    }
    out
}

/// PowerShell `-EncodedCommand` payload: Base64 of the UTF-16LE text.
pub fn encoded_command(text: &str) -> String {
    let bytes: Vec<u8> = text.encode_utf16().flat_map(u16::to_le_bytes).collect();
    base64(&bytes)
}

/// The two-level script that makes Windows show one administrator prompt for [command_text].
pub fn elevated_outer_script(command_text: &str) -> String {
    let inner = encoded_command(command_text);
    format!(
        "Start-Process -FilePath powershell.exe -Verb RunAs -WindowStyle Hidden -ArgumentList '-NoProfile','-NonInteractive','-EncodedCommand','{inner}'"
    )
}

/// Runs [command_text] in an elevated PowerShell (one Windows prompt). Returns without waiting.
pub fn elevated_powershell(command_text: &str) -> Result<()> {
    let encoded = encoded_command(&elevated_outer_script(command_text));
    command("powershell.exe")
        .args(["-NoProfile", "-NonInteractive", "-EncodedCommand", &encoded])
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .spawn()?;
    Ok(())
}

/// [text] trimmed and cut to [max] characters, for logs.
pub fn head(text: &str, max: usize) -> String {
    text.trim().chars().take(max).collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn base64_matches_known_vectors() {
        assert_eq!(base64(b""), "");
        assert_eq!(base64(b"f"), "Zg==");
        assert_eq!(base64(b"fo"), "Zm8=");
        assert_eq!(base64(b"foo"), "Zm9v");
        assert_eq!(base64(b"foobar"), "Zm9vYmFy");
    }

    #[test]
    fn encoded_command_is_utf16le() {
        // "A" is 41 00 in UTF-16LE.
        assert_eq!(encoded_command("A"), "QQA=");
    }

    #[test]
    fn quotes_are_rejected() {
        assert!(check_quotes(&["C:\\ok", "u"]).is_ok());
        assert!(check_quotes(&["C:\\it's"]).is_err());
        assert!(check_quotes(&["a\"b"]).is_err());
    }

    #[test]
    fn decode_strips_nuls() {
        assert_eq!(decode_output(b"a\0b\0"), "ab");
    }

    #[test]
    fn head_trims_and_cuts() {
        assert_eq!(head("  hello world \n", 5), "hello");
    }
}
