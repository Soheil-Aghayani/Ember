//! Runs the OpenVPN program the user installed (openvpn.net/community-downloads) for `.ovpn` servers. OpenVPN needs
//! administrator rights to add its adapter and routes, so a scheduled task (one Windows prompt, once) starts it; Ember
//! then follows it through OpenVPN's management port, which is also where user names and passwords are handed over
//! (they never touch the disk). Ember's kill switch does not cover these servers.

use std::io::{self, Read, Write};
use std::net::{SocketAddr, TcpStream};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, Ordering};
use std::thread::sleep;
use std::time::{Duration, Instant};

use crate::{proc, Error, Result};

const TASK: &str = r"\Ember\OpenVPN";
pub const MANAGEMENT_PORT: u16 = 17505;
const CONNECT_TIMEOUT: Duration = Duration::from_secs(90);
const ENTER_PASSWORD: &str = "ENTER PASSWORD:";

/// What the user typed for a profile that asks for credentials. `username` is None when only a password (a key's) is asked.
pub struct OpenVpnCredentials {
    pub username: Option<String>,
    pub password: String,
}

/// One line from the management port, understood.
#[derive(Debug, PartialEq, Eq)]
pub enum Event {
    EnterPassword,
    PasswordAccepted,
    AuthFailed,
    /// `>PASSWORD:Need 'subject' username/password` (ask_user) or `... password`.
    NeedCredentials { subject: String, ask_user: bool },
    State { name: String, detail: String },
    Fatal(String),
    Other,
}

pub fn parse_line(line: &str) -> Event {
    if line == ENTER_PASSWORD {
        Event::EnterPassword
    } else if line.starts_with("SUCCESS: password is correct") {
        Event::PasswordAccepted
    } else if line.starts_with(">PASSWORD:Verification Failed") {
        Event::AuthFailed
    } else if let Some(rest) = line.strip_prefix(">PASSWORD:Need '") {
        // greedy subject like the Kotlin regex `Need '(.+)' (username/password|password)`
        let Some(quote) = rest.rfind("' ") else { return Event::Other };
        let (subject, kind) = (&rest[..quote], &rest[quote + 2..]);
        if subject.is_empty() {
            return Event::Other;
        }
        if kind.starts_with("username/password") {
            Event::NeedCredentials { subject: subject.to_string(), ask_user: true }
        } else if kind.starts_with("password") {
            Event::NeedCredentials { subject: subject.to_string(), ask_user: false }
        } else {
            Event::Other
        }
    } else if let Some(rest) = line.strip_prefix(">STATE:") {
        let mut parts = rest.split(',');
        let _time = parts.next();
        let name = parts.next().unwrap_or_default().to_string();
        let detail = parts.next().unwrap_or_default().to_string();
        Event::State { name, detail }
    } else if let Some(rest) = line.strip_prefix(">FATAL:") {
        Event::Fatal(rest.trim().to_string())
    } else {
        Event::Other
    }
}

/// Escapes a value for a management `username "x" "y"` command.
pub fn escape(value: &str) -> String {
    value.replace('\\', "\\\\").replace('"', "\\\"")
}

pub fn username_command(subject: &str, username: &str) -> String {
    format!("username \"{subject}\" \"{}\"", escape(username))
}

pub fn password_command(subject: &str, password: &str) -> String {
    format!("password \"{subject}\" \"{}\"", escape(password))
}

/// Reads management lines; the password prompt arrives without an end of line, so it is recognised on its own.
pub struct LineReader<R: Read> {
    input: R,
    buffer: Vec<u8>,
}

impl<R: Read> LineReader<R> {
    pub fn new(input: R) -> Self {
        Self { input, buffer: Vec::new() }
    }

    /// The next non-empty line, or `Ok(None)` at the end of the stream. A read timeout is returned as an error
    /// (`WouldBlock`/`TimedOut`) and keeps what has arrived so far.
    pub fn next_line(&mut self) -> io::Result<Option<String>> {
        loop {
            if let Some(pos) = self.buffer.iter().position(|b| *b == b'\n') {
                let raw: Vec<u8> = self.buffer.drain(..=pos).collect();
                let line = String::from_utf8_lossy(&raw[..raw.len() - 1]).trim_end_matches('\r').to_string();
                if !line.is_empty() {
                    return Ok(Some(line));
                }
                continue;
            }
            if self.buffer == ENTER_PASSWORD.as_bytes() {
                self.buffer.clear();
                return Ok(Some(ENTER_PASSWORD.to_string()));
            }
            let mut byte = [0u8; 1];
            match self.input.read(&mut byte) {
                Ok(0) => return Ok(None),
                Ok(_) => self.buffer.push(byte[0]),
                Err(e) if e.kind() == io::ErrorKind::Interrupted => {}
                Err(e) => return Err(e),
            }
        }
    }
}

