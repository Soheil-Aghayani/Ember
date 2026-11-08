//! Every case of `desktop/src/test/kotlin/ember/subs/ShareLinksTest.kt`, in the same order. Where the Kotlin test relied
//! on a Java platform class (X25519, ImageIO) the check is made against the standard instead (see the unit tests in
//! `warp.rs` for the RFC 7748 vectors).

use std::collections::{HashMap, HashSet};
use std::path::Path;

use base64::engine::general_purpose::{STANDARD, URL_SAFE_NO_PAD};
use base64::Engine;
use ember_subs::config_files::wireguard;
use ember_subs::openvpn;
use ember_subs::qr::qr_matrix;
use ember_subs::sort::{pick_failover, sort_nodes};
use ember_subs::subscription::{parse_subscription, parse_title, parse_user_info, NodeResult};
use ember_subs::tester::{normalize_site_url, SiteResult};
use ember_subs::warp::{configs, new_key_pair, parse_registration};
use ember_subs::xray_config::{xray_config, xray_config_many, XraySettings};
use ember_subs::{outbound, outbounds, parse_share_link, Node, ParseError, JSON_PREFIX};
use serde_json::{json, Value};

const UUID: &str = "11111111-2222-3333-4444-555555555555";

fn node(link: &str) -> Node {
    parse_share_link(link).unwrap_or_else(|e| panic!("{e}"))
}

fn xray_settings(address: &str, bypass: Vec<(String, Vec<String>)>) -> XraySettings {
    XraySettings {
        address: address.into(),
        socks_port: 10808,
        http_port: None,
        log_file: Path::new("x.log").into(),
        verbose: false,
        bypass,
    }
}

#[test]
fn reads_vless_reality() {
    let n = node(&format!("vless://{UUID}@vl.example.com:443?type=tcp&security=reality&pbk=KEY&sni=www.example.org&sid=ab&flow=xtls-rprx-vision#UK%20Reality"));
    assert_eq!(n.protocol, "vless");
    assert_eq!(n.host, "vl.example.com");
    assert_eq!(n.port, 443);
    assert_eq!(n.name, "UK Reality");
    let o = outbound(&n.raw, "203.0.113.7", "proxy").unwrap();
    assert_eq!(o["settings"]["vnext"][0]["address"], "203.0.113.7");
    assert_eq!(o["streamSettings"]["realitySettings"]["serverName"], "www.example.org");
    assert_eq!(o["streamSettings"]["realitySettings"]["publicKey"], "KEY");
}

