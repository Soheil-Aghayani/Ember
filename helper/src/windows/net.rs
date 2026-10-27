use ember_helper::{
    ip::Cidr,
    network::{self, DefaultRoute},
    Result,
};
use std::{mem::size_of, net::Ipv4Addr};
use windows::{
    core::{GUID, PWSTR},
    Win32::{
        Foundation::{ERROR_BUFFER_OVERFLOW, ERROR_NO_DATA, WIN32_ERROR},
        NetworkManagement::{
            IpHelper::*,
            Ndis::{IfOperStatusUp, NET_LUID_LH},
        },
        Networking::WinSock::*,
    },
};

#[derive(Clone)]
pub struct Adapter {
    pub index: u32,
    pub name: String,
    pub up: bool,
    luid: NET_LUID_LH,
}

fn check(code: WIN32_ERROR) -> Result<()> {
    code.ok().map_err(|e| e.to_string())
}

pub fn adapters() -> Result<Vec<Adapter>> {
    let mut bytes = 15_000u32;
    for _ in 0..5 {
        // u64 storage provides the alignment required by IP_ADAPTER_ADDRESSES_LH.
        let mut buffer = vec![0u64; (bytes as usize).div_ceil(size_of::<u64>())];
        let head = buffer.as_mut_ptr().cast::<IP_ADAPTER_ADDRESSES_LH>();
        // SAFETY: buffer is writable, aligned, and lives through traversal of the API's linked list.
        let code = unsafe {
            GetAdaptersAddresses(
                AF_UNSPEC.0 as u32,
                GAA_FLAG_INCLUDE_ALL_INTERFACES,
                None,
                Some(head),
                &mut bytes,
            )
        };
        if code == ERROR_BUFFER_OVERFLOW.0 {
            continue;
        }
        if code == ERROR_NO_DATA.0 {
            return Ok(Vec::new());
        }
        check(WIN32_ERROR(code))?;
        let mut result = Vec::new();
        let mut cursor = head;
        // SAFETY: on success all list nodes and terminated names refer to the live API buffer.
        unsafe {
            while !cursor.is_null() {
                let a = &*cursor;
                result.push(Adapter {
                    index: a.Anonymous1.Anonymous.IfIndex,
                    name: if a.FriendlyName.is_null() {
                        String::new()
                    } else {
                        a.FriendlyName.to_string().map_err(|e| e.to_string())?
                    },
                    up: a.OperStatus == IfOperStatusUp,
                    luid: a.Luid,
                });
                cursor = a.Next;
            }
        }
        return Ok(result);
    }
    Err("adapter list kept changing".into())
}

struct RouteTable(*mut MIB_IPFORWARD_TABLE2);
impl Drop for RouteTable {
    fn drop(&mut self) {
        // SAFETY: this is the allocation returned by GetIpForwardTable2, freed once.
        unsafe {
            FreeMibTable(self.0.cast());
        }
    }
}

fn route_rows() -> Result<Vec<MIB_IPFORWARD_ROW2>> {
    let mut ptr = std::ptr::null_mut();
    // SAFETY: valid output pointer. The table is owned by RouteTable immediately on success.
    check(unsafe { GetIpForwardTable2(AF_INET, &mut ptr) })?;
    let table = RouteTable(ptr);
    // SAFETY: the API allocation contains NumEntries rows (Table is a flexible array).
    Ok(unsafe {
        std::slice::from_raw_parts(
            std::ptr::addr_of!((*table.0).Table).cast::<MIB_IPFORWARD_ROW2>(),
            (*table.0).NumEntries as usize,
        )
    }
    .to_vec())
}

fn address(ip: Ipv4Addr) -> SOCKADDR_INET {
    SOCKADDR_INET {
        Ipv4: SOCKADDR_IN {
            sin_family: AF_INET,
            sin_addr: IN_ADDR {
                S_un: IN_ADDR_0 {
                    S_addr: u32::from_ne_bytes(ip.octets()),
                },
            },
            ..Default::default()
        },
    }
}

fn ipv4_address(addr: &SOCKADDR_INET) -> Ipv4Addr {
    // SAFETY: callers only pass IPv4 rows from an AF_INET table.
    Ipv4Addr::from(unsafe { addr.Ipv4.sin_addr.S_un.S_addr }.to_ne_bytes())
}

fn matches(row: &MIB_IPFORWARD_ROW2, cidr: Cidr, index: Option<u32>) -> bool {
    row.DestinationPrefix.PrefixLength == cidr.bits
        && ipv4_address(&row.DestinationPrefix.Prefix) == cidr.address
        && index.is_none_or(|idx| idx == row.InterfaceIndex)
}

pub fn remove_route(cidr: Cidr, index: Option<u32>) -> Result<()> {
    let mut failure = None;
    for row in route_rows()?.iter().filter(|r| matches(r, cidr, index)) {
        // SAFETY: a fully initialized row copied from GetIpForwardTable2.
        if let Err(e) = check(unsafe { DeleteIpForwardEntry2(row) }) {
            failure = Some(e);
        }
    }
    failure.map_or(Ok(()), Err)
}