/// The scheduled task's command line. Everything that matters is on it; the profile cannot change it
/// (`script-security` is set after the profile is read).
pub fn task_arguments(profile: &Path, password_file: &Path, log_file: &Path) -> String {
    format!(
        "--config \"{}\" --script-security 1 --management 127.0.0.1 {MANAGEMENT_PORT} \"{}\" --management-hold --management-query-passwords --log \"{}\" --verb 3",
        profile.display(),
        password_file.display(),
        log_file.display()
    )
}

/// The PowerShell that registers the `\Ember\OpenVPN` task.
pub fn build_install_script(exe: &Path, arguments: &str, dir: &Path, user: &str) -> Result<String> {
    let (exe_s, dir_s) = (exe.to_string_lossy(), dir.to_string_lossy());
    proc::check_quotes(&[&exe_s, &dir_s, user])?;
    Ok(format!(
        "$ErrorActionPreference = 'Stop'\n\
$action = New-ScheduledTaskAction -Execute '{exe_s}' -Argument '{arguments}' -WorkingDirectory '{dir_s}'\n\
$principal = New-ScheduledTaskPrincipal -UserId '{user}' -LogonType S4U -RunLevel Highest\n\
$settings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -ExecutionTimeLimit ([TimeSpan]::Zero)\n\
Register-ScheduledTask -TaskName 'OpenVPN' -TaskPath '\\Ember\\' -Action $action -Principal $principal -Settings $settings -Force | Out-Null"
    ))
}

/// True when the task XML starts [exe] with the options this version of Ember expects.
pub fn task_xml_ready(xml: &str, exe: &Path) -> bool {
    let x = xml.to_lowercase();
    x.contains(&exe.to_string_lossy().to_lowercase())
        && x.contains(&MANAGEMENT_PORT.to_string())
        && x.contains("management-query-passwords")
        && x.contains("s4u")
}

/// The last two lines of OpenVPN's log that name a problem, for the error shown to the user.
pub fn log_hint(log: &str) -> String {
    let lines: Vec<&str> = log
        .lines()
        .filter(|l| {
            let l = l.to_lowercase();
            ["error", "fail", "cannot", "auth", "tls"].iter().any(|w| l.contains(w))
        })
        .collect();
    let tail = &lines[lines.len().saturating_sub(2)..];
    if tail.is_empty() {
        return String::new();
    }
    let joined = tail.iter().map(|l| l.split_once(' ').map_or(*l, |(_, rest)| rest).trim()).collect::<Vec<_>>().join(" ");
    format!(" {}", joined.chars().take(200).collect::<String>())
}

/// 16 random bytes as 32 hex characters: the management password.
fn random_secret() -> Result<String> {
    let mut bytes = [0u8; 16];
    fill_random(&mut bytes)?;
    Ok(bytes.iter().map(|b| format!("{b:02x}")).collect())
}

#[cfg(windows)]
fn fill_random(bytes: &mut [u8]) -> Result<()> {
    use windows::Win32::Security::Cryptography::{BCryptGenRandom, BCRYPT_USE_SYSTEM_PREFERRED_RNG};
    // SAFETY: `bytes` is a valid writable slice; a null algorithm handle with the system-preferred flag is the documented form.
    let status = unsafe { BCryptGenRandom(None, bytes, BCRYPT_USE_SYSTEM_PREFERRED_RNG) };
    if status.is_ok() {
        Ok(())
    } else {
        Err(Error::msg("Windows could not provide a random number."))
    }
}

#[cfg(not(windows))]
fn fill_random(bytes: &mut [u8]) -> Result<()> {
    std::fs::File::open("/dev/urandom")?.read_exact(bytes)?;
    Ok(())
}

pub struct OpenVpnRunner {
    dir: PathBuf,
    profile_file: PathBuf,
    password_file: PathBuf,
    log_file: PathBuf,
}

impl OpenVpnRunner {
    pub fn new(data_dir: &Path) -> Self {
        let dir = data_dir.join("openvpn");
        Self {
            profile_file: dir.join("active.ovpn"),
            password_file: dir.join("management.pw"),
            log_file: dir.join("openvpn.log"),
            dir,
        }
    }

