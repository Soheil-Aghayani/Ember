mod firewall;
mod net;
mod process;

use ember_helper::{
    config::{self, StateConfig, UpConfig},
    ip::{self, Cidr},
    network::{self, DefaultRoute},
    routes::{self, RouteRecord},
    Result,
};
use firewall::Firewall;
use serde_json::Value;
use std::{
    fs::{self, OpenOptions},
    io::Write,
    path::{Path, PathBuf},
    process::Child,
    thread::sleep,
    time::{Duration, Instant},
};

struct State {
    dir: PathBuf,
}

impl State {
    fn log(&self, message: &str) {
        if let Ok(mut file) = OpenOptions::new()
            .create(true)
            .append(true)
            .open(self.dir.join("helper.log"))
        {
            // Keep each event on one line even if an invalid config contains a newline.
            let message = message.replace(['\r', '\n'], " ");
            let _ = writeln!(file, "{} {message}", process::timestamp());
        }
    }

    fn status(&self, message: &str) -> Result<()> {
        fs::write(
            self.dir.join("status"),
            format!("{}\n", message.replace(['\r', '\n'], " ")),
        )
        .map_err(|e| e.to_string())
    }

    fn elapsed(&self, step: &str, start: Instant) {
        self.log(&format!("{step} ({} ms)", start.elapsed().as_millis()));
    }

    fn remove(&self, name: &str) {
        if let Err(e) = fs::remove_file(self.dir.join(name)) {
            if e.kind() != std::io::ErrorKind::NotFound {
                self.log(&format!("remove {name}: {e}"));
            }
        }
    }

    fn cleanup(&self, firewall: Option<&Firewall>, added: &[RouteRecord]) {
        let start = Instant::now();
        let path = self.dir.join("routes.json");
        let mut records = added.to_vec();
        if path.exists() {
            match routes::load(&path, |s| self.log(s)) {
                Ok(on_disk) => records.extend(on_disk),
                Err(e) => self.log(&format!("cleanup (routes): {e}")),
            }
        }
        for record in records {
            if let Some(cidr) = Cidr::parse(&record.prefix, 0, false) {
                if let Err(e) = net::remove_route(cidr, Some(record.if_index)) {
                    self.log(&format!("cleanup (route entry): {e}"));
                }
            }
        }
        self.remove("routes.json");
        let remove_rules = |fw: &Firewall| {
            if let Err(e) = fw.remove_ember(|s| self.log(s)) {
                self.log(&format!("cleanup (firewall): {e}"));
            }
        };
        if let Some(fw) = firewall {
            remove_rules(fw);
        } else {
            match Firewall::new() {
                Ok(fw) => remove_rules(&fw),
                Err(e) => self.log(&format!("cleanup (firewall): {e}")),
            }
        }
        process::kill_tunnels(|s| self.log(s));
        self.elapsed("cleanup complete", start);
    }
}

struct Session<'a> {
    state: &'a State,
    config: UpConfig,
    firewall: Firewall,
    added: Vec<RouteRecord>,
}

