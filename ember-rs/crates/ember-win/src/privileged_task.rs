//! Optional one-time setup that lets Ember start its network helper without a Windows administrator prompt on
//! every connect. Setup (one prompt) registers two scheduled tasks that run the helper from the Ember installation
//! folder with the highest privileges; connecting then just starts a task.
//!
//! The tasks only run `ember-helper.exe` (or `ember-tunnel.ps1`) from the installation folder, and the helper
//! validates everything it reads from its config file.

use std::path::{Path, PathBuf};
use std::process::{Child, Stdio};
use std::thread::sleep;
use std::time::{Duration, Instant};

use crate::{paths, proc, Error, Result};

pub const UP_TASK: &str = r"\Ember\Tunnel Up";
pub const RESTORE_TASK: &str = r"\Ember\Tunnel Restore";
const INSTALL_TIMEOUT: Duration = Duration::from_secs(90);

pub struct PrivilegedTask {
    resources_dir: Option<PathBuf>,
    config_file: PathBuf,
}

impl PrivilegedTask {
    pub fn new(data_dir: &Path) -> Self {
        Self::with_resources(data_dir, paths::resources_dir())
    }

    pub fn with_resources(data_dir: &Path, resources_dir: Option<PathBuf>) -> Self {
        Self { resources_dir, config_file: data_dir.join("tunnel-state").join("config.json") }
    }

    pub fn script(&self) -> Option<PathBuf> {
        self.resources_dir.as_ref().map(|d| d.join("ember-tunnel.ps1")).filter(|p| p.is_file())
    }

    pub fn native_helper(&self) -> Option<PathBuf> {
        self.resources_dir.as_ref().map(|d| d.join("ember-helper.exe")).filter(|p| p.is_file())
    }

    fn task_exists(name: &str) -> bool {
        proc::run15("schtasks.exe", &["/query", "/tn", name]).0 == 0
    }

    pub fn is_installed(&self) -> bool {
        Self::task_exists(UP_TASK) && Self::task_exists(RESTORE_TASK)
    }

    /// True when the registered tasks start the helper this installation ships. Tasks made by an older Ember point at
    /// the PowerShell script; after an update to the native helper they must be registered again (one prompt).
    pub fn is_current(&self) -> bool {
        if self.native_helper().is_none() {
            return true;
        }
        let (code, xml) = proc::run15("schtasks.exe", &["/query", "/tn", UP_TASK, "/xml"]);
        code == 0 && xml.to_lowercase().contains("ember-helper.exe")
    }

    /// Starts the helper through its scheduled task (no prompt). "Up" starts the tunnel, anything else restores.
    pub fn run(&self, mode: &str) -> std::io::Result<Child> {
        let task = if mode == "Up" { UP_TASK } else { RESTORE_TASK };
        proc::command("schtasks.exe")
            .args(["/run", "/tn", task])
            .stdin(Stdio::null())
            .stdout(Stdio::piped())
            .stderr(Stdio::piped())
            .spawn()
    }

    /// Registers the tasks. Windows shows one administrator prompt. Blocks until the tasks exist.
    pub fn install(&self) -> Result<()> {
        let script = self.register_script()?;
        proc::elevated_powershell(&script)?;
        let deadline = Instant::now() + INSTALL_TIMEOUT;
        while Instant::now() < deadline {
            if self.is_installed() && self.is_current() {
                return Ok(());
            }
            sleep(Duration::from_millis(500));
        }
        Err(Error::msg("The one-time setup did not finish. Was the administrator prompt declined?"))
    }

    /// Removes the tasks. Windows shows one administrator prompt.
    pub fn uninstall(&self) -> Result<()> {
        proc::elevated_powershell(UNREGISTER_SCRIPT)?;
        let deadline = Instant::now() + INSTALL_TIMEOUT;
        while Instant::now() < deadline {
            if !self.is_installed() {
                return Ok(());
            }
            sleep(Duration::from_millis(500));
        }
        Err(Error::msg("Removing the one-time setup did not finish."))
    }

    fn register_script(&self) -> Result<String> {
        let native = self.native_helper();
        let helper = native
            .clone()
            .or_else(|| self.script())
            .ok_or_else(|| Error::msg("The network helper is missing from this installation."))?;
        build_register_script(native.as_deref(), &helper, &self.config_file, &proc::current_user())
    }
}