    /// The installed `openvpn.exe`, or None when OpenVPN is not installed.
    pub fn executable() -> Option<PathBuf> {
        let mut seen: Vec<String> = Vec::new();
        for var in ["ProgramFiles", "ProgramW6432", "ProgramFiles(x86)"] {
            let Ok(base) = std::env::var(var) else { continue };
            if seen.contains(&base) {
                continue;
            }
            let exe = Path::new(&base).join(r"OpenVPN\bin\openvpn.exe");
            seen.push(base);
            if exe.is_file() {
                return Some(exe);
            }
        }
        None
    }

    pub fn task_ready(&self, exe: &Path) -> bool {
        task_xml_ready(&proc::run15("schtasks.exe", &["/query", "/tn", TASK, "/xml"]).1, exe)
    }

    fn is_running() -> bool {
        proc::run15("tasklist.exe", &["/fi", "imagename eq openvpn.exe", "/fo", "csv", "/nh"]).1.to_lowercase().contains("openvpn.exe")
    }

    /// Registers the task. Windows shows one administrator prompt. Blocks until the task exists.
    pub fn install_task(&self, exe: &Path) -> Result<()> {
        std::fs::create_dir_all(&self.dir)?;
        let arguments = task_arguments(&self.profile_file, &self.password_file, &self.log_file);
        let script = build_install_script(exe, &arguments, &self.dir, &proc::current_user())?;
        proc::elevated_powershell(&script)?;
        let deadline = Instant::now() + Duration::from_secs(90);
        while Instant::now() < deadline {
            if self.task_ready(exe) {
                return Ok(());
            }
            sleep(Duration::from_millis(500));
        }
        Err(Error::msg("The one-time setup did not finish. Was the administrator prompt declined?"))
    }

    /// Starts OpenVPN with [profile] and follows it until it ends. Returns when OpenVPN stops; fails when it never
    /// connects. [on_connected] runs once the tunnel is up. Setting [cancel] shuts OpenVPN down and returns Ok.
    /// `ask_credentials(subject, ask_user)` returns None when the user cancels.
    pub fn session(
        &self,
        profile: &str,
        ask_credentials: &mut dyn FnMut(&str, bool) -> Option<OpenVpnCredentials>,
        on_progress: &mut dyn FnMut(&str),
        on_connected: &mut dyn FnMut(),
        cancel: &AtomicBool,
    ) -> Result<()> {
        std::fs::create_dir_all(&self.dir)?;
        if Self::is_running() {
            // an OpenVPN left running by an earlier Ember (it holds the old management password): end it first
            proc::run15("schtasks.exe", &["/end", "/tn", TASK]);
            let begun = Instant::now();
            while begun.elapsed() < Duration::from_secs(4) && Self::is_running() {
                sleep(Duration::from_millis(300));
            }
        }
        let secret = random_secret()?;
        std::fs::write(&self.profile_file, profile)?;
        std::fs::write(&self.password_file, &secret)?;
        let _ = std::fs::remove_file(&self.log_file);
        proc::run15("schtasks.exe", &["/run", "/tn", TASK]);

        let mut socket: Option<TcpStream> = None;
        let result = self.follow(&secret, &mut socket, ask_credentials, on_progress, on_connected, cancel);
        self.stop(socket);
        result
    }

