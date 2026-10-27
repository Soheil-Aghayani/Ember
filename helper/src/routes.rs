use crate::{config, ip::Cidr, Result};
use serde::{Deserialize, Serialize};
use serde_json::Value;
use std::{fs, path::Path};

#[derive(Clone, Debug, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct RouteRecord {
    pub prefix: String,
    pub if_index: u32,
    pub kind: String,
}

pub fn parse(value: Value, mut log: impl FnMut(&str)) -> Vec<RouteRecord> {
    let entries = match value {
        Value::Array(v) => v,
        v => vec![v],
    };
    entries
        .into_iter()
        .filter_map(|v| {
            // Cleanup only needs prefix/index. Older journals may omit kind.
            let prefix = v.get("prefix").and_then(Value::as_str);
            let index = v
                .get("ifIndex")
                .and_then(Value::as_u64)
                .and_then(|n| u32::try_from(n).ok());
            match (prefix.and_then(|p| Cidr::parse(p, 0, false)), index) {
                (Some(cidr), Some(if_index)) => Some(RouteRecord {
                    prefix: cidr.to_string(),
                    if_index,
                    kind: v
                        .get("kind")
                        .and_then(Value::as_str)
                        .unwrap_or("")
                        .to_owned(),
                }),
                _ => {
                    log("cleanup (route entry): invalid prefix or interface index");
                    None
                }
            }
        })
        .collect()
}

pub fn load(path: &Path, log: impl FnMut(&str)) -> Result<Vec<RouteRecord>> {
    Ok(parse(config::read(path)?, log))
}

pub fn save(path: &Path, routes: &[RouteRecord]) -> Result<()> {
    let json = serde_json::to_vec_pretty(routes).map_err(|e| e.to_string())?;
    fs::write(path, json).map_err(|e| e.to_string())
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn powershell_journal_compatibility() {
        let value = json!([{"prefix":"23.235.42.11/32", "ifIndex":12, "kind":"bypass"}, {"prefix":"128.0.0.0/1", "ifIndex":22, "kind":"tun"}]);
        let records = parse(value.clone(), |_| panic!());
        assert_eq!(serde_json::to_value(&records).unwrap(), value);
        assert_eq!(
            serde_json::to_string(&Vec::<RouteRecord>::new()).unwrap(),
            "[]"
        );
        assert!(serde_json::to_string(&records[..1])
            .unwrap()
            .starts_with('['));
    }

    #[test]
    fn bad_entries_do_not_prevent_other_cleanup() {
        let mut logs = Vec::new();
        let records = parse(
            json!([{"prefix":"1.2.3.4/32", "ifIndex":2}, {"prefix":"::/0", "ifIndex":2}, {"prefix":"0.0.0.0/1", "ifIndex":-1}, null]),
            |s| logs.push(s.to_owned()),
        );
        assert_eq!(records.len(), 1);
        assert_eq!(logs.len(), 3);
    }
}
