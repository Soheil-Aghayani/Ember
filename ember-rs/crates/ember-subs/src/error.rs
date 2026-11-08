use thiserror::Error;

/// Why a link, file or subscription body could not be turned into servers. The texts of `File`, `NoServers` and
/// `JsonWithoutServers` are shown to the user exactly as the Kotlin app showed them.
#[derive(Debug, Error, PartialEq, Eq, Clone)]
pub enum ParseError {
    /// Not a link kind Ember knows (or one it cannot run, such as Hysteria2 with obfuscation).
    #[error("unsupported link type")]
    Unsupported,
    /// A known kind that is malformed. The text never contains the link.
    #[error("invalid link: {0}")]
    Invalid(&'static str),
    /// A specific, user-facing message about a configuration file (WireGuard, OpenVPN, WARP).
    #[error("{0}")]
    File(String),
    #[error("No servers were found in this subscription.")]
    NoServers,
    #[error("This subscription is in a format Ember does not read (JSON without Xray servers). Ask the provider for the standard link list.")]
    JsonWithoutServers,
}

pub(crate) fn file_error<T>(message: impl Into<String>) -> Result<T, ParseError> {
    Err(ParseError::File(message.into()))
}
