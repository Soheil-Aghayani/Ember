//! The one place that touches the network: a small blocking HTTP client (ureq with rustls). Everything else takes an
//! [`Http`], so tests use fixtures and never connect anywhere.
//!
//! Errors never contain the request URL, which can be a subscription link (a secret): only the host is named.

use std::io::Read;
use std::time::Duration;

use serde_json::Value;

#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error)]
pub enum HttpError {
    #[error("The server answered HTTP {0}.")]
    Status(u16),
    #[error("{0}")]
    Transport(String),
}

pub struct Http {
    agent: ureq::Agent,
}

/// A response with any status. The body is read with [`Response::into_reader`] or [`Response::into_text`].
pub struct Response(ureq::Response);

impl Response {
    pub fn status(&self) -> u16 {
        self.0.status()
    }

    pub fn header(&self, name: &str) -> Option<&str> {
        self.0.header(name)
    }

    pub fn into_reader(self) -> Box<dyn Read + Send + Sync + 'static> {
        self.0.into_reader()
    }

    /// The body as text (invalid UTF-8 replaced), at most `limit` bytes.
    pub fn into_text(self, limit: u64) -> std::io::Result<String> {
        let mut bytes = Vec::new();
        self.into_reader().take(limit).read_to_end(&mut bytes)?;
        Ok(String::from_utf8_lossy(&bytes).into_owned())
    }
}

impl Http {
    /// Direct connections.
    pub fn direct(connect: Duration, read: Duration) -> Http {
        Http { agent: ureq::AgentBuilder::new().timeout_connect(connect).timeout_read(read).build() }
    }

    /// Connections through the local SOCKS5 port of a running Xray (`127.0.0.1:port`). `follow_redirects` false hands
    /// back 3xx answers as they are. `call` limits the whole request.
    pub fn socks(port: u16, connect: Duration, read: Duration, call: Duration, follow_redirects: bool) -> Http {
        let mut builder = ureq::AgentBuilder::new().timeout_connect(connect).timeout_read(read).timeout(call);
        if !follow_redirects {
            builder = builder.redirects(0);
        }
        if let Ok(proxy) = ureq::Proxy::new(format!("socks5://127.0.0.1:{port}")) {
            builder = builder.proxy(proxy);
        }
        Http { agent: builder.build() }
    }

    /// A GET. Any HTTP status is an `Ok` response; only failing to talk to the server is an error.
    pub fn get(&self, url: &str, headers: &[(&str, &str)]) -> Result<Response, HttpError> {
        let mut request = self.agent.get(url);
        for (name, value) in headers {
            request = request.set(name, value);
        }
        finish(request.call(), url)
    }

    /// Sends `body` as JSON with `method` and returns the answer's text; a non-2xx status is an error.
    pub fn send_json(&self, method: &str, url: &str, headers: &[(&str, &str)], body: &Value) -> Result<String, HttpError> {
        let mut request = self.agent.request(method, url).set("Content-Type", "application/json");
        for (name, value) in headers {
            request = request.set(name, value);
        }
        let response = finish(request.send_string(&body.to_string()), url)?;
        if !(200..300).contains(&response.status()) {
            return Err(HttpError::Status(response.status()));
        }
        response.into_text(1 << 20).map_err(|_| transport(url, "the answer could not be read"))
    }
}

fn finish(result: Result<ureq::Response, ureq::Error>, url: &str) -> Result<Response, HttpError> {
    match result {
        Ok(response) | Err(ureq::Error::Status(_, response)) => Ok(Response(response)),
        Err(ureq::Error::Transport(t)) => Err(transport(url, &t.kind().to_string())),
    }
}

fn transport(url: &str, what: &str) -> HttpError {
    let host = url::Url::parse(url).ok().and_then(|u| u.host_str().map(str::to_string)).unwrap_or_default();
    HttpError::Transport(format!("Could not reach {host}: {what}."))
}
