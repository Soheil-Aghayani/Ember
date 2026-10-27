use std::net::Ipv4Addr;

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct DefaultRoute {
    pub if_index: u32,
    pub next_hop: Ipv4Addr,
    pub route_metric: u32,
    pub interface_metric: u32,
    pub adapter_name: String,
}

pub fn physical_default(routes: &[DefaultRoute], tunnel: &str) -> Option<DefaultRoute> {
    routes
        .iter()
        .filter(|r| {
            r.next_hop != Ipv4Addr::UNSPECIFIED && !r.adapter_name.eq_ignore_ascii_case(tunnel)
        })
        .min_by_key(|r| u64::from(r.route_metric) + u64::from(r.interface_metric))
        .cloned()
}

pub fn signature(
    default: Option<&DefaultRoute>,
    active_names: &[String],
    tunnel: &str,
) -> Option<String> {
    let route = default?;
    let mut names: Vec<_> = active_names
        .iter()
        .filter(|n| !n.eq_ignore_ascii_case(tunnel))
        .cloned()
        .collect();
    names.sort_by_key(|n| n.to_lowercase());
    Some(format!(
        "{}|{}|{}",
        route.if_index,
        route.next_hop,
        names.join(",")
    ))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn route(index: u32, route_metric: u32, interface_metric: u32, name: &str) -> DefaultRoute {
        DefaultRoute {
            if_index: index,
            next_hop: Ipv4Addr::new(192, 168, 1, 1),
            route_metric,
            interface_metric,
            adapter_name: name.into(),
        }
    }

    #[test]
    fn default_uses_combined_metric_and_excludes_tunnel_and_onlink() {
        let mut onlink = route(4, 0, 0, "onlink");
        onlink.next_hop = Ipv4Addr::UNSPECIFIED;
        let routes = [
            route(1, 1, 100, "Wi-Fi"),
            route(2, 10, 5, "Ethernet"),
            route(3, 0, 0, "Ember"),
            onlink,
        ];
        assert_eq!(physical_default(&routes, "ember").unwrap().if_index, 2);
        assert!(physical_default(&[], "Ember").is_none());
    }

    #[test]
    fn signature_is_order_independent_but_tracks_network_changes() {
        let def = route(2, 10, 5, "Ethernet");
        assert_eq!(
            signature(
                Some(&def),
                &["Wi-Fi".into(), "Ember".into(), "Ethernet".into()],
                "Ember"
            )
            .unwrap(),
            "2|192.168.1.1|Ethernet,Wi-Fi"
        );
        assert_eq!(
            signature(Some(&def), &["Wi-Fi".into(), "Ethernet".into()], "Ember"),
            signature(Some(&def), &["Ethernet".into(), "Wi-Fi".into()], "Ember")
        );
        assert_ne!(
            signature(Some(&def), &[], "Ember"),
            signature(Some(&route(3, 10, 5, "Ethernet")), &[], "Ember")
        );
        assert!(signature(None, &[], "Ember").is_none());
    }
}