    fn follow(
        &self,
        secret: &str,
        socket_slot: &mut Option<TcpStream>,
        ask_credentials: &mut dyn FnMut(&str, bool) -> Option<OpenVpnCredentials>,
        on_progress: &mut dyn FnMut(&str),
        on_connected: &mut dyn FnMut(),
        cancel: &AtomicBool,
    ) -> Result<()> {
        let Some(stream) = self.open_management(cancel)? else { return Ok(()) };
        stream.set_read_timeout(Some(Duration::from_millis(500)))?;
        let mut writer = stream.try_clone()?;
        let mut reader = LineReader::new(stream.try_clone()?);
        *socket_slot = Some(stream);
        let mut send = |line: &str| -> io::Result<()> {
            writer.write_all(format!("{line}\n").as_bytes())?;
            writer.flush()
        };
        let mut connected = false;
        let mut deadline = Instant::now() + CONNECT_TIMEOUT;
        loop {
            if cancel.load(Ordering::Relaxed) {
                return Ok(());
            }
            let line = match reader.next_line() {
                Ok(Some(line)) => line,
                Ok(None) => break,
                Err(e) if matches!(e.kind(), io::ErrorKind::WouldBlock | io::ErrorKind::TimedOut) => {
                    if !connected && Instant::now() > deadline {
                        return Err(Error::msg(format!("OpenVPN did not connect in time.{}", self.log_hint())));
                    }
                    continue;
                }
                Err(e) => return Err(e.into()),
            };
            match parse_line(&line) {
                Event::EnterPassword => send(secret)?,
                Event::PasswordAccepted => {
                    send("state on")?;
                    send("hold release")?;
                }
                Event::AuthFailed => return Err(Error::OpenVpnAuthFailed),
                Event::NeedCredentials { subject, ask_user } => {
                    on_progress("Waiting for your sign-in…");
                    let credentials = ask_credentials(&subject, ask_user).ok_or_else(|| Error::msg("Sign-in was cancelled."))?;
                    if ask_user {
                        send(&username_command(&subject, credentials.username.as_deref().unwrap_or_default()))?;
                    }
                    send(&password_command(&subject, &credentials.password))?;
                    deadline = Instant::now() + CONNECT_TIMEOUT;
                    on_progress("Signing in…");
                }
                Event::State { name, detail } => match name.as_str() {
                    "CONNECTED" if !connected => {
                        connected = true;
                        on_connected();
                    }
                    "EXITING" => {
                        if !connected {
                            return Err(Error::msg(format!("OpenVPN stopped: {detail}.{}", self.log_hint())));
                        }
                        break;
                    }
                    "AUTH" | "GET_CONFIG" if !connected => on_progress("Signing in…"),
                    "ASSIGN_IP" | "ADD_ROUTES" if !connected => on_progress("Setting up the connection…"),
                    "CONNECTED" | "AUTH" | "GET_CONFIG" | "ASSIGN_IP" | "ADD_ROUTES" | "RECONNECTING" => {}
                    _ if !connected => on_progress("Reaching the server…"),
                    _ => {}
                },
                Event::Fatal(text) => return Err(Error::msg(format!("{text}{}", self.log_hint()))),
                Event::Other => {}
            }
        }
        if connected {
            Ok(())
        } else {
            Err(Error::msg(format!("OpenVPN stopped before it connected.{}", self.log_hint())))
        }
    }

    /// Connects to the management port, waiting for OpenVPN to open it. None when cancelled meanwhile.
    fn open_management(&self, cancel: &AtomicBool) -> Result<Option<TcpStream>> {
        let addr = SocketAddr::from(([127, 0, 0, 1], MANAGEMENT_PORT));
        let started = Instant::now();
        loop {
            if cancel.load(Ordering::Relaxed) {
                return Ok(None);
            }
            if let Ok(stream) = TcpStream::connect_timeout(&addr, Duration::from_millis(500)) {
                return Ok(Some(stream));
            }
            let waited = started.elapsed();
            // OpenVPN that has already exited (no network driver, a bad option) will never open the port.
            if waited > Duration::from_secs(4) && !Self::is_running() {
                return Err(Error::msg(format!("OpenVPN did not start.{}", self.log_hint())));
            }
            if waited > Duration::from_secs(30) {
                return Err(Error::msg(format!("OpenVPN did not answer.{}", self.log_hint())));
            }
            sleep(Duration::from_millis(300));
        }
    }

    /// Asks OpenVPN to close down cleanly, then ends the task if it is still there.
    fn stop(&self, socket: Option<TcpStream>) {
        if let Some(mut s) = socket {
            let _ = s.write_all(b"signal SIGTERM\n");
            let _ = s.flush();
            let _ = s.shutdown(std::net::Shutdown::Both);
        }
        let begun = Instant::now();
        while begun.elapsed() < Duration::from_secs(8) && Self::is_running() {
            sleep(Duration::from_millis(500));
        }
        if Self::is_running() {
            proc::run15("schtasks.exe", &["/end", "/tn", TASK]);
        }
    }

