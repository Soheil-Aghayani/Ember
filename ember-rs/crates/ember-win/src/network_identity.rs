//! Names the network this computer is on, so Ember can tell a trusted network (home) from an untrusted one.
//! Wi-Fi networks are identified by their name (SSID). Anything wired is simply "wired".

use std::net::Ipv4Addr;
use std::time::Duration;

use crate::proc;

/// "wifi:<name>", "wired", or None when the computer looks offline.
pub fn current() -> Option<String> {
    if let Some(name) = wifi_name() {
        return Some(format!("wifi:{name}"));
    }
    has_wired_link().then(|| "wired".to_string())
}

/// The user-facing name of an id from [current], for example the Wi-Fi name.
pub fn display_name(id: &str) -> String {
    id.strip_prefix("wifi:").map_or_else(|| "Wired network".to_string(), str::to_string)
}

fn wifi_name() -> Option<String> {
    let (_, text) = proc::run("netsh.exe", &["wlan", "show", "interfaces"], Duration::from_secs(5));
    parse_ssid(&text)
}

/// The connected SSID from `netsh wlan show interfaces`. "SSID" (not "BSSID") is only listed while the adapter is
/// connected, and the label is not translated.
pub fn parse_ssid(text: &str) -> Option<String> {
    text.lines()
        .map(str::trim)
        .find(|l| l.starts_with("SSID") && !l.starts_with("SSID name") && l.split(':').next().is_some_and(|k| k.trim() == "SSID"))
        .and_then(|l| l.split_once(':'))
        .map(|(_, v)| v.trim().to_string())
        .filter(|v| !v.is_empty())
}

const NOT_WIRED: [&str; 10] = ["vethernet", "hyper-v", "vmware", "virtualbox", "wintun", "loopback", "bluetooth", "wi-fi", "wireless", "wlan"];

/// True for an adapter that counts as a wired link: up, not loopback, not a virtual or wireless adapter, and with a
/// usable IPv4 address (not link-local, not Ember's own 10.8.0.x tunnel).
pub fn is_wired_candidate(description: &str, up: bool, loopback: bool, ipv4: &[Ipv4Addr]) -> bool {
    let lower = description.to_lowercase();
    up && !loopback
        && !NOT_WIRED.iter().any(|w| lower.contains(w))
        && ipv4.iter().any(|a| !a.is_link_local() && !(a.octets()[..3] == [10, 8, 0]))
}