pub fn add_route(cidr: Cidr, index: u32, next_hop: Ipv4Addr) -> Result<()> {
    // Remove stale rows with the same destination/interface, regardless of old next hop.
    remove_route(cidr, Some(index))?;
    let mut row = MIB_IPFORWARD_ROW2::default();
    // SAFETY: writable, correctly sized row. API initializes default lifetimes and flags.
    unsafe {
        InitializeIpForwardEntry(&mut row);
    }
    row.InterfaceIndex = index;
    row.DestinationPrefix = IP_ADDRESS_PREFIX {
        Prefix: address(cidr.address),
        PrefixLength: cidr.bits,
    };
    row.NextHop = address(next_hop);
    row.Metric = 1;
    row.Protocol = MIB_IPPROTO_NETMGMT;
    // SAFETY: row has IPv4 prefix/next hop and an interface index; API routes are nonpersistent.
    check(unsafe { CreateIpForwardEntry2(&row) })
}

fn interface(index: u32) -> Result<MIB_IPINTERFACE_ROW> {
    let mut row = MIB_IPINTERFACE_ROW::default();
    // SAFETY: API receives a valid output structure with its required family/index set.
    unsafe {
        InitializeIpInterfaceEntry(&mut row);
    }
    row.Family = AF_INET;
    row.InterfaceIndex = index;
    // SAFETY: writable row identifies a single IPv4 interface.
    check(unsafe { GetIpInterfaceEntry(&mut row) })?;
    Ok(row)
}

pub fn physical_default(tunnel: &str, adapters: &[Adapter]) -> Result<Option<DefaultRoute>> {
    let mut defaults = Vec::new();
    for row in route_rows()?
        .iter()
        .filter(|r| r.DestinationPrefix.PrefixLength == 0)
    {
        let Some(adapter) = adapters.iter().find(|a| a.index == row.InterfaceIndex) else {
            continue;
        };
        if adapter.name.eq_ignore_ascii_case(tunnel) || ipv4_address(&row.NextHop).is_unspecified()
        {
            continue;
        }
        defaults.push(DefaultRoute {
            if_index: row.InterfaceIndex,
            next_hop: ipv4_address(&row.NextHop),
            route_metric: row.Metric,
            interface_metric: interface(row.InterfaceIndex)?.Metric,
            adapter_name: adapter.name.clone(),
        });
    }
    Ok(network::physical_default(&defaults, tunnel))
}

pub fn active_names(adapters: &[Adapter], tunnel: &str) -> Vec<String> {
    adapters
        .iter()
        .filter(|a| a.up && !a.name.eq_ignore_ascii_case(tunnel))
        .map(|a| a.name.clone())
        .collect()
}

pub fn bypass_present(edges: &[Ipv4Addr]) -> Result<bool> {
    let rows = route_rows()?;
    Ok(edges.iter().all(|ip| {
        rows.iter().any(|r| {
            matches(r, Cidr::host(*ip), None) && !ipv4_address(&r.NextHop).is_unspecified()
        })
    }))
}

fn ensure_address(index: u32, ip: Ipv4Addr) -> Result<()> {
    let mut row = MIB_UNICASTIPADDRESS_ROW::default();
    // SAFETY: initializes valid default values in a writable row.
    unsafe {
        InitializeUnicastIpAddressEntry(&mut row);
    }
    row.InterfaceIndex = index;
    row.Address = address(ip);
    // SAFETY: row identifies the requested address/interface; no system change on this query.
    if unsafe { GetUnicastIpAddressEntry(&mut row) }.is_ok() {
        return Ok(());
    }
    row.OnLinkPrefixLength = 30;
    row.PrefixOrigin = IpPrefixOriginManual;
    row.SuffixOrigin = IpSuffixOriginManual;
    // SAFETY: initialized IPv4 address row; this creates only an active unicast address.
    check(unsafe { CreateUnicastIpAddressEntry(&row) })
}

pub fn configure(adapter: &Adapter, ip: Ipv4Addr, dns: Ipv4Addr) -> Result<()> {
    ensure_address(adapter.index, ip)?;
    let mut row = interface(adapter.index)?;
    row.UseAutomaticMetric = false;
    row.Metric = 1;
    // Get can return an IPv4 SitePrefixLength which Set rejects. Preserve all other fields.
    row.SitePrefixLength = 0;
    // SAFETY: a queried row with only metric settings and the required IPv4 field changed.
    check(unsafe { SetIpInterfaceEntry(&mut row) })?;
    set_dns(adapter, dns)
}

fn set_dns(adapter: &Adapter, dns: Ipv4Addr) -> Result<()> {
    let mut guid = GUID::zeroed();
    // SAFETY: valid LUID copied from the adapter list and writable GUID output.
    check(unsafe { ConvertInterfaceLuidToGuid(&adapter.luid, &mut guid) })?;
    let mut server: Vec<u16> = dns.to_string().encode_utf16().chain(Some(0)).collect();
    let settings = DNS_INTERFACE_SETTINGS {
        Version: DNS_INTERFACE_SETTINGS_VERSION1,
        Flags: DNS_SETTING_NAMESERVER as u64,
        NameServer: PWSTR(server.as_mut_ptr()),
        ..Default::default()
    };
    // SAFETY: the terminated DNS string stays live for this synchronous call; unused fields are zero.
    check(unsafe { SetInterfaceDnsSettings(guid, &settings) })
}