    fn log_hint(&self) -> String {
        std::fs::read_to_string(&self.log_file).map(|t| log_hint(&t)).unwrap_or_default()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::VecDeque;

    #[test]
    fn lines_are_classified() {
        assert_eq!(parse_line("ENTER PASSWORD:"), Event::EnterPassword);
        assert_eq!(parse_line("SUCCESS: password is correct"), Event::PasswordAccepted);
        assert_eq!(parse_line(">PASSWORD:Verification Failed: 'Auth'"), Event::AuthFailed);
        assert_eq!(
            parse_line(">PASSWORD:Need 'Auth' username/password"),
            Event::NeedCredentials { subject: "Auth".into(), ask_user: true }
        );
        assert_eq!(
            parse_line(">PASSWORD:Need 'Private Key' password"),
            Event::NeedCredentials { subject: "Private Key".into(), ask_user: false }
        );
        assert_eq!(parse_line(">PASSWORD:Need 'x' something"), Event::Other);
        assert_eq!(
            parse_line(">STATE:1700000000,CONNECTED,SUCCESS,10.8.0.6,1.2.3.4"),
            Event::State { name: "CONNECTED".into(), detail: "SUCCESS".into() }
        );
        assert_eq!(parse_line(">STATE:1,EXITING,exit-with-notification,,"), Event::State { name: "EXITING".into(), detail: "exit-with-notification".into() });
        assert_eq!(parse_line(">FATAL:  boom "), Event::Fatal("boom".into()));
        assert_eq!(parse_line(">LOG:hello"), Event::Other);
    }

    #[test]
    fn credentials_are_escaped() {
        assert_eq!(username_command("Auth", r#"a"b\c"#), r#"username "Auth" "a\"b\\c""#);
        assert_eq!(password_command("Auth", "p\"w"), r#"password "Auth" "p\"w""#);
    }

    /// Hands out bytes in chunks and reports a timeout between them.
    struct Script(VecDeque<Option<Vec<u8>>>, VecDeque<u8>);

    impl Read for Script {
        fn read(&mut self, buf: &mut [u8]) -> io::Result<usize> {
            if self.1.is_empty() {
                match self.0.pop_front() {
                    Some(Some(chunk)) => self.1.extend(chunk),
                    Some(None) => return Err(io::ErrorKind::TimedOut.into()),
                    None => return Ok(0),
                }
            }
            buf[0] = self.1.pop_front().unwrap();
            Ok(1)
        }
    }

    fn script(parts: Vec<Option<&str>>) -> LineReader<Script> {
        LineReader::new(Script(parts.into_iter().map(|p| p.map(|s| s.as_bytes().to_vec())).collect(), VecDeque::new()))
    }

    #[test]
    fn reader_splits_lines_and_skips_blanks() {
        let mut r = script(vec![Some(">INFO:hi\r\n\r\n>STATE:1,A\r\n")]);
        assert_eq!(r.next_line().unwrap().as_deref(), Some(">INFO:hi"));
        assert_eq!(r.next_line().unwrap().as_deref(), Some(">STATE:1,A"));
        assert_eq!(r.next_line().unwrap(), None);
    }

    #[test]
    fn reader_recognises_the_password_prompt_without_newline() {
        let mut r = script(vec![Some(">INFO:x\n"), Some("ENTER PASSWORD:")]);
        assert_eq!(r.next_line().unwrap().as_deref(), Some(">INFO:x"));
        assert_eq!(r.next_line().unwrap().as_deref(), Some("ENTER PASSWORD:"));
    }

    #[test]
    fn reader_keeps_partial_lines_across_a_timeout() {
        let mut r = script(vec![Some(">STATE:1,CONN"), None, Some("ECTED\n")]);
        assert_eq!(r.next_line().unwrap_err().kind(), io::ErrorKind::TimedOut);
        assert_eq!(r.next_line().unwrap().as_deref(), Some(">STATE:1,CONNECTED"));
    }

    #[test]
    fn task_arguments_keep_everything_on_the_command_line() {
        let a = task_arguments(Path::new(r"C:\d\active.ovpn"), Path::new(r"C:\d\management.pw"), Path::new(r"C:\d\openvpn.log"));
        assert_eq!(
            a,
            "--config \"C:\\d\\active.ovpn\" --script-security 1 --management 127.0.0.1 17505 \"C:\\d\\management.pw\" --management-hold --management-query-passwords --log \"C:\\d\\openvpn.log\" --verb 3"
        );
    }

    #[test]
    fn task_xml_check_needs_all_options() {
        let exe = Path::new(r"C:\Program Files\OpenVPN\bin\openvpn.exe");
        let xml = r"<Command>C:\Program Files\OpenVPN\bin\openvpn.exe</Command><Arguments>--management 127.0.0.1 17505 --management-query-passwords</Arguments><LogonType>S4U</LogonType>";
        assert!(task_xml_ready(xml, exe));
        assert!(!task_xml_ready(&xml.replace("17505", "1"), exe));
        assert!(!task_xml_ready(&xml.replace("--management-query-passwords", ""), exe));
    }

    #[test]
    fn install_script_refuses_quotes() {
        assert!(build_install_script(Path::new(r"C:\a\openvpn.exe"), "--x", Path::new(r"C:\d"), r"PC\u").is_ok());
        assert!(build_install_script(Path::new(r"C:\a'b\openvpn.exe"), "--x", Path::new(r"C:\d"), r"PC\u").is_err());
    }

    #[test]
    fn log_hint_keeps_two_problem_lines() {
        let log = "2024 Note\n2024 TLS Error: handshake\n2024 AUTH_FAILED\n2024 cannot open";
        assert_eq!(log_hint(log), " AUTH_FAILED cannot open");
        assert_eq!(log_hint("fine"), "");
    }
}
