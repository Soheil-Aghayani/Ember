//! Runs Ember's own copy of `xray.exe` without a console window and stops it again. Port of `XrayRunner.kt` and the
//! data of `XrayProcess.kt`.
//!
//! Not ported: `killStale` (ending `xray.exe` copies left by an earlier crash needs process enumeration, which belongs
//! to `ember-win`).

use std::fs;
use std::io::Write as _;
use std::net::{Ipv4Addr, SocketAddr, TcpListener, TcpStream};
use std::path::{Path, PathBuf};
use std::process::{Child, Command, Stdio};
use std::sync::{Mutex, MutexGuard};
use std::time::{Duration, Instant};

use serde_json::Value;

#[derive(Debug, thiserror::Error)]
#[error("{0}")]
pub struct RunnerError(pub String);

#[derive(Default)]
struct State {
    child: Option<Child>,
    config_file: Option<PathBuf>,
}

pub struct XrayRunner {
    exe: Option<PathBuf>,
    work_dir: PathBuf,
    state: Mutex<State>,
}

/// `count` free local ports.
pub fn free_ports(count: usize) -> std::io::Result<Vec<u16>> {
    let listeners = (0..count).map(|_| TcpListener::bind((Ipv4Addr::LOCALHOST, 0))).collect::<Result<Vec<_>, _>>()?;
    listeners.iter().map(|l| l.local_addr().map(|a| a.port())).collect()
}

impl XrayRunner {
    /// `data_dir/xray` becomes the working folder. `exe` is the packaged `xray.exe`, if there is one.
    pub fn new(data_dir: &Path, exe: Option<PathBuf>) -> std::io::Result<XrayRunner> {
        let work_dir = data_dir.join("xray");
        fs::create_dir_all(&work_dir)?;
        Ok(XrayRunner { exe, work_dir, state: Mutex::new(State::default()) })
    }

    pub fn is_available(&self) -> bool {
        self.exe.as_ref().is_some_and(|e| e.is_file())
    }

    pub fn log_file(&self) -> PathBuf {
        self.work_dir.join("xray.log")
    }

    fn lock(&self) -> MutexGuard<'_, State> {
        self.state.lock().unwrap_or_else(|e| e.into_inner())
    }

    /// Writes `config` and starts Xray with it, ending a copy this runner already started.
    pub fn start(&self, config: &Value) -> Result<(), RunnerError> {
        let mut state = self.lock();
        stop_locked(&mut state);
        let exe = self.exe.as_ref().filter(|e| e.is_file()).ok_or_else(|| RunnerError("The Xray program is missing from this installation.".into()))?;
        let _ = fs::remove_file(self.log_file());
        let file = self.work_dir.join("run.json");
        fs::File::create(&file)
            .and_then(|mut f| f.write_all(config.to_string().as_bytes()))
            .map_err(|e| RunnerError(format!("Could not write the Xray settings ({}).", e.kind())))?;
        let mut command = Command::new(exe);
        command.arg("run").arg("-c").arg(&file).current_dir(&self.work_dir).stdin(Stdio::null()).stdout(Stdio::null()).stderr(Stdio::null());
        #[cfg(windows)]
        {
            use std::os::windows::process::CommandExt;
            command.creation_flags(0x0800_0000); // CREATE_NO_WINDOW
        }
        match command.spawn() {
            Ok(child) => {
                state.child = Some(child);
                state.config_file = Some(file);
                Ok(())
            }
            Err(e) => {
                let _ = fs::remove_file(&file);
                Err(RunnerError(format!("Windows could not start Xray ({}).", e.kind())))
            }
        }
    }

    pub fn is_running(&self) -> bool {
        let mut state = self.lock();
        state.child.as_mut().is_some_and(|c| matches!(c.try_wait(), Ok(None)))
    }

    /// Waits until Xray accepts connections on `port`, or fails with what it logged.
    pub fn await_listening(&self, port: u16, timeout: Duration) -> Result<(), RunnerError> {
        let deadline = Instant::now() + timeout;
        let addr = SocketAddr::from((Ipv4Addr::LOCALHOST, port));
        while Instant::now() < deadline {
            if !self.is_running() {
                return Err(RunnerError(format!("Xray stopped right after it started. {}", self.log_tail())));
            }
            if TcpStream::connect_timeout(&addr, Duration::from_millis(300)).is_ok() {
                // The settings are read; the file holds the account's keys, so remove it.
                let mut state = self.lock();
                if let Some(file) = state.config_file.take() {
                    let _ = fs::remove_file(file);
                }
                return Ok(());
            }
            std::thread::sleep(Duration::from_millis(100));
        }
        let tail = self.log_tail();
        let hint = if tail.is_empty() {
            format!("It wrote nothing, so something may be holding xray.exe up (an antivirus scan, or a program using port {port}).")
        } else {
            tail
        };
        Err(RunnerError(format!("Xray did not start in time. {hint}")))
    }

    fn log_tail(&self) -> String {
        let text = fs::read_to_string(self.log_file()).unwrap_or_default();
        let lines: Vec<&str> = text.lines().collect();
        let tail = lines[lines.len().saturating_sub(3)..].join(" ");
        tail.chars().take(400).collect()
    }

    pub fn stop(&self) {
        stop_locked(&mut self.lock());
    }
}

fn stop_locked(state: &mut State) {
    if let Some(mut child) = state.child.take() {
        let _ = child.kill();
        let _ = child.wait();
    }
    if let Some(file) = state.config_file.take() {
        let _ = fs::remove_file(file);
    }
}

impl Drop for XrayRunner {
    fn drop(&mut self) {
        self.stop();
    }
}
