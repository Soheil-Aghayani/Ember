//! Golden files (`tests/golden/*.json`): inputs with the outputs the Kotlin code gives for them, worked out by reading
//! the Kotlin sources (ids with `hashlib`), never by running this crate. JSON is compared as values, so key order does
//! not matter (the Kotlin side used an unordered map too).

use std::collections::HashMap;
use std::path::PathBuf;

use base64::engine::general_purpose::{STANDARD, URL_SAFE_NO_PAD};
use base64::Engine;
use ember_subs::openvpn;
use ember_subs::subscription::{parse_subscription, parse_user_info};
use ember_subs::xray_config::{xray_config, xray_config_many, XraySettings};
use ember_subs::{outbound, parse_share_link};
use serde_json::Value;

macro_rules! golden {
    ($name:literal) => {
        serde_json::from_str::<Value>(include_str!(concat!("golden/", $name, ".json"))).expect($name)
    };
}

fn s(v: &Value) -> &str {
    v.as_str().unwrap()
}

#[test]
fn nodes() {
    let file = golden!("nodes");
    for case in file["cases"].as_array().unwrap() {
        let link = s(&case["link"]);
        let n = parse_share_link(link).unwrap_or_else(|e| panic!("{link}: {e}"));
        assert_eq!(n.id, s(&case["id"]), "id of {link}");
        assert_eq!(n.name, s(&case["name"]), "name of {link}");
        assert_eq!(n.protocol, s(&case["protocol"]), "protocol of {link}");
        assert_eq!(n.host, s(&case["host"]), "host of {link}");
        assert_eq!(i64::from(n.port), case["port"].as_i64().unwrap(), "port of {link}");
        assert_eq!(n.flag_code().as_deref(), case["flag"].as_str(), "flag of {link}");
        assert_eq!(n.title(), s(&case["title"]), "title of {link}");
        assert_eq!(n.plain_title(), s(&case["plain_title"]), "plain title of {link}");
        assert_eq!(n.is_info(), case["info"].as_bool().unwrap(), "info of {link}");
        assert_eq!(n.raw, link);
    }
    for link in file["bad_links"].as_array().unwrap() {
        assert!(parse_share_link(s(link)).is_err(), "{} should not parse", s(link));
    }
}

#[test]
fn outbounds() {
    for case in golden!("outbounds")["cases"].as_array().unwrap() {
        let name = s(&case["name"]);
        let link = if let Some(l) = case.get("link") {
            s(l).to_string()
        } else if let Some(j) = case.get("vmess_json") {
            format!("vmess://{}", STANDARD.encode(j.to_string()))
        } else {
            format!("ss://{}{}", URL_SAFE_NO_PAD.encode(s(&case["ss_userinfo"])), s(&case["ss_tail"]))
        };
        let got = outbound(&link, s(&case["address"]), s(&case["tag"])).unwrap_or_else(|e| panic!("{name}: {e}"));
        assert_eq!(got, case["expected"], "{name}");
        // whatever outbound() accepts must also parse as a node
        parse_share_link(&link).unwrap_or_else(|e| panic!("{name}: {e}"));
    }
}

#[test]
fn xray_configs() {
    let file = golden!("configs");
    for case in file["single"].as_array().unwrap() {
        let name = s(&case["name"]);
        let node = parse_share_link(s(&case["raw"])).unwrap();
        let settings = XraySettings {
            address: s(&case["address"]).into(),
            socks_port: case["socks_port"].as_u64().unwrap() as u16,
            http_port: case["http_port"].as_u64().map(|p| p as u16),
            log_file: PathBuf::from(s(&case["log_file"])),
            verbose: case["verbose"].as_bool().unwrap(),
            bypass: case["bypass"]
                .as_array()
                .unwrap()
                .iter()
                .map(|e| (s(&e[0]).to_string(), e[1].as_array().unwrap().iter().map(|i| s(i).to_string()).collect()))
                .collect(),
        };
        assert_eq!(xray_config(&node, &settings).unwrap(), case["expected"], "{name}");
    }
    for case in file["many"].as_array().unwrap() {
        let entries: Vec<_> = case["entries"]
            .as_array()
            .unwrap()
            .iter()
            .map(|e| (parse_share_link(s(&e[0])).unwrap(), s(&e[1]).to_string(), e[2].as_u64().unwrap() as u16))
            .collect();
        let got = xray_config_many(&entries, &PathBuf::from(s(&case["log_file"]))).unwrap();
        assert_eq!(got, case["expected"], "{}", s(&case["name"]));
    }
}

#[test]
fn openvpn_profiles() {
    let file = golden!("openvpn");
    let p = &file["prepare"];
    let files: HashMap<String, String> = p["files"].as_object().unwrap().iter().map(|(k, v)| (k.clone(), s(v).to_string())).collect();
    assert_eq!(openvpn::prepare(s(&p["input"]), |name| files.get(name).cloned()).unwrap(), s(&p["expected"]));

    let n = &file["node"];
    let node = openvpn::node(s(&n["input"]), s(&n["name_hint"])).unwrap();
    assert_eq!((node.name.as_str(), node.host.as_str(), i64::from(node.port)), (s(&n["name"]), s(&n["host"]), n["port"].as_i64().unwrap()));

    let r = &file["remotes"];
    let expected: Vec<&str> = r["expected"].as_array().unwrap().iter().map(s).collect();
    assert_eq!(openvpn::remotes(s(&r["profile"])), expected);
}

#[test]
fn subscriptions() {
    let file = golden!("subscriptions");
    for case in file["cases"].as_array().unwrap() {
        let name = s(&case["name"]);
        let hint = case.get("name_hint").map_or("", s);
        let nodes = parse_subscription(s(&case["body"]), hint).unwrap_or_else(|e| panic!("{name}: {e}"));
        let got: Vec<(String, String, i64)> = nodes.iter().map(|n| (n.name.clone(), n.host.clone(), i64::from(n.port))).collect();
        let want: Vec<(String, String, i64)> = case["expected"]
            .as_array()
            .unwrap()
            .iter()
            .map(|e| (s(&e["name"]).to_string(), s(&e["host"]).to_string(), e["port"].as_i64().unwrap()))
            .collect();
        assert_eq!(got, want, "{name}");
        // every node can be rebuilt from its stored form with the same id
        for n in &nodes {
            let back = parse_share_link(&n.raw).unwrap_or_else(|e| panic!("{name}: {e}"));
            assert_eq!((&back.id, &back.name), (&n.id, &n.name), "{name}");
        }
    }
    for case in file["errors"].as_array().unwrap() {
        let err = parse_subscription(s(&case["body"]), "").unwrap_err();
        assert_eq!(err.to_string(), s(&case["message"]), "{:?}", s(&case["body"]));
    }
    for case in file["user_info"].as_array().unwrap() {
        let header = s(&case["header"]);
        let got = parse_user_info(Some(header));
        match case["expected"].as_object() {
            None => assert!(got.is_none(), "{header}"),
            Some(e) => {
                let got = got.unwrap_or_else(|| panic!("{header}"));
                assert_eq!(got.uploaded, e["uploaded"].as_i64().unwrap(), "{header}");
                assert_eq!(got.downloaded, e["downloaded"].as_i64().unwrap(), "{header}");
                assert_eq!(got.total, e["total"].as_i64().unwrap(), "{header}");
                assert_eq!(got.expires_at_epoch_seconds, e["expire"].as_i64(), "{header}");
            }
        }
    }
}