#[test]
fn reads_vmess_trojan_and_shadowsocks() {
    let vmess = format!(r#"{{"v":"2","ps":"Vm","add":"vm.example.com","port":"443","id":"{UUID}","aid":"0","net":"ws","host":"vm.example.com","path":"/w","tls":"tls"}}"#);
    assert_eq!(node(&format!("vmess://{}", STANDARD.encode(vmess))).protocol, "vmess");
    assert_eq!(node("trojan://pw@tr.example.com:443?security=tls#T").protocol, "trojan");
    let ss = format!("ss://{}@1.2.3.4:8388#S", URL_SAFE_NO_PAD.encode("aes-256-gcm:pw"));
    let n = node(&ss);
    assert_eq!(n.host, "1.2.3.4");
    let o = outbound(&ss, "1.2.3.4", "p").unwrap();
    assert_eq!(o["settings"]["servers"][0]["method"], "aes-256-gcm");
}

#[test]
fn ignores_garbage_and_marks_information_rows() {
    assert!(parse_share_link("hello").is_err());
    assert!(parse_share_link("vless://broken").is_err());
    assert!(node(&format!("vless://{UUID}@0.0.0.0:1?type=tcp#99%20GB%20left")).is_info());
}

#[test]
fn takes_the_flag_from_the_name_and_drops_it_from_the_title() {
    let n = node(&format!("vless://{UUID}@h.example.com:443?type=tcp#%F0%9F%87%A9%F0%9F%87%AA%20Germany%201"));
    assert_eq!(n.flag_code().as_deref(), Some("de"));
    assert_eq!(n.title(), "Germany 1");
    assert_eq!(node(&format!("vless://{UUID}@h.example.com:443?type=tcp#Plain")).flag_code(), None);
}

#[test]
fn uses_the_last_leading_flag_when_there_are_two() {
    let n = node(&format!("vless://{UUID}@h.example.com:443?type=tcp#%F0%9F%87%AE%F0%9F%87%B7%20%F0%9F%87%AB%F0%9F%87%AE%202.5x"));
    assert_eq!(n.flag_code().as_deref(), Some("fi"));
    assert_eq!(n.title(), "2.5x");
}

#[test]
fn recognises_usage_messages_and_strips_emoji_for_plain_titles() {
    let info = node(&format!("vless://{UUID}@real.example.com:8080?type=tcp#%F0%9F%93%8A96.37%20GB%20%E2%8F%B3%E2%88%9E%20%D8%B1%D9%88%D8%B2%20%D8%A7%D8%B9%D8%AA%D8%A8%D8%A7%D8%B1"));
    assert!(info.is_info());
    assert!(!node(&format!("vless://{UUID}@real.example.com:8080?type=tcp#Finland%202")).is_info());
    let flagged = node(&format!("vless://{UUID}@h.example.com:443?type=tcp#%D8%A7%D8%B6%D8%B7%D8%B1%D8%A7%D8%B1%DB%8C%202.5x%20%F0%9F%87%AB%F0%9F%87%AE"));
    assert_eq!(flagged.plain_title(), "اضطراری 2.5x");
}

#[test]
fn reads_plain_and_base64_subscriptions() {
    let links = [format!("vless://{UUID}@a.example.com:443?type=tcp#A"), "trojan://pw@b.example.com:443?security=tls#B".to_string()];
    let joined = links.join("\n");
    assert_eq!(parse_subscription(&joined, "").unwrap().len(), 2);
    assert_eq!(parse_subscription(&STANDARD.encode(&joined), "").unwrap().len(), 2);
    assert_eq!(parse_subscription("not a subscription", ""), Err(ParseError::NoServers));
}

#[test]
fn reads_usage_and_title_headers() {
    let usage = parse_user_info(Some("upload=1; download=2; total=100; expire=1893456000")).unwrap();
    assert_eq!(usage.used(), 3);
    assert_eq!(usage.total, 100);
    assert_eq!(usage.expires_at_epoch_seconds, Some(1_893_456_000));
    assert_eq!(parse_user_info(None), None);
    assert_eq!(parse_title(Some(&format!("base64:{}", STANDARD.encode("My VPN")))).as_deref(), Some("My VPN"));
}

#[test]
fn outbound_json_is_valid_json() {
    let n = node(&format!("vless://{UUID}@h.example.com:443?type=ws&security=tls&path=%2Fp&host=h.example.com#W"));
    let text = outbound(&n.raw, "203.0.113.7", "proxy").unwrap().to_string();
    serde_json::from_str::<Value>(&text).unwrap();
}

#[test]
fn reads_hysteria2_and_skips_obfuscated_ones() {
    let n = node("hysteria2://secret@hy.example.com:443?sni=hy.example.com&insecure=1#Hy");
    assert_eq!(n.protocol, "hysteria2");
    let o = outbound(&n.raw, "203.0.113.7", "proxy").unwrap();
    assert_eq!(o["protocol"], "hysteria");
    assert_eq!(o["streamSettings"]["hysteriaSettings"]["auth"], "secret");
    assert!(parse_share_link("hysteria2://secret@hy.example.com:443?obfs=salamander&obfs-password=x#Hy").is_err());
    assert_eq!(node("hy2://secret@hy.example.com:443#Short").protocol, "hysteria2");
}

#[test]
fn reads_an_xray_json_subscription() {
    let json = format!(r#"[{{"remarks":"JSON One","outbounds":[{{"protocol":"vless","settings":{{"vnext":[{{"address":"j.example.com","port":443,"users":[{{"id":"{UUID}","encryption":"none"}}]}}]}},"streamSettings":{{"network":"tcp","security":"tls"}}}},{{"protocol":"freedom","tag":"direct"}}]}}]"#);
    let nodes = parse_subscription(&json, "").unwrap();
    assert_eq!(nodes.len(), 1);
    assert_eq!(nodes[0].name, "JSON One");
    assert_eq!(nodes[0].host, "j.example.com");
    let o = outbound(&nodes[0].raw, "203.0.113.7", "proxy").unwrap();
    assert_eq!(o["settings"]["vnext"][0]["address"], "203.0.113.7");
    assert_eq!(o["tag"], "proxy");
    // a node read back from storage is the same node
    assert_eq!(nodes[0].id, node(&nodes[0].raw).id);
}

fn results(entries: &[(&Node, Option<i32>, Option<f64>)]) -> HashMap<String, NodeResult> {
    entries.iter().map(|(n, ping, mbps)| (n.id.clone(), NodeResult { ping_ms: *ping, mbps: *mbps, tested_at_ms: 0 })).collect()
}

fn names(nodes: &[Node]) -> Vec<&str> {
    nodes.iter().map(|n| n.name.as_str()).collect()
}

#[test]
fn sorts_favourites_then_speed() {
    let [a, b, c] = ["A", "B", "C"].map(|n| node(&format!("vless://{UUID}@{}.example.com:443?type=tcp#{n}", n.to_lowercase())));
    let r = results(&[(&a, Some(50), Some(10.0)), (&b, Some(60), Some(90.0)), (&c, Some(20), None)]);
    let all = [a.clone(), b.clone(), c.clone()];
    assert_eq!(names(&sort_nodes(&all, &r, &HashSet::new())), ["B", "A", "C"]);
    assert_eq!(names(&sort_nodes(&all, &r, &HashSet::from([a.id.clone()]))), ["A", "B", "C"]);
}

const WIREGUARD_FILE: &str = "[Interface]\nPrivateKey = yAnz5TF+lXXJte14tji3zlMNq+hd2rYUIgJBgB3fBmk=\nAddress = 10.8.0.2/32, fd00::2/128\nDNS = 1.1.1.1\nMTU = 1280\n\n[Peer]\nPublicKey = xTIBA5rboUvnH4htodjb6e697QjLERt1NAB4mZqp8Dg=\nAllowedIPs = 0.0.0.0/0, ::/0\nEndpoint = wg.example.com:51820\nPersistentKeepalive = 25";

#[test]
fn reads_a_wireguard_file_and_pins_its_endpoint() {
    let nodes = parse_subscription(WIREGUARD_FILE, "Office").unwrap();
    assert_eq!(nodes.len(), 1);
    let n = &nodes[0];
    assert_eq!((n.protocol.as_str(), n.host.as_str(), n.port, n.name.as_str()), ("wireguard", "wg.example.com", 51820, "Office"));
    let o = outbound(&n.raw, "203.0.113.9", "proxy").unwrap();
    let settings = &o["settings"];
    assert_eq!(settings["peers"][0]["endpoint"], "203.0.113.9:51820");
    // the tunnel's own addresses are left alone
    assert_eq!(settings["address"][0], "10.8.0.2/32");
    assert_eq!(settings["mtu"], 1280);
    assert_eq!(settings["peers"][0]["keepAlive"], 25);
}

#[test]
fn explains_files_it_cannot_use() {
    let no_peer = "[Interface]\nPrivateKey = x\nAddress = 10.0.0.2/32\n";
    assert_eq!(parse_subscription(no_peer, "").unwrap_err().to_string(), "The WireGuard file has no [Peer] section.");
}

const OVPN: &str = "client\ndev tun\nproto udp\nremote vpn.example.com 443 udp\nauth-user-pass creds.txt\nca ca.crt\ntls-auth ta.key 1\nscript-security 2\nup /bin/evil.cmd\nplugin evil.dll\n<cert>\nup not-a-directive-inside-a-block\n</cert>";

#[test]
fn reads_an_openvpn_profile_and_removes_what_runs_programs() {
    let files = HashMap::from([("ca.crt", "CA-PEM"), ("ta.key", "TA-KEY")]);
    let profile = openvpn::prepare(OVPN, |name| files.get(name).map(|s| s.to_string())).unwrap();
    assert!(profile.contains("<ca>\nCA-PEM\n</ca>"));
    assert!(profile.contains("<tls-auth>\nTA-KEY\n</tls-auth>"));
    assert!(profile.contains("key-direction 1"));
    // credentials are asked for, never read from a file next to the profile
    assert!(profile.contains("auth-user-pass\n") && !profile.contains("creds.txt"));
    assert!(openvpn::needs_credentials(&profile));
    for removed in ["script-security 2", "/bin/evil.cmd", "evil.dll"] {
        assert!(!profile.contains(removed), "{removed}");
    }
    // text inside a certificate block is left alone
    assert!(profile.contains("up not-a-directive-inside-a-block"));
}

#[test]
fn an_openvpn_server_keeps_its_name_and_id_when_stored_and_read_back() {
    let prepared = openvpn::prepare(OVPN, |_| Some("X".into())).unwrap();
    let n = openvpn::node(&prepared, "Work").unwrap();
    assert_eq!((n.protocol.as_str(), n.host.as_str(), n.port, n.name.as_str()), ("openvpn", "vpn.example.com", 443, "Work"));
    let back = node(&n.raw);
    assert_eq!(n.id, back.id);
    assert_eq!(back.name, "Work");
    assert!(openvpn::decode(&n.raw).unwrap().1.contains("remote vpn.example.com 443 udp"));
}

#[test]
fn an_openvpn_profile_can_be_pinned_to_the_addresses_the_kill_switch_allows() {
    let profile = openvpn::prepare("client\ndev tun\nremote vpn.example.com 443 udp\nremote 198.51.100.4 1194\n<ca>\nremote not-a-line\n</ca>\n", |_| None).unwrap();
    assert_eq!(openvpn::remotes(&profile), ["vpn.example.com", "198.51.100.4"]);
    let pinned = openvpn::pin_remotes(&profile, &HashMap::from([("vpn.example.com".to_string(), "203.0.113.9".to_string())]));
    assert!(pinned.contains("remote 203.0.113.9 443 udp"));
    assert!(pinned.contains("remote 198.51.100.4 1194"));
    assert!(pinned.contains("remote not-a-line")); // text inside a certificate block is untouched
}

#[test]
fn an_openvpn_profile_needs_its_files_and_cannot_use_p12() {
    assert!(parse_subscription(OVPN, "").unwrap_err().to_string().contains("ca.crt"));
    assert!(openvpn::prepare("pkcs12 x.p12", |_| None).unwrap_err().to_string().contains(".p12"));
    assert!(openvpn::node("client\ndev tun\n", "").unwrap_err().to_string().contains("remote"));
}

#[test]
fn a_json_server_keeps_its_name_after_a_restart() {
    let json = format!(r#"[{{"remarks":"Berlin","outbounds":[{{"protocol":"vless","settings":{{"vnext":[{{"address":"b.example.com","port":443,"users":[{{"id":"{UUID}","encryption":"none"}}]}}]}}}}]}}]"#);
    let n = parse_subscription(&json, "").unwrap().remove(0);
    let back = node(&n.raw);
    assert_eq!(back.name, "Berlin");
    assert_eq!(n.id, back.id);
    assert!(outbound(&n.raw, "203.0.113.7", "proxy").unwrap().get("ember_name").is_none());
    assert!(n.raw.starts_with(JSON_PREFIX));
}

#[test]
fn a_site_refusing_the_server_counts_as_not_working() {
    let ok = |code| SiteResult { code, ms: Some(1) }.ok();
    assert!(ok(Some(200)));
    assert!(ok(Some(401))); // an API that wants a key still answered
    assert!(!ok(Some(403)));
    assert!(!ok(Some(451)));
    assert!(!ok(Some(502)));
    assert!(!ok(None));
}

#[test]
fn reads_a_website_the_user_typed() {
    assert_eq!(normalize_site_url("open.spotify.com").as_deref(), Some("https://open.spotify.com"));
    assert_eq!(normalize_site_url("  https://www.youtube.com/ ").as_deref(), Some("https://www.youtube.com/"));
    assert_eq!(normalize_site_url(""), None);
    assert_eq!(normalize_site_url("localhost"), None);
    assert_eq!(normalize_site_url("ftp://example.com"), None);
}

const WARP_ANSWER: &str = r#"{"id":"abc","token":"tok","config":{"client_id":"AQID",
 "peers":[{"public_key":"bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo=","endpoint":{"host":"engage.cloudflareclient.com:2408","v4":"162.159.192.1:0"}}],
 "interface":{"addresses":{"v4":"172.16.0.2","v6":"2606:4700:110:8a36::2"}}}}"#;

#[test]
fn turns_a_cloudflare_warp_registration_into_wireguard_servers() {
    let (private, public) = new_key_pair().unwrap();
    assert_eq!(private.len(), 44);
    assert_eq!(public.len(), 44);
    let registration = parse_registration(WARP_ANSWER, &private).unwrap();
    assert_eq!(registration.reserved, [1, 2, 3]);
    assert_eq!(registration.addresses, ["172.16.0.2/32", "2606:4700:110:8a36::2/128"]);
    let nodes: Vec<Node> = configs(&registration).iter().map(|(name, text)| wireguard(text, name).unwrap()).collect();
    assert_eq!(names(&nodes), ["Cloudflare WARP", "Cloudflare WARP (500)", "Cloudflare WARP (4500)"]);
    assert_eq!(nodes.iter().map(|n| n.port).collect::<Vec<_>>(), [2408, 500, 4500]);
    let o = outbound(&nodes[1].raw, "162.159.192.1", "proxy").unwrap();
    assert_eq!(o["settings"]["peers"][0]["endpoint"], "162.159.192.1:500");
    assert_eq!(o["settings"]["reserved"], json!([1, 2, 3]));
    // and the node survives being saved and read back
    assert_eq!(nodes[1].id, node(&nodes[1].raw).id);
}

#[test]
fn fails_over_to_the_best_server_that_has_not_just_failed() {
    let [a, b, c, d] = ["A", "B", "C", "D"].map(|n| node(&format!("vless://{UUID}@{}.example.com:443?type=tcp#{n}", n.to_lowercase())));
    let all = [a.clone(), b.clone(), c.clone(), d.clone()];
    let r = results(&[(&a, Some(50), Some(10.0)), (&b, Some(60), Some(90.0)), (&c, None, None), (&d, Some(70), Some(40.0))]);
    let none = HashSet::new();
    let name = |n: Option<Node>| n.map(|n| n.name);
    // the fastest other server, never the current one and never one a test found dead
    assert_eq!(name(pick_failover(&all, &a, &none, &r, &none)).as_deref(), Some("B"));
    assert_eq!(name(pick_failover(&all, &a, &HashSet::from([b.id.clone()]), &r, &none)).as_deref(), Some("D"));
    assert_eq!(name(pick_failover(&all, &a, &HashSet::from([b.id.clone(), d.id.clone()]), &r, &none)), None);
    // with nothing tested, servers never tested are tried
    assert_eq!(name(pick_failover(&all, &a, &none, &HashMap::new(), &HashSet::from([b.id.clone()]))).as_deref(), Some("B"));
}

#[test]
fn a_server_link_survives_being_drawn_as_a_qr_code() {
    let link = format!("vless://{UUID}@vl.example.com:443?type=tcp&security=reality&pbk=KEY&sni=www.example.org&sid=ab&flow=xtls-rprx-vision#UK%20Reality");
    let n = node(&link);
    assert_eq!(n.share_link(), Some(link.as_str()));
    let qr = qr_matrix(n.share_link().unwrap()).unwrap();
    assert!(qr.size >= 21 && (qr.size - 17).is_multiple_of(4));
    assert_eq!(qr.dark.len(), qr.size * qr.size);
    // the finder pattern in the top-left corner: a dark ring, a light ring, a dark centre
    assert!(qr.is_dark(0, 0) && qr.is_dark(6, 6) && !qr.is_dark(1, 1) && qr.is_dark(3, 3));
    // servers that came from files have no link to share
    assert_eq!(parse_subscription(WIREGUARD_FILE, "Office").unwrap()[0].share_link(), None);
}

#[test]
fn warp_carried_through_a_server_becomes_two_linked_outbounds() {
    let carrier = node(&format!("vless://{UUID}@vl.example.com:443?type=tcp#🇩🇪 Germany"));
    let registration = parse_registration(WARP_ANSWER, &new_key_pair().unwrap().0).unwrap();
    let wg = wireguard(&configs(&registration)[0].1, "x").unwrap();
    let warp: Value = serde_json::from_str(wg.raw.strip_prefix(JSON_PREFIX).unwrap()).unwrap();
    let chain = ember_subs::warp_chain::create(&carrier, &warp).unwrap();
    assert_eq!(chain.protocol, "warp");
    assert_eq!(chain.host, "vl.example.com"); // the network sees the carrier, not Cloudflare
    assert_eq!(chain.port, 443);
    assert_eq!(chain.name, "🇩🇪 WARP via Germany");
    assert_eq!(chain.flag_code().as_deref(), Some("de"));
    let back = node(&chain.raw);
    assert_eq!(chain.id, back.id);
    assert_eq!(chain.name, back.name);
    let obs = outbounds(&chain.raw, "203.0.113.7", "out0").unwrap();
    assert_eq!(obs.iter().map(|o| o["protocol"].as_str().unwrap()).collect::<Vec<_>>(), ["wireguard", "vless"]);
    assert_eq!(obs[0]["streamSettings"]["sockopt"]["dialerProxy"], "out0-carrier");
    assert_eq!(obs[1]["tag"], "out0-carrier");
    assert_eq!(obs[0]["settings"]["peers"][0]["endpoint"], "162.159.192.1:2408");
    assert_eq!(outbounds(&carrier.raw, "203.0.113.7", "out1").unwrap().len(), 1);
    // both outbounds reach Xray's configuration, the first one being the one traffic is routed to
    let config = xray_config_many(&[(chain, "203.0.113.7".into(), 21001)], Path::new("x.log")).unwrap();
    let tags: Vec<&str> = config["outbounds"].as_array().unwrap().iter().map(|o| o["tag"].as_str().unwrap()).collect();
    assert_eq!(tags, ["out0", "out0-carrier"]);
}

#[test]
fn reads_a_bare_xray_outbound() {
    let json = format!(r#"{{"protocol":"vless","tag":"mine","settings":{{"vnext":[{{"address":"b.example.com","port":443,"users":[{{"id":"{UUID}","encryption":"none"}}]}}]}}}}"#);
    let n = parse_subscription(&json, "").unwrap().remove(0);
    assert_eq!(n.host, "b.example.com");
    assert_eq!(n.name, "mine");
}

#[test]
fn skipped_hostnames_go_direct_to_the_addresses_looked_up_for_them() {
    let n = node(&format!("vless://{UUID}@h.example.com:443?type=tcp#N"));
    let config = xray_config(&n, &xray_settings("203.0.113.7", vec![("open.spotify.com".into(), vec!["1.2.3.4".into()])])).unwrap();
    let outbounds = config["outbounds"].as_array().unwrap();
    assert!(outbounds.iter().any(|o| o["tag"] == "direct"));
    assert_eq!(config["dns"]["hosts"]["full:open.spotify.com"][0], "1.2.3.4");
    let first = &config["routing"]["rules"][0];
    assert_eq!(first["domain"][0], "full:open.spotify.com");
    assert_eq!(first["outboundTag"], "direct");
    // without a skip list there is no direct outbound and no dns section
    let plain = xray_config(&n, &xray_settings("203.0.113.7", vec![])).unwrap();
    assert!(plain.get("dns").is_none());
}

// ---- cases beyond the Kotlin tests ------------------------------------------------------------------------------

#[test]
fn debug_output_never_contains_the_link() {
    let n = node("vless://secret-key@h.example.com:443?type=tcp#N");
    let text = format!("{n:?}");
    assert!(!text.contains("secret-key") && text.contains("h.example.com"));
}