impl Session<'_> {
    fn add_route(
        &mut self,
        cidr: Cidr,
        index: u32,
        next_hop: std::net::Ipv4Addr,
        kind: &str,
    ) -> Result<()> {
        net::add_route(cidr, index, next_hop)?;
        self.added.push(RouteRecord {
            prefix: cidr.to_string(),
            if_index: index,
            kind: kind.into(),
        });
        // Keep the in-memory entry even if writing fails, so error cleanup still removes it.
        routes::save(&self.state.dir.join("routes.json"), &self.added)
    }

    fn bypass(&mut self, default: &DefaultRoute) -> Result<()> {
        let start = Instant::now();
        let old: Vec<_> = self
            .added
            .iter()
            .filter(|r| r.kind == "bypass")
            .cloned()
            .collect();
        for record in old {
            if let Some(cidr) = Cidr::parse(&record.prefix, 0, false) {
                net::remove_route(cidr, Some(record.if_index))?;
            }
        }
        self.added.retain(|r| r.kind != "bypass");
        routes::save(&self.state.dir.join("routes.json"), &self.added)?;
        let prefixes: Vec<_> = self
            .config
            .edge_ips
            .iter()
            .copied()
            .map(Cidr::host)
            .chain(self.config.exclusions.iter().copied())
            .collect();
        for cidr in prefixes {
            self.add_route(cidr, default.if_index, default.next_hop, "bypass")?;
        }
        self.state.elapsed("bypass routes set", start);
        Ok(())
    }

    fn kill_switch(&self, adapters: &[net::Adapter]) -> Result<()> {
        if !self.config.kill_switch {
            return Ok(());
        }
        let start = Instant::now();
        let names = net::active_names(adapters, &self.config.adapter_name);
        if names.is_empty() {
            return Err("no active network adapter was found for the kill switch".into());
        }
        let ranges = ip::blocked_ranges(&self.config.edge_ips, &self.config.exclusions);
        self.firewall.block(
            "Ember kill switch",
            &ranges.join(","),
            256,
            None,
            Some(&names),
        )?;
        self.state.log(&format!(
            "kill switch rule set on: {} ({} blocked ranges)",
            names.join(", "),
            ranges.len()
        ));
        self.state.elapsed("kill switch set", start);
        Ok(())
    }

    fn refresh(&mut self) -> Result<Option<String>> {
        let adapters = net::adapters()?;
        let default = net::physical_default(&self.config.adapter_name, &adapters)?;
        if let Some(default) = &default {
            self.bypass(default)?;
            self.kill_switch(&adapters)?;
        }
        Ok(network::signature(
            default.as_ref(),
            &net::active_names(&adapters, &self.config.adapter_name),
            &self.config.adapter_name,
        ))
    }

    fn network_signature(&self) -> Result<Option<String>> {
        let adapters = net::adapters()?;
        let default = net::physical_default(&self.config.adapter_name, &adapters)?;
        Ok(network::signature(
            default.as_ref(),
            &net::active_names(&adapters, &self.config.adapter_name),
            &self.config.adapter_name,
        ))
    }

    fn guards(&self) -> Result<()> {
        let start = Instant::now();
        for (name, protocol) in [("Ember DNS guard (UDP)", 17), ("Ember DNS guard (TCP)", 6)] {
            self.firewall.block(
                name,
                "0.0.0.0-198.18.0.1,198.18.0.3-255.255.255.255",
                protocol,
                Some("53"),
                None,
            )?;
        }
        self.firewall.block(
            "Ember IPv6 guard",
            "2000::-3fff:ffff:ffff:ffff:ffff:ffff:ffff:ffff",
            256,
            None,
            None,
        )?;
        self.state.elapsed("firewall guards set", start);
        Ok(())
    }

    fn up(&mut self, hev: &Path, child: &mut Option<Child>) -> Result<()> {
        let total = Instant::now();
        self.state.cleanup(Some(&self.firewall), &[]);
        for ip in &self.config.edge_ips {
            if let Err(e) = net::remove_route(Cidr::host(*ip), None) {
                self.state.log(&format!("cleanup (edge route): {e}"));
            }
        }
        self.state.remove("stop");
        self.state.status("STARTING")?;
        self.state.log(&format!(
            "up requested (killSwitch={})",
            self.config.kill_switch
        ));
        let start = Instant::now();
        let adapters = net::adapters()?;
        let default = net::physical_default(&self.config.adapter_name, &adapters)?
            .ok_or("no default IPv4 route found")?;
        self.state.elapsed("physical default found", start);
        self.kill_switch(&adapters)?;
        self.bypass(&default)?;

        let start = Instant::now();
        *child = Some(process::spawn(hev, &self.config.hev_config_path)?);
        let proc = child.as_mut().ok_or("tunnel process was not started")?;
        let adapter = loop {
            if proc.try_wait().map_err(|e| e.to_string())?.is_some() {
                return Err("the tunnel process exited during start-up".into());
            }
            if let Some(adapter) = net::adapters()?
                .into_iter()
                .find(|a| a.name.eq_ignore_ascii_case(&self.config.adapter_name))
            {
                break adapter;
            }
            if start.elapsed() >= Duration::from_secs(20) {
                return Err(format!(
                    "the tunnel adapter '{}' did not appear",
                    self.config.adapter_name
                ));
            }
            sleep(Duration::from_millis(100));
        };
        self.state.elapsed("tunnel adapter appeared", start);
        let start = Instant::now();
        net::configure(
            &adapter,
            self.config.tun_address,
            self.config.mapdns_address,
        )?;
        for text in ["0.0.0.0/1", "128.0.0.0/1"] {
            self.add_route(
                Cidr::parse(text, 0, false).unwrap(),
                adapter.index,
                std::net::Ipv4Addr::UNSPECIFIED,
                "tun",
            )?;
        }
        self.state.elapsed("tunnel interface configured", start);
        self.guards()?;
        self.state.status("UP")?;
        self.state.elapsed("tunnel is up", total);
        self.watch(proc)
    }

    fn cleanup(&self) {
        self.state.cleanup(Some(&self.firewall), &self.added);
    }

    fn watch(&mut self, proc: &mut Child) -> Result<()> {
        let mut last_signature = self.network_signature()?;
        let mut tick = 0u64;
        loop {
            sleep(Duration::from_secs(1));
            tick += 1;
            if self.state.dir.join("stop").exists() {
                self.cleanup();
                self.state.status("DOWN")?;
                self.state.log("stopped on request");
                return Ok(());
            }
            if proc.try_wait().map_err(|e| e.to_string())?.is_some() {
                if !self.config.kill_switch {
                    self.cleanup();
                    self.state.status("ERROR: the tunnel process exited")?;
                    return Ok(());
                }
                self.state.status(
                    "BLOCKED: the tunnel process exited; the kill switch is blocking traffic",
                )?;
                self.state.log("tunnel process exited; kill switch holding");
                while !self.state.dir.join("stop").exists() {
                    sleep(Duration::from_secs(1));
                }
                self.cleanup();
                self.state.status("DOWN")?;
                return Ok(());
            }
            if !process::alive(self.config.parent_pid)? {
                process::kill_tunnels(|s| self.state.log(s));
                if self.config.kill_switch {
                    self.state.status(
                        "BLOCKED: Ember closed unexpectedly; the kill switch is blocking traffic",
                    )?;
                    self.state.log("parent gone; kill switch left in place");
                } else {
                    self.cleanup();
                    self.state.status("DOWN")?;
                }
                return Ok(());
            }
            let requested = self.state.dir.join("refresh").exists();
            if requested || tick.is_multiple_of(5) {
                let check = || -> Result<bool> {
                    Ok(requested || !net::bypass_present(&self.config.edge_ips)?)
                };
                if requested {
                    self.state.remove("refresh");
                }
                let result = check().and_then(|needed| {
                    if needed {
                        self.state.log(if requested {
                            "refresh requested; re-asserting the edge route"
                        } else {
                            "edge route was missing; restoring it"
                        });
                        self.refresh()?;
                    }
                    Ok(())
                });
                if let Err(e) = result {
                    self.state.log(&format!("route self-check failed: {e}"));
                }
            }
            if tick.is_multiple_of(3) {
                let result = self.network_signature().and_then(|signature| {
                    if signature.is_some() && signature != last_signature {
                        self.state.log(&format!(
                            "network changed: {}",
                            signature.as_deref().unwrap_or_default()
                        ));
                        last_signature = self.refresh()?;
                    }
                    Ok(())
                });
                if let Err(e) = result {
                    self.state.log(&format!("network refresh failed: {e}"));
                }
            }
        }
    }
}