const UNREGISTER_SCRIPT: &str = "Unregister-ScheduledTask -TaskName 'Tunnel Up' -TaskPath '\\Ember\\' -Confirm:$false -ErrorAction SilentlyContinue; \
Unregister-ScheduledTask -TaskName 'Tunnel Restore' -TaskPath '\\Ember\\' -Confirm:$false -ErrorAction SilentlyContinue";

/// The PowerShell that registers the two tasks. [native] is `ember-helper.exe` when shipped; otherwise [helper] is the
/// PowerShell script, run through powershell.exe.
pub fn build_register_script(native: Option<&Path>, helper: &Path, config: &Path, user: &str) -> Result<String> {
    let helper_s = helper.to_string_lossy();
    let config_s = config.to_string_lossy();
    proc::check_quotes(&[&helper_s, &config_s, user])?;
    let task_action = match native {
        Some(n) => format!(
            "$arg = $mode.ToLower() + ' --config \"{config_s}\"'\n                $action = New-ScheduledTaskAction -Execute '{}' -Argument $arg",
            n.to_string_lossy()
        ),
        None => format!(
            "$arg = '-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File \"{helper_s}\" -Mode ' + $mode + ' -ConfigPath \"{config_s}\"'\n                $action = New-ScheduledTaskAction -Execute $ps -Argument $arg"
        ),
    };
    Ok(format!(
        "$ErrorActionPreference = 'Stop'\n\
$ps = Join-Path $env:SystemRoot 'System32\\WindowsPowerShell\\v1.0\\powershell.exe'\n\
function Reg($name, $mode) {{\n\
    {task_action}\n\
    $principal = New-ScheduledTaskPrincipal -UserId '{user}' -LogonType Interactive -RunLevel Highest\n\
    $settings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -ExecutionTimeLimit ([TimeSpan]::Zero)\n\
    Register-ScheduledTask -TaskName $name -TaskPath '\\Ember\\' -Action $action -Principal $principal -Settings $settings -Force | Out-Null\n\
}}\n\
Reg 'Tunnel Up' 'Up'\n\
Reg 'Tunnel Restore' 'Restore'"
    ))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn native_script_runs_the_helper_directly() {
        let s = build_register_script(
            Some(Path::new(r"C:\Ember\ember-helper.exe")),
            Path::new(r"C:\Ember\ember-helper.exe"),
            Path::new(r"C:\Users\a\AppData\Roaming\Ember\tunnel-state\config.json"),
            r"PC\a",
        )
        .unwrap();
        assert!(s.contains("New-ScheduledTaskAction -Execute 'C:\\Ember\\ember-helper.exe' -Argument $arg"));
        assert!(s.contains("$mode.ToLower() + ' --config \"C:\\Users\\a\\AppData\\Roaming\\Ember\\tunnel-state\\config.json\"'"));
        assert!(s.contains("-UserId 'PC\\a' -LogonType Interactive -RunLevel Highest"));
        assert!(s.contains("Reg 'Tunnel Up' 'Up'") && s.contains("Reg 'Tunnel Restore' 'Restore'"));
    }

    #[test]
    fn fallback_script_goes_through_powershell() {
        let s = build_register_script(None, Path::new(r"C:\Ember\ember-tunnel.ps1"), Path::new(r"C:\c.json"), r"PC\a").unwrap();
        assert!(s.contains("-File \"C:\\Ember\\ember-tunnel.ps1\" -Mode ' + $mode + ' -ConfigPath \"C:\\c.json\"'"));
        assert!(s.contains("-Execute $ps -Argument $arg"));
    }

    #[test]
    fn quotes_in_paths_are_refused() {
        assert!(build_register_script(None, Path::new(r"C:\it's\x.ps1"), Path::new(r"C:\c.json"), r"PC\a").is_err());
        assert!(build_register_script(None, Path::new(r"C:\x.ps1"), Path::new(r"C:\c.json"), "PC\\a'b").is_err());
    }

    #[test]
    fn task_without_resources_has_no_helper() {
        let t = PrivilegedTask::with_resources(Path::new(r"C:\data"), None);
        assert!(t.native_helper().is_none() && t.script().is_none());
        assert!(t.is_current()); // no native helper shipped: any registered task is current
    }
}
