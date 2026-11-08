//! Windows integration for Ember (port of the Windows parts of `desktop/.../engine/` and `Main.kt`).
//!
//! Everything that changes the machine (scheduled tasks, the elevated helper, OpenVPN, sing-box) goes through a thin
//! function that runs a program or a Win32 call; the logic around it (command lines, scripts, config JSON, parsing
//! of captured output) is plain and unit-tested. Paths and JSON keys match the Kotlin app.

pub mod app_routing;
pub mod autostart;
pub mod clock;
pub mod network_identity;
pub mod openvpn;
pub mod paths;
pub mod privileged_task;
pub mod proc;
pub mod shell;
pub mod single_instance;
pub mod theme;
pub mod tray;
pub mod windows_tunnel;

/// Error type shared by the runners. The text is shown to the user, so it is plain language.
#[derive(Debug, thiserror::Error)]
pub enum Error {
    #[error("{0}")]
    Message(String),
    /// The OpenVPN server refused the credentials Ember had; the remembered ones are wrong.
    #[error("The server refused the user name or password.")]
    OpenVpnAuthFailed,
    #[error(transparent)]
    Io(#[from] std::io::Error),
}

impl Error {
    pub fn msg(text: impl Into<String>) -> Self {
        Error::Message(text.into())
    }
}

pub type Result<T> = std::result::Result<T, Error>;