fn find_hev(root: &Path) -> Result<PathBuf> {
    // Search only the trusted application bundle, never an executable path from config.
    // Do not follow directory junctions/symlinks out of that bundle or into cycles.
    for entry in fs::read_dir(root).map_err(|e| e.to_string())? {
        let entry = entry.map_err(|e| e.to_string())?;
        let kind = entry.file_type().map_err(|e| e.to_string())?;
        if kind.is_file()
            && entry
                .file_name()
                .to_string_lossy()
                .eq_ignore_ascii_case("hev-socks5-tunnel.exe")
        {
            return Ok(entry.path());
        }
        if kind.is_dir() {
            if let Ok(found) = find_hev(&entry.path()) {
                return Ok(found);
            }
        }
    }
    Err("hev-socks5-tunnel.exe was not found next to this script".into())
}

fn run(mode: &str, value: &Value, state: &State) -> Result<()> {
    // Check before COM activation, process termination, or any network mutation, including restore.
    if !process::elevated()? {
        return Err("administrator rights are required".into());
    }
    if mode == "restore" {
        state.cleanup(None, &[]);
        state.status("DOWN")?;
        state.log("restore complete");
        return Ok(());
    }
    let prepared = || -> Result<(UpConfig, PathBuf, Firewall)> {
        let config = UpConfig::parse(value, &state.dir, |s| state.log(s))?;
        let exe = std::env::current_exe().map_err(|e| e.to_string())?;
        let hev = find_hev(
            &exe.parent()
                .ok_or("helper has no parent folder")?
                .join("tunnel"),
        )
        .map_err(|_| "hev-socks5-tunnel.exe was not found next to this script".to_owned())?;
        Ok((config, hev, Firewall::new()?))
    };
    let (config, hev, firewall) = match prepared() {
        Ok(prepared) => prepared,
        Err(e) => {
            state.cleanup(None, &[]);
            return Err(e);
        }
    };
    let mut session = Session {
        state,
        config,
        firewall,
        added: Vec::new(),
    };
    let mut child = None;
    let result = session.up(&hev, &mut child);
    if let Err(e) = &result {
        state.log(&format!("error: {e}"));
        // Kill our own child by handle as well, even if name enumeration has failed.
        if let Some(proc) = child.as_mut() {
            let _ = proc.kill();
            let _ = proc.wait();
        }
        session.cleanup();
    }
    result
}

pub fn entry() -> i32 {
    let args: Vec<_> = std::env::args_os().skip(1).collect();
    if args.len() != 3 || (args[0] != "up" && args[0] != "restore") || args[1] != "--config" {
        eprintln!("usage: ember-helper <up|restore> --config <path>");
        return 1;
    }
    let path = PathBuf::from(&args[2]);
    let boot = State {
        dir: path.parent().unwrap_or(Path::new(".")).to_owned(),
    };
    boot.log(&format!(
        "helper started: mode={}",
        args[0].to_string_lossy()
    ));
    let value = match config::read(&path) {
        Ok(value) => value,
        Err(e) => {
            boot.log(&format!("fatal: {e}"));
            let _ = boot.status(&format!("ERROR: {e}"));
            return 1;
        }
    };
    let state = match StateConfig::parse(&value) {
        Ok(config) => State {
            dir: config.state_dir,
        },
        Err(e) => {
            boot.log(&format!("fatal: {e}"));
            let _ = boot.status(&format!("ERROR: {e}"));
            return 1;
        }
    };
    match run(&args[0].to_string_lossy(), &value, &state) {
        Ok(()) => 0,
        Err(e) => {
            state.log(&format!("error: {e}"));
            let _ = state.status(&format!("ERROR: {e}"));
            1
        }
    }
}