#[cfg(windows)]
fn has_wired_link() -> bool {
    use windows::Win32::NetworkManagement::IpHelper::{
        GetAdaptersAddresses, GAA_FLAG_SKIP_ANYCAST, GAA_FLAG_SKIP_DNS_SERVER, GAA_FLAG_SKIP_MULTICAST, IP_ADAPTER_ADDRESSES_LH,
    };
    use windows::Win32::NetworkManagement::Ndis::IfOperStatusUp;
    use windows::Win32::Networking::WinSock::{AF_INET, SOCKADDR_IN};

    const IF_TYPE_SOFTWARE_LOOPBACK: u32 = 24;
    const BUFFER_TOO_SMALL: u32 = 111;
    let flags = GAA_FLAG_SKIP_ANYCAST | GAA_FLAG_SKIP_MULTICAST | GAA_FLAG_SKIP_DNS_SERVER;
    let mut size: u32 = 16 * 1024;
    // u64 elements keep the buffer 8-byte aligned, as IP_ADAPTER_ADDRESSES requires.
    let mut buffer: Vec<u64> = Vec::new();
    for _ in 0..3 {
        buffer = vec![0u64; (size as usize).div_ceil(8)];
        // SAFETY: `buffer` is at least `size` bytes, writable and aligned; `size` is updated by the call when too small.
        let code = unsafe {
            GetAdaptersAddresses(u32::from(AF_INET.0), flags, None, Some(buffer.as_mut_ptr().cast::<IP_ADAPTER_ADDRESSES_LH>()), &mut size)
        };
        match code {
            0 => break,
            BUFFER_TOO_SMALL => buffer.clear(),
            _ => return false,
        }
    }
    if buffer.is_empty() {
        return false;
    }
    let mut adapter = buffer.as_ptr().cast::<IP_ADAPTER_ADDRESSES_LH>();
    while !adapter.is_null() {
        // SAFETY: the list was filled by GetAdaptersAddresses inside `buffer`, which outlives this loop; every
        // pointer followed here is either null (checked) or points into that buffer.
        let a = unsafe { &*adapter };
        // SAFETY: Description is a NUL-terminated wide string inside the buffer, or null (checked by to_string).
        let description = unsafe { a.Description.to_string() }.unwrap_or_default();
        let mut ips = Vec::new();
        let mut unicast = a.FirstUnicastAddress;
        while !unicast.is_null() {
            // SAFETY: same buffer; the node and its sockaddr are valid while `buffer` is alive.
            let node = unsafe { &*unicast };
            let sa = node.Address.lpSockaddr;
            // SAFETY: lpSockaddr is non-null for a listed address; AF_INET addresses are SOCKADDR_IN.
            if !sa.is_null() && unsafe { (*sa).sa_family } == AF_INET {
                let v4 = unsafe { &*sa.cast::<SOCKADDR_IN>() };
                // SAFETY: reading the IPv4 address as 32 bits is valid for every SOCKADDR_IN.
                ips.push(Ipv4Addr::from(u32::from_be(unsafe { v4.sin_addr.S_un.S_addr })));
            }
            unicast = node.Next;
        }
        if is_wired_candidate(&description, a.OperStatus == IfOperStatusUp, a.IfType == IF_TYPE_SOFTWARE_LOOPBACK, &ips) {
            return true;
        }
        adapter = a.Next;
    }
    false
}

#[cfg(not(windows))]
fn has_wired_link() -> bool {
    false
}

#[cfg(test)]
mod tests {
    use super::*;

    const NETSH: &str = "\
There is 1 interface on the system:

    Name                   : Wi-Fi
    Description            : Intel(R) Wi-Fi 6 AX201 160MHz
    State                  : connected
    SSID                   : Home Net: 5G
    BSSID                  : aa:bb:cc:dd:ee:ff
    Network type           : Infrastructure
";

    #[test]
    fn ssid_is_read_and_may_contain_a_colon() {
        assert_eq!(parse_ssid(NETSH).as_deref(), Some("Home Net: 5G"));
    }

    #[test]
    fn disconnected_adapter_has_no_ssid() {
        assert_eq!(parse_ssid("    Name : Wi-Fi\n    State : disconnected\n"), None);
        assert_eq!(parse_ssid("    SSID                   : \n"), None);
    }

    #[test]
    fn bssid_and_ssid_name_do_not_count() {
        assert_eq!(parse_ssid("    BSSID : aa\n    SSID name : x\n"), None);
    }

    #[test]
    fn display_names() {
        assert_eq!(display_name("wifi:Cafe"), "Cafe");
        assert_eq!(display_name("wired"), "Wired network");
    }

    #[test]
    fn wired_filter() {
        let ok = [Ipv4Addr::new(192, 168, 1, 5)];
        assert!(is_wired_candidate("Realtek PCIe GbE", true, false, &ok));
        assert!(!is_wired_candidate("Realtek PCIe GbE", false, false, &ok));
        assert!(!is_wired_candidate("Hyper-V Virtual Ethernet Adapter", true, false, &ok));
        assert!(!is_wired_candidate("Wintun Userspace Tunnel", true, false, &ok));
        assert!(!is_wired_candidate("Realtek", true, false, &[Ipv4Addr::new(169, 254, 3, 3)]));
        assert!(!is_wired_candidate("Realtek", true, false, &[Ipv4Addr::new(10, 8, 0, 2)]));
        assert!(is_wired_candidate("Realtek", true, false, &[Ipv4Addr::new(10, 9, 0, 2)]));
    }
}
