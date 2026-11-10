#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]
mod about;
mod backend;
mod lang;
mod logs;
mod releases;
mod servers;
mod sphere;
mod strings;

slint::include_modules!();

use ember_engine::{ConnState, Engine, HTTP_PORT, LOCAL_BIND, SOCKS_PORT};
use slint::{ComponentHandle, Image, Model, ModelRc, SharedString, VecModel};
use std::{
    path::PathBuf,
    rc::Rc,
    sync::{
        atomic::{AtomicBool, AtomicU32, Ordering},
        Arc,
    },
    time::{Duration, Instant},
};

/// Same wrap as `LOOP` in GlassOrb.kt: ten full turns, so every dot returns to where it started.
const LOOP: f32 = 20.0 * std::f32::consts::PI;

/// The orb's motion: eased towards where the connection state says it should be, a few times a second.
struct OrbMotion {
    lit: f32,
    speed: f32,
    loader: f32,
    angle: f32,
    ripple: f32,
    pop: f32,
    pop_v: f32,
    feel: f32,
    list_t: f32,
    row_t: f32,
    row_card: String,
    sprites: SpriteCache,
    frame: u32,
    page_in: f32,
    last_page: i32,
    last: Instant,
    previous: ConnState,
}

/// The sphere sprites for the size now shown. They are drawn 1:1 (a resampled sprite has jagged edges), so they are made
/// again whenever the sphere's pixel size changes (the connect spring, hover, press); only the visible one is made.
#[derive(Default)]
struct SpriteCache {
    diameter: u32,
    scale: f32,
    mint: bool,
    off: bool,
    on: bool,
}

impl SpriteCache {
    fn ensure(&mut self, app: &App, diameter: u32, scale: f32, lit: f32, mint: bool) {
        let m = app.global::<Motion>();
        let fresh = diameter != self.diameter || scale != self.scale || mint != self.mint;
        if fresh {
            *self = SpriteCache { diameter, scale, mint, off: false, on: false };
        }
        if lit < 0.999 && !self.off {
            self.off = true;
            m.set_sphere_off(sphere::sprite(diameter, 0.0, mint));
        }
        if lit > 0.001 && !self.on {
            self.on = true;
            m.set_sphere_on(sphere::sprite(diameter, 1.0, mint));
        }
        if fresh {
            m.set_sprite_size((diameter + 2 * sphere::PAD) as f32 / scale);
        }
    }
}

fn approach(v: f32, target: f32, dt: f32, seconds: f32) -> f32 {
    v + (target - v) * (dt / seconds).min(1.0)
}

/// A round flag from the bundled circle-flags set: next to the exe in an install, in the source tree when developing.
pub(crate) fn flag(code: &str) -> Option<Image> {
    let name = format!("flags/{}.svg", code.to_lowercase());
    let exe_dir = std::env::current_exe().ok().and_then(|p| p.parent().map(std::path::Path::to_path_buf));
    let beside_exe = exe_dir.as_ref().map(|d| d.join("resources").join(&name));
    let in_source = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("ui/assets").join(&name);
    let next_to_exe = exe_dir.map(|d| d.join(&name));
    [beside_exe, next_to_exe, Some(in_source)].into_iter().flatten().find(|p| p.exists()).and_then(|p| Image::load_from_path(&p).ok())
}

/// `--ports <socks> <http>`: other local proxy ports, so the Rust app can run beside the Kotlin app (which holds 10808).
fn ports(args: &[String]) -> (u16, u16) {
    let Some(i) = args.iter().position(|a| a == "--ports") else { return (SOCKS_PORT, HTTP_PORT) };
    let number = |k: usize| args.get(i + k).and_then(|v| v.parse::<u16>().ok());
    match (number(1), number(2)) {
        (Some(socks), Some(http)) if socks != http => (socks, http),
        _ => (SOCKS_PORT, HTTP_PORT),
    }
}

fn duration(seconds: u64) -> String {
    format!("{:02}:{:02}:{:02}", seconds / 3600, seconds % 3600 / 60, seconds % 60)
}

fn detail(result: Option<&ember_subs::subscription::NodeResult>, precise: bool) -> String {
    match result {
        Some(r) => match (r.mbps, r.ping_ms) {
            (Some(mbps), _) if precise => format!("{mbps:.1} Mbps"),
            (Some(mbps), _) => format!("{mbps:.0} Mbps"),
            (None, Some(ping)) => format!("{ping} ms"),
            _ => String::new(),
        },
        None => String::new(),
    }
}

/// Updates the server list in place. Replacing the whole model would close the open dropdown every time the engine
/// reports progress (a speed test does that many times a second).
fn sync_rows(model: &VecModel<ServerRow>, rows: Vec<ServerRow>) {
    let same_shape = model.row_count() == rows.len()
        && rows.iter().enumerate().all(|(i, row)| model.row_data(i).is_some_and(|old| old.id == row.id));
    if !same_shape {
        model.set_vec(rows);
        return;
    }
    for (i, row) in rows.into_iter().enumerate() {
        let changed = model
            .row_data(i)
            .is_some_and(|old| old.title != row.title || old.detail != row.detail || old.selected != row.selected || old.has_flag != row.has_flag);
        if changed {
            model.set_row_data(i, row);
        }
    }
}

/// Copies what the engine knows into the window.
fn refresh(app: &App, engine: &Engine, model: &VecModel<ServerRow>, s: &strings::Strings, ports: (u16, u16)) {
    let snap = engine.snapshot();
    let store = engine.store();
    let results = store.results();
    let favorites = engine.settings().favorite_nodes();
    let all: Vec<_> = store.all_nodes().into_iter().filter(|n| !n.is_info()).collect();
    let sorted = ember_subs::sort::sort_nodes(&all, &results, &favorites);
    let auto = snap.selected_id == ember_subs::subscription::AUTO_NODE;

    let rows: Vec<ServerRow> = sorted
        .iter()
        .map(|n| {
            let image = n.flag_code().and_then(|c| flag(&c));
            ServerRow {
                id: n.id.as_str().into(),
                title: n.title().into(),
                has_flag: image.is_some(),
                flag: image.unwrap_or_default(),
                detail: detail(results.get(&n.id), false).into(),
                selected: snap.selected_id == n.id,
            }
        })
        .collect();
    sync_rows(model, rows);
    app.set_auto_selected(auto);

    let connected = snap.state == ConnState::Connected;
    let node = snap.connected_node.clone().or_else(|| engine.selected_node());
    let title = match (&node, auto) {
        (Some(n), true) if !connected => format!("{} · {}", s.fastest_server, n.title()),
        (Some(n), _) => n.title(),
        (None, true) => s.fastest_server.to_string(),
        (None, false) => s.choose_server.to_string(),
    };
    app.set_server_title(title.into());
    // once connected, the country the internet really sees beats the server's own label
    let shown = snap.exit_country.clone().filter(|_| connected).or_else(|| node.as_ref().and_then(|n| n.flag_code()));
    let image = shown.as_deref().and_then(flag);
    app.set_server_has_flag(image.is_some());
    app.set_server_flag(image.unwrap_or_default());
    app.set_server_detail(if connected {
        SharedString::new()
    } else {
        detail(node.as_ref().and_then(|n| results.get(&n.id)), true).into()
    });

    app.set_state(match snap.state {
        ConnState::Disconnected => 0,
        ConnState::Connecting => 1,
        ConnState::Connected => 2,
    });
    app.set_status(
        match snap.state {
            ConnState::Disconnected => s.disconnected,
            ConnState::Connecting => s.connecting,
            ConnState::Connected => s.connected,
        }
        .into(),
    );
    app.set_can_pick(snap.state == ConnState::Disconnected);
    app.set_secondary(match snap.state {
        ConnState::Disconnected => SharedString::new(),
        ConnState::Connecting => snap.progress.clone().unwrap_or_default().into(),
        ConnState::Connected => {
            duration(snap.connected_at_ms.map(|t| ((ember_engine::now_ms() - t) / 1000).max(0) as u64).unwrap_or(0)).into()
        }
    });
    app.set_caption(if !connected || snap.using_openvpn {
        SharedString::new()
    } else if snap.whole_device {
        s.whole_device_short.into()
    } else if snap.app_rules {
        s.app_rules.into()
    } else {
        strings::fill(
            s.proxy_mode_caption,
            &[("socks", &format!("{LOCAL_BIND}:{}", ports.0)), ("http", &format!("{LOCAL_BIND}:{}", ports.1))],
        )
        .into()
    });
    app.set_blocked_notice(if DEMO_BLOCKED.load(Ordering::SeqCst) {
        "Ember closed while the kill switch was on, so internet access is still blocked.".into()
    } else {
        snap.blocked_notice.clone().unwrap_or_default().into()
    });
    app.set_can_restore(snap.state == ConnState::Disconnected);
    match &snap.credential_request {
        Some(request) => {
            let template = if request.ask_user { s.openvpn_sign_in } else { s.key_password_for };
            app.set_cred_title(strings::fill(template, &[("0", &request.server_name)]).into());
            app.set_cred_ask_user(request.ask_user);
            app.set_cred_open(true);
        }
        None if DEMO_CRED.load(Ordering::SeqCst) => {
            app.set_cred_title(strings::fill(s.openvpn_sign_in, &[("0", "Office")]).into());
            app.set_cred_ask_user(true);
            app.set_cred_open(true);
        }
        None => app.set_cred_open(false),
    }
    app.set_error(snap.last_error.clone().unwrap_or_default().into());
    app.set_testing(snap.test_progress.is_some());
    app.set_test_progress(snap.test_progress.clone().unwrap_or_default().into());
}

fn apply_language(app: &App, language: &str) {
    let s = strings::for_language(language);
    app.global::<Palette>().set_rtl(language == "fa");
    let tr = app.global::<Tr>();
    tr.set_home(s.home.into());
    tr.set_servers(s.servers.into());
    tr.set_settings(s.settings.into());
    tr.set_logs(s.logs.into());
    tr.set_whats_new(s.whats_new.into());
    tr.set_about(s.about.into());
    tr.set_test_speed(s.test_speed.into());
    tr.set_stop_test(s.stop_test.into());
    tr.set_fastest_server(s.fastest_server.into());
    tr.set_manage_servers(s.manage_servers.into());
    tr.set_subscriptions(s.subscriptions.into());
    tr.set_subscriptions_help(s.subscriptions_help.into());
    tr.set_add_servers(s.add_servers.into());
    tr.set_add_link(s.add_subscription.into());
    tr.set_paste_clipboard(s.paste_from_clipboard.into());
    tr.set_empty_title(s.empty_servers_title.into());
    tr.set_empty_text(s.no_subscriptions.into());
    tr.set_refresh(s.refresh.into());
    tr.set_edit(s.edit_subscription.into());
    tr.set_remove(s.remove_subscription.into());
    tr.set_expand(s.expand.into());
    tr.set_collapse(s.collapse.into());
    tr.set_no_answer(s.no_answer.into());
    tr.set_favorite(s.favorite.into());
    tr.set_link_label(s.subscription_link.into());
    tr.set_name_label(s.subscription_name_optional.into());
    tr.set_adding(s.adding.into());
    tr.set_saving(s.saving.into());
    tr.set_save(s.save.into());
    tr.set_cancel(s.cancel.into());
    tr.set_test_needs_disconnect(s.test_needs_disconnect.into());
    tr.set_all_logs(s.all_logs.into());
    tr.set_info_logs(s.info_logs.into());
    tr.set_problem_logs(s.problem_logs.into());
    tr.set_copy(s.copy.into());
    tr.set_clear(s.clear.into());
    tr.set_copy_report(s.copy_report.into());
    tr.set_copy_report_hint(s.copy_report_hint.into());
    tr.set_empty_logs_title(s.empty_logs_title.into());
    tr.set_empty_logs_text(s.empty_logs.into());
    tr.set_settings_interface(s.settings_interface.into());
    tr.set_settings_connection(s.settings_connection.into());
    tr.set_settings_more(s.settings_more.into());
    tr.set_language(s.language.into());
    tr.set_english(s.english_language.into());
    tr.set_persian(s.persian_language.into());
    tr.set_appearance(s.appearance.into());
    tr.set_light(s.light.into());
    tr.set_dark(s.dark.into());
    tr.set_mint(s.mint.into());
    tr.set_system(s.system.into());
    tr.set_startup_tray(s.startup_and_tray.into());
    tr.set_protection(s.protection.into());
    tr.set_app_rules(s.app_rules.into());
    tr.set_skip_vpn(s.skip_vpn.into());
    tr.set_trusted_networks(s.trusted_networks_title.into());
    tr.set_backup(s.backup_title.into());
    tr.set_advanced(s.advanced.into());
    tr.set_back(s.back.into());
    tr.set_keep_in_tray(s.keep_in_tray.into());
    tr.set_sounds(s.sounds.into());
    tr.set_sounds_help(s.sounds_help.into());
    tr.set_tray_help(s.tray_help.into());
    tr.set_start_with_windows(s.start_with_windows.into());
    tr.set_start_quietly(s.start_quietly.into());
    tr.set_auto_connect(s.auto_connect.into());
    tr.set_auto_connect_help(s.auto_connect_help.into());
    tr.set_whole_device(s.whole_device.into());
    tr.set_whole_device_unavailable(s.whole_device_unavailable.into());
    tr.set_kill_switch(s.kill_switch.into());
    tr.set_kill_switch_help(s.kill_switch_help.into());
    tr.set_ipv6(s.ipv6.into());
    tr.set_ipv6_help(s.ipv6_help.into());
    tr.set_auto_failover(s.auto_failover.into());
    tr.set_auto_failover_help(s.auto_failover_help.into());
    tr.set_no_prompts(s.no_prompts.into());
    tr.set_no_prompts_help(s.no_prompts_help.into());
    tr.set_diagnostic_log(s.diagnostic_log.into());
    tr.set_diagnostic_log_help(s.diagnostic_log_help.into());
    tr.set_mtu_help(s.mtu_help.into());
    tr.set_repair_network(s.repair_network.into());
    tr.set_repair_network_hint(s.repair_network_hint.into());
    tr.set_proxy_only(s.proxy_only_short.into());
    tr.set_next_connection(s.next_connection.into());
    tr.set_not_yet(lang::not_yet().into());
    tr.set_whats_new_intro(s.whats_new_intro.into());
    tr.set_show(s.show.into());
    tr.set_hide(s.hide.into());
    tr.set_contact(s.about_contact.into());
    tr.set_licenses(s.about_licenses.into());
    tr.set_current_version(s.current_version.into());
    tr.set_check_site_title(s.check_site_title.into());
    tr.set_check_site_help(s.check_site_help.into());
    tr.set_site_hint(s.site_hint.into());
    tr.set_check_button(s.check_button.into());
    tr.set_clear_check(s.clear_check.into());
    tr.set_qr_hint(s.qr_hint.into());
    tr.set_qr_too_long(s.qr_too_long.into());
    tr.set_copy_link(s.copy_link.into());
    tr.set_close(s.close.into());
    tr.set_backup_hint(s.backup_hint.into());
    tr.set_save_backup(s.save_backup.into());
    tr.set_restore_backup(s.restore_backup.into());
    tr.set_restore_and_restart(s.restore_and_restart.into());
    tr.set_reset_default(s.reset_default.into());
    tr.set_whole_device_help(s.whole_device_help.into());
    tr.set_whole_device_short(s.whole_device_short.into());
    tr.set_auto_untrusted(s.auto_connect_untrusted.into());
    tr.set_auto_untrusted_help(s.auto_connect_untrusted_help.into());
    tr.set_trust_network(s.trust_this_network.into());
    tr.set_no_trusted(s.no_trusted_networks.into());
    tr.set_skip_help(s.skip_vpn_help.into());
    tr.set_needs_whole(s.needs_whole_device.into());
    tr.set_address_placeholder(s.address_placeholder.into());
    tr.set_add_address(s.add_address.into());
    tr.set_username(s.username.into());
    tr.set_sign_in(s.sign_in.into());
    tr.set_credentials_not_saved(s.credentials_not_saved.into());
    tr.set_password(s.password.into());
    tr.set_app_off(s.app_rules_off.into());
    tr.set_app_skip(s.app_rules_skip.into());
    tr.set_app_skip_help(s.app_rules_skip_help.into());
    tr.set_app_only(s.app_rules_only.into());
    tr.set_app_only_help(s.app_rules_only_help.into());
    tr.set_app_note(s.app_rules_note.into());
    tr.set_app_needs_whole(s.app_rules_needs_whole.into());
    tr.set_apps_list(s.apps_list.into());
    tr.set_add_app(s.add_app.into());
    tr.set_no_apps(s.no_apps.into());
    tr.set_internet_blocked(s.internet_blocked.into());
    tr.set_restore_internet(s.restore_internet.into());
}

/// A short message at the bottom of the window.
fn show_toast(app: &App, generation: &Arc<AtomicU32>, text: &str, error: bool) {
    let mine = generation.fetch_add(1, Ordering::SeqCst) + 1;
    app.set_toast(text.into());
    app.set_toast_error(error);
    let (weak, generation) = (app.as_weak(), Arc::clone(generation));
    // errors stay a little longer, as in the Kotlin app
    let seconds = if error { 5 } else { 3 } + (text.len() as u64 / 40).min(4);
    slint::Timer::single_shot(Duration::from_secs(seconds), move || {
        if generation.load(Ordering::SeqCst) == mine {
            if let Some(app) = weak.upgrade() {
                app.set_toast(SharedString::new());
            }
        }
    });
}

/// A link as a black-and-white QR picture (each module is 6 pixels, with the quiet border), or `None` when the link is
/// too long for a code.
fn qr_image(link: &str) -> Option<slint::Image> {
    use ember_subs::qr::{qr_matrix, QUIET_ZONE};
    const SCALE: usize = 6;
    if link.is_empty() {
        return None;
    }
    let matrix = qr_matrix(link).ok()?;
    let side = (matrix.size + 2 * QUIET_ZONE) * SCALE;
    let mut buffer = slint::SharedPixelBuffer::<slint::Rgba8Pixel>::new(side as u32, side as u32);
    let pixels = buffer.make_mut_slice();
    for y in 0..side {
        for x in 0..side {
            let (mx, my) = (x / SCALE, y / SCALE);
            let dark = mx >= QUIET_ZONE && my >= QUIET_ZONE && mx - QUIET_ZONE < matrix.size && my - QUIET_ZONE < matrix.size && matrix.is_dark(mx - QUIET_ZONE, my - QUIET_ZONE);
            let v = if dark { 0 } else { 255 };
            pixels[y * side + x] = slint::Rgba8Pixel { r: v, g: v, b: v, a: 255 };
        }
    }
    Some(slint::Image::from_rgba8(buffer))
}

/// Applies [`style_window`] once the event loop is running (the system window may not exist before that).
fn style_window_soon(app: &App) {
    let weak = app.as_weak();
    slint::Timer::single_shot(Duration::from_millis(100), move || {
        if let Some(app) = weak.upgrade() {
            style_window(&app);
        }
    });
}

/// The frameless window keeps the system's soft shadow and rounded corners (Windows 11).
fn style_window(app: &App) {
    use slint::winit_030::winit::platform::windows::{CornerPreference, WindowExtWindows};
    use slint::winit_030::WinitWindowAccessor;
    app.window().with_winit_window(|win| {
        win.set_undecorated_shadow(true);
        win.set_corner_preference(CornerPreference::Round);
    });
}

fn set_clipboard(text: &str) -> bool {
    arboard::Clipboard::new().and_then(|mut c| c.set_text(text.to_string())).is_ok()
}

/// Fills the About and What's new pages in the language in use.
fn push_static_pages(app: &App, data_dir: &std::path::Path) {
    let s = lang::current();
    app.set_about_info(about::info(data_dir, s));
    app.set_about_licenses(about::licenses());
    // the Kotlin app marks the entry of the version that is running; the Rust build has its own version numbers
    app.set_releases(about::releases(lang::code(), env!("CARGO_PKG_VERSION")));
}

/// The look for an Appearance value ("Light", "Dark", "Mint" or "System"): 0 Strawberry chocolate, 1 Dark chocolate, 2 Mint chocolate.
fn flavour(theme: &str, forced: Option<i32>) -> i32 {
    forced.unwrap_or_else(|| match theme {
        "Light" => 0,
        "Dark" => 1,
        "Mint" => 2,
        _ => i32::from(ember_win::theme::apps_use_dark()),
    })
}

static CAPABILITIES: std::sync::OnceLock<ember_engine::Capabilities> = std::sync::OnceLock::new();
/// Whether Windows starts Ember at sign-in (asking launches `reg.exe`, so it is read at start and after a change).
static AUTOSTART: AtomicBool = AtomicBool::new(false);
/// Debug flags `--demo-blocked` and `--demo-cred` show the kill-switch notice and the OpenVPN sign-in without a real connection.
static DEMO_BLOCKED: AtomicBool = AtomicBool::new(false);
static DEMO_CRED: AtomicBool = AtomicBool::new(false);

fn entries_text(count: usize) -> String {
    let fa = lang::code() == "fa";
    match (count, fa) {
        (0, false) => "None".to_string(),
        (0, true) => "\u{647}\u{6cc}\u{686}".to_string(),
        (n, false) => format!("{n} entries"),
        (n, true) => format!("{n} \u{645}\u{648}\u{631}\u{62f}"),
    }
}

fn app_rules_text(mode: &str) -> &'static str {
    match (mode, lang::code() == "fa") {
        ("skip", false) => "VPN off for some",
        ("only", false) => "VPN only for some",
        ("skip", true) => "VPN \u{62e}\u{627}\u{645}\u{648}\u{634} \u{628}\u{631}\u{627}\u{6cc} \u{628}\u{639}\u{636}\u{6cc}",
        ("only", true) => "VPN \u{641}\u{642}\u{637} \u{628}\u{631}\u{627}\u{6cc} \u{628}\u{639}\u{636}\u{6cc}",
        (_, false) => "Off",
        (_, true) => "\u{62e}\u{627}\u{645}\u{648}\u{634}",
    }
}

fn network_name(id: &str, s: &strings::Strings) -> String {
    if id.starts_with("wifi:") {
        ember_win::network_identity::display_name(id)
    } else {
        s.wired_network.to_string()
    }
}

fn text_model(items: Vec<String>) -> ModelRc<SharedString> {
    ModelRc::new(VecModel::from(items.into_iter().map(SharedString::from).collect::<Vec<_>>()))
}

/// Puts the saved settings into the Settings page.
fn push_settings(app: &App, engine: &Engine) {
    let s = engine.settings();
    let t = lang::current();
    let caps = *CAPABILITIES.get_or_init(|| engine.capabilities());
    let snap = engine.snapshot();
    let trusted = s.trusted_networks();
    let (skip, apps) = (s.excluded_addresses(), s.app_rule_apps());
    let mode = s.app_rule_mode();
    let network = match &snap.network {
        Some(id) => strings::fill(t.current_network, &[("0", &network_name(id, t))]),
        None => t.offline.to_string(),
    };
    app.set_settings_data(SettingsData {
        language: lang::code().into(),
        theme: s.theme().into(),
        auto_connect: s.auto_connect(),
        auto_failover: s.auto_failover(),
        diagnostic_log: s.diagnostic_log(),
        keep_in_tray: s.keep_in_tray(),
        sounds: s.sounds(),
        start_with_windows: AUTOSTART.load(Ordering::SeqCst),
        whole_device: s.whole_device() && caps.whole_device,
        whole_available: caps.whole_device,
        kill_switch: s.kill_switch(),
        ipv6: s.ipv6(),
        no_prompts: engine.setup_installed(),
        auto_untrusted: s.auto_connect_untrusted(),
        app_mode: mode.as_str().into(),
        app_rules_available: caps.app_rules,
        app_summary: app_rules_text(&mode).into(),
        skip_summary: entries_text(skip.len()).into(),
        trusted_summary: entries_text(trusted.len()).into(),
        mtu: s.mtu().to_string().into(),
        can_trust: snap.network.as_ref().is_some_and(|id| !trusted.contains(id)),
        network: network.into(),
        trusted: text_model(trusted.iter().map(|id| network_name(id, t)).collect()),
        skip: text_model(skip),
        apps: text_model(apps),
    });
}

/// The Settings page's choices and switches.
fn wire_settings_page(
    app: &App,
    engine: &Engine,
    forced_theme: Option<i32>,
    servers_ui: &Rc<servers::ServersUi>,
    logs_ui: &Rc<logs::LogsUi>,
    dirty: &Arc<AtomicBool>,
    data_dir: &std::path::Path,
) {
    {
        let data_dir = data_dir.to_path_buf();
        let (engine, weak, servers_ui, logs_ui, dirty) =
            (engine.clone(), app.as_weak(), Rc::clone(servers_ui), Rc::clone(logs_ui), Arc::clone(dirty));
        app.on_language_chosen(move |code| {
            let Some(app) = weak.upgrade() else { return };
            let code = if code == "fa" { "fa" } else { "en" };
            engine.settings().set_language(code);
            lang::set(code);
            apply_language(&app, code);
            push_settings(&app, &engine);
            push_static_pages(&app, &data_dir);
            servers_ui.invalidate();
            logs::apply(&app, &logs_ui, true);
            dirty.store(true, Ordering::SeqCst);
        });
    }
    {
        let (engine, weak) = (engine.clone(), app.as_weak());
        app.on_theme_chosen(move |theme| {
            let Some(app) = weak.upgrade() else { return };
            engine.settings().set_theme(&theme);
            app.global::<Palette>().set_flavour(flavour(&theme, forced_theme));
            push_settings(&app, &engine);
        });
    }
    {
        let (engine, weak) = (engine.clone(), app.as_weak());
        app.on_flag_toggled(move |key, value| {
            let Some(app) = weak.upgrade() else { return };
            let s = engine.settings();
            match key.as_str() {
                "autoConnect" => s.set_auto_connect(value),
                "autoFailover" => s.set_auto_failover(value),
                "diagnosticLog" => s.set_diagnostic_log(value),
                "keepInTray" => s.set_keep_in_tray(value),
                "sounds" => s.set_sounds(value),
                "wholeDevice" => s.set_whole_device(value),
                "killSwitch" => s.set_kill_switch(value),
                "ipv6" => s.set_ipv6(value),
                "autoConnectUntrusted" => s.set_auto_connect_untrusted(value),
                "noPrompts" => engine.set_setup(value), // one Windows prompt, on its own thread; the switch follows when it is done
                "startWithWindows" => {
                    if !ember_win::autostart::set_enabled(value) {
                        ember_engine::log::warn("Ember", "could not change the Windows startup entry");
                    }
                    AUTOSTART.store(ember_win::autostart::is_enabled(), Ordering::SeqCst);
                }
                _ => {}
            }
            push_settings(&app, &engine);
        });
    }
    {
        let (engine, weak) = (engine.clone(), app.as_weak());
        app.on_app_mode_chosen(move |mode| {
            let Some(app) = weak.upgrade() else { return };
            engine.settings().set_app_rule_mode(&mode);
            push_settings(&app, &engine);
        });
    }
    {
        let (engine, weak) = (engine.clone(), app.as_weak());
        app.on_list_add(move |kind, text| {
            let Some(app) = weak.upgrade() else { return };
            let t = lang::current();
            let s = engine.settings();
            let mut error = String::new();
            match kind.as_str() {
                "skip" => {
                    let entry = site_or_address(text.as_str());
                    let mut list = s.excluded_addresses();
                    if !(ember_engine::skip::is_address(&entry) || ember_engine::skip::is_domain(&entry)) {
                        error = t.address_validation.to_string();
                    } else if list.len() >= 300 {
                        error = t.address_limit.to_string();
                    } else if !list.iter().any(|e| e.eq_ignore_ascii_case(&entry)) {
                        list.push(entry);
                        s.set_excluded_addresses(&list);
                    }
                }
                "apps" => {
                    let name = program_name(text.as_str());
                    let mut list = s.app_rule_apps();
                    if !list.iter().any(|e| e.eq_ignore_ascii_case(&name)) {
                        list.push(name);
                        s.set_app_rule_apps(&list);
                    }
                }
                _ => {}
            }
            app.set_list_error(error.into());
            push_settings(&app, &engine);
        });
    }
    {
        let (engine, weak) = (engine.clone(), app.as_weak());
        app.on_list_remove(move |kind, index| {
            let Some(app) = weak.upgrade() else { return };
            let s = engine.settings();
            let i = index.max(0) as usize;
            match kind.as_str() {
                "skip" => {
                    let mut list = s.excluded_addresses();
                    if i < list.len() {
                        list.remove(i);
                        s.set_excluded_addresses(&list);
                    }
                }
                "apps" => {
                    let mut list = s.app_rule_apps();
                    if i < list.len() {
                        list.remove(i);
                        s.set_app_rule_apps(&list);
                    }
                }
                "trusted" => {
                    if let Some(id) = s.trusted_networks().get(i) {
                        engine.untrust_network(id);
                    }
                }
                _ => {}
            }
            app.set_list_error(SharedString::new());
            push_settings(&app, &engine);
        });
    }
    {
        let (engine, weak) = (engine.clone(), app.as_weak());
        app.on_mtu_apply(move |text| {
            let Some(app) = weak.upgrade() else { return };
            let range = ember_engine::settings::MTU_RANGE;
            match text.trim().parse::<i32>() {
                Ok(v) if range.contains(&v) => {
                    engine.settings().set_mtu(v);
                    app.set_list_error(SharedString::new());
                }
                _ => {
                    let message = strings::fill(lang::current().mtu_validation, &[("min", &range.start().to_string()), ("max", &range.end().to_string())]);
                    app.set_list_error(message.into());
                }
            }
            push_settings(&app, &engine);
        });
    }
    wire_backup(app, engine, data_dir);
    {
        let (engine, weak) = (engine.clone(), app.as_weak());
        app.on_trust_network(move || {
            let Some(app) = weak.upgrade() else { return };
            engine.trust_current_network();
            push_settings(&app, &engine);
        });
    }
}

/// Backup and restore. The file dialogs block, so they run on their own thread and hand the answer back to the UI thread.
fn wire_backup(app: &App, engine: &Engine, data_dir: &std::path::Path) {
    let pending: Arc<std::sync::Mutex<Option<std::path::PathBuf>>> = Arc::default();
    {
        let (data_dir, weak) = (data_dir.to_path_buf(), app.as_weak());
        app.on_backup_save(move || {
            let (data_dir, weak) = (data_dir.clone(), weak.clone());
            std::thread::spawn(move || {
                let Some(target) = rfd::FileDialog::new().set_title(lang::current().save_backup).set_file_name("Ember-backup.zip").add_filter("Ember backup", &["zip"]).save_file() else { return };
                let t = lang::current();
                let name = target.file_name().map(|n| n.to_string_lossy().into_owned()).unwrap_or_default();
                let message = match ember_engine::backup::write(&data_dir, &target) {
                    Ok(()) => strings::fill(t.backup_saved, &[("name", &name)]),
                    Err(e) => strings::fill(t.backup_failed, &[("reason", &e)]),
                };
                let _ = weak.upgrade_in_event_loop(move |app| app.set_backup_message(message.into()));
            });
        });
    }
    {
        let (pending, weak) = (Arc::clone(&pending), app.as_weak());
        app.on_backup_restore(move || {
            let (pending, weak) = (Arc::clone(&pending), weak.clone());
            std::thread::spawn(move || {
                let Some(file) = rfd::FileDialog::new().set_title(lang::current().restore_backup).add_filter("Ember backup", &["zip"]).pick_file() else { return };
                let name = file.file_name().map(|n| n.to_string_lossy().into_owned()).unwrap_or_default();
                *pending.lock().unwrap() = Some(file);
                let text = strings::fill(lang::current().restore_confirm, &[("name", &name)]);
                let _ = weak.upgrade_in_event_loop(move |app| {
                    app.set_restore_text(text.into());
                    app.set_restore_open(true);
                });
            });
        });
    }
    {
        let (pending, weak) = (Arc::clone(&pending), app.as_weak());
        app.on_restore_cancel(move || {
            *pending.lock().unwrap() = None;
            if let Some(app) = weak.upgrade() {
                app.set_restore_open(false);
            }
        });
    }
    {
        let (engine, data_dir, weak) = (engine.clone(), data_dir.to_path_buf(), app.as_weak());
        app.on_restore_confirm(move || {
            let Some(app) = weak.upgrade() else { return };
            app.set_restore_open(false);
            let Some(file) = pending.lock().unwrap().take() else { return };
            let (engine, data_dir) = (engine.clone(), data_dir.clone());
            std::thread::spawn(move || {
                // stop everything first: the running app would write its own copies of these files over the restored ones
                engine.shutdown();
                if let Err(e) = ember_engine::backup::restore(&data_dir, &file) {
                    ember_engine::log::warn("Ember", &format!("restore failed: {e}"));
                }
                // start again either way: the app is stopped now, and a failed restore left the old files as they were
                let _ = ember_win::single_instance::relaunch(&["--restarted"]);
                std::process::exit(0);
            });
        });
    }
}

/// What the skip list stores for what was typed: a pasted link becomes its host name (as in the Kotlin app).
fn site_or_address(text: &str) -> String {
    let t = text.trim();
    if ember_engine::skip::is_address(t) {
        return t.to_string(); // an address or range stays as typed
    }
    let after_scheme = t.split_once("://").map_or(t, |(_, rest)| rest);
    let host = after_scheme.split(['/', '?', '#']).next().unwrap_or("");
    // drop a user name and a port
    let host = host.rsplit('@').next().unwrap_or(host);
    let host = if host.matches(':').count() == 1 { host.split(':').next().unwrap_or(host) } else { host };
    host.to_lowercase()
}

/// A program file name for the app rules: what was typed, or the last part of a path, with `.exe` when it has no extension.
fn program_name(text: &str) -> String {
    let name = text.trim().trim_matches('"').rsplit(['\\', '/']).next().unwrap_or("").replace(';', "");
    if name.is_empty() || name.contains('.') {
        name
    } else {
        format!("{name}.exe")
    }
}

/// The Logs page's buttons.
fn wire_logs_page(app: &App, ui: &Rc<logs::LogsUi>) {
    let toasts = Arc::new(AtomicU32::new(0));
    {
        let (ui, weak) = (Rc::clone(ui), app.as_weak());
        app.on_log_filter_changed(move |_| {
            if let Some(app) = weak.upgrade() {
                logs::apply(&app, &ui, true);
            }
        });
    }
    {
        let (weak, toasts) = (app.as_weak(), Arc::clone(&toasts));
        app.on_copy_logs(move || {
            let Some(app) = weak.upgrade() else { return };
            let lines = logs::filtered(app.get_log_filter());
            if set_clipboard(&lines.join("\n")) {
                show_toast(&app, &toasts, lang::current().copied, false);
            }
        });
    }
    {
        let (ui, weak) = (Rc::clone(ui), app.as_weak());
        app.on_clear_logs(move || {
            ember_engine::log::clear();
            if let Some(app) = weak.upgrade() {
                logs::apply(&app, &ui, true);
            }
        });
    }
    {
        let (weak, toasts) = (app.as_weak(), Arc::clone(&toasts));
        app.on_copy_report(move || {
            let Some(app) = weak.upgrade() else { return };
            let entries = ember_engine::log::entries();
            let recent: Vec<String> = entries[entries.len().saturating_sub(120)..].iter().map(logs::line).collect();
            let report = ember_engine::diagnostics::report(env!("CARGO_PKG_VERSION"), &ember_engine::diagnostics::system_lines("software"), &recent);
            if set_clipboard(&report) {
                show_toast(&app, &toasts, lang::current().report_copied, false);
            }
        });
    }
}


/// Shows a message at the top of the Servers page for a few seconds.
fn show_notice(app: &App, generation: &Arc<AtomicU32>, text: SharedString, error: bool) {
    let mine = generation.fetch_add(1, Ordering::SeqCst) + 1;
    app.set_page_notice(text);
    app.set_page_notice_error(error);
    let (weak, generation) = (app.as_weak(), Arc::clone(generation));
    slint::Timer::single_shot(Duration::from_secs(6), move || {
        if generation.load(Ordering::SeqCst) == mine {
            if let Some(app) = weak.upgrade() {
                app.set_page_notice(SharedString::new());
            }
        }
    });
}

fn lock_set(set: &std::sync::Mutex<std::collections::HashSet<String>>) -> std::sync::MutexGuard<'_, std::collections::HashSet<String>> {
    set.lock().unwrap_or_else(|e| e.into_inner())
}

/// The Servers page's buttons.
fn wire_servers_page(app: &App, engine: &Engine, ui: &Rc<servers::ServersUi>, dirty: &Arc<AtomicBool>) {
    let notices = Arc::new(AtomicU32::new(0));
    // ---- file import, WARP, QR code, website check
    app.set_site_presets(ModelRc::new(VecModel::from(
        ember_subs::tester::SITE_PRESETS.iter().map(|(name, address)| SitePreset { name: (*name).into(), address: (*address).into() }).collect::<Vec<_>>(),
    )));
    {
        let (engine, dirty, notices, weak) = (engine.clone(), Arc::clone(dirty), Arc::clone(&notices), app.as_weak());
        app.on_import_files(move || {
            let (engine, dirty, notices, weak) = (engine.clone(), Arc::clone(&dirty), Arc::clone(&notices), weak.clone());
            // the file dialog blocks, and reading a link can take a while, so both happen off the window's thread
            std::thread::spawn(move || {
                let t = lang::current();
                let Some(files) = rfd::FileDialog::new().set_title(t.choose_file_title).add_filter("Server files", &["json", "conf", "txt", "ovpn"]).pick_files() else { return };
                let (mut added, mut problems) = (0usize, Vec::<String>::new());
                for file in files {
                    let name = file.file_name().map(|n| n.to_string_lossy().into_owned()).unwrap_or_default();
                    let stem = file.file_stem().map(|n| n.to_string_lossy().into_owned()).unwrap_or_default();
                    // a server file is small; anything bigger is not one
                    if std::fs::metadata(&file).is_ok_and(|m| m.len() > 2_000_000) {
                        problems.push(strings::fill(t.file_too_large, &[("0", &name)]));
                        continue;
                    }
                    match std::fs::read(&file) {
                        Ok(bytes) => match engine.store().add(&String::from_utf8_lossy(&bytes), &stem) {
                            Ok(sub) => added += sub.nodes.iter().filter(|n| !n.is_info()).count(),
                            Err(e) => problems.push(format!("{name}: {e}")),
                        },
                        Err(e) => problems.push(format!("{name}: {e}")),
                    }
                }
                dirty.store(true, Ordering::SeqCst);
                let _ = weak.upgrade_in_event_loop(move |app| {
                    if !problems.is_empty() {
                        show_notice(&app, &notices, problems.join("\n").into(), true);
                    } else if added > 0 {
                        show_notice(&app, &notices, servers::added_message(lang::current(), added), false);
                    }
                });
            });
        });
    }
    {
        let (engine, dirty, notices, weak) = (engine.clone(), Arc::clone(dirty), Arc::clone(&notices), app.as_weak());
        app.on_add_warp(move || {
            if let Some(app) = weak.upgrade() {
                show_notice(&app, &notices, lang::current().warp_registering.into(), false);
            }
            let (engine, dirty, notices, weak) = (engine.clone(), Arc::clone(&dirty), Arc::clone(&notices), weak.clone());
            std::thread::spawn(move || {
                let outcome = engine.store().add_warp();
                dirty.store(true, Ordering::SeqCst);
                let _ = weak.upgrade_in_event_loop(move |app| match outcome {
                    Ok(sub) => show_notice(&app, &notices, servers::added_message(lang::current(), sub.nodes.len()), false),
                    Err(e) => show_notice(&app, &notices, e.into(), true),
                });
            });
        });
    }
    {
        let (engine, weak) = (engine.clone(), app.as_weak());
        app.on_show_qr(move |id| {
            let (Some(app), Some(node)) = (weak.upgrade(), engine.store().node_by_id(&id)) else { return };
            let link = node.share_link().unwrap_or_default().to_string();
            let image = qr_image(&link);
            app.set_qr_title(node.title().into());
            app.set_qr_link(link.into());
            app.set_qr_has_image(image.is_some());
            app.set_qr_image(image.unwrap_or_default());
            app.set_qr_open(true);
        });
    }
    {
        let (weak, notices) = (app.as_weak(), Arc::clone(&notices));
        app.on_copy_link(move |link| {
            if let Some(app) = weak.upgrade() {
                if set_clipboard(&link) {
                    show_notice(&app, &notices, lang::current().copied.into(), false);
                }
            }
        });
    }
    {
        let (engine, dirty, notices, weak) = (engine.clone(), Arc::clone(dirty), Arc::clone(&notices), app.as_weak());
        app.on_check_site(move |input| {
            let Some(app) = weak.upgrade() else { return };
            let Some(url) = ember_subs::tester::normalize_site_url(&input) else {
                show_notice(&app, &notices, lang::current().site_hint.into(), true);
                return;
            };
            // a preset keeps its name; anything else is named by its host
            let label = ember_subs::tester::SITE_PRESETS
                .iter()
                .find(|(_, address)| *address == input.trim())
                .map(|(name, _)| (*name).to_string())
                .unwrap_or_else(|| ember_subs::subscription::url_host(&url).unwrap_or_else(|| url.clone()).trim_start_matches("www.").to_string());
            let nodes: Vec<_> = engine.store().all_nodes().into_iter().filter(|n| !n.is_info()).collect();
            engine.check_site(nodes, label, url);
            dirty.store(true, Ordering::SeqCst);
        });
    }
    {
        let (engine, dirty) = (engine.clone(), Arc::clone(dirty));
        app.on_clear_site_check(move || {
            engine.clear_site_check();
            dirty.store(true, Ordering::SeqCst);
        });
    }
    {
        let (ui, dirty) = (Rc::clone(ui), Arc::clone(dirty));
        app.on_toggle_expanded(move |id| {
            {
                let mut open = ui.expanded.borrow_mut();
                if !open.remove(id.as_str()) { open.insert(id.to_string()); *ui.opened.borrow_mut() = Some(id.to_string()); }
            }
            ui.invalidate();
            dirty.store(true, Ordering::SeqCst);
        });
    }
    {
        let (engine, dirty) = (engine.clone(), Arc::clone(dirty));
        app.on_favorite(move |id| {
            if let Some(node) = engine.store().node_by_id(&id) {
                engine.toggle_favorite(&node);
                dirty.store(true, Ordering::SeqCst);
            }
        });
    }
    {
        let engine = engine.clone();
        app.on_test_subscription(move |id| {
            if let Some(sub) = engine.store().subscriptions().into_iter().find(|s| s.id == id.as_str()) {
                engine.test_nodes(sub.nodes, true);
            }
        });
    }
    {
        let engine = engine.clone();
        app.on_ping_subscription(move |id| {
            if let Some(sub) = engine.store().subscriptions().into_iter().find(|s| s.id == id.as_str()) {
                engine.test_nodes(sub.nodes, false);
            }
        });
    }
    {
        let (engine, ui, dirty, notices, weak) = (engine.clone(), Rc::clone(ui), Arc::clone(dirty), Arc::clone(&notices), app.as_weak());
        let refreshing = Arc::clone(&ui.refreshing);
        app.on_refresh_subscription(move |id| {
            let id = id.to_string();
            lock_set(&refreshing).insert(id.clone());
            ui.invalidate();
            dirty.store(true, Ordering::SeqCst);
            let (engine, dirty, notices, weak, refreshing) =
                (engine.clone(), Arc::clone(&dirty), Arc::clone(&notices), weak.clone(), Arc::clone(&refreshing));
            std::thread::spawn(move || {
                let outcome = engine.store().refresh(&id);
                lock_set(&refreshing).remove(&id);
                dirty.store(true, Ordering::SeqCst);
                let _ = weak.upgrade_in_event_loop(move |app| match outcome {
                    Ok(()) => show_notice(&app, &notices, lang::current().updated.into(), false),
                    Err(e) => show_notice(&app, &notices, e.into(), true),
                });
            });
        });
    }
    {
        let (engine, ui, dirty) = (engine.clone(), Rc::clone(ui), Arc::clone(dirty));
        app.on_remove_subscription(move |id| {
            let store = engine.store();
            let selected = engine.snapshot().selected_id;
            if store.subscriptions().iter().any(|s| s.id == id.as_str() && s.nodes.iter().any(|n| n.id == selected)) {
                engine.choose_server("");
            }
            store.remove(&id);
            ui.invalidate();
            dirty.store(true, Ordering::SeqCst);
        });
    }
    {
        let weak = app.as_weak();
        let serial = std::cell::Cell::new(0);
        app.on_paste_request(move || {
            let Some(app) = weak.upgrade() else { return };
            let clipboard = arboard::Clipboard::new().and_then(|mut c| c.get_text()).unwrap_or_default();
            let clipboard = clipboard.trim();
            if clipboard.is_empty() {
                app.set_dialog_error(lang::current().clipboard_empty.into());
                return;
            }
            app.set_dialog_error(SharedString::new());
            app.set_pasted_text(clipboard.into());
            serial.set(serial.get() + 1);
            app.set_paste_serial(serial.get());
        });
    }
    {
        let (engine, dirty, notices, weak) = (engine.clone(), Arc::clone(dirty), Arc::clone(&notices), app.as_weak());
        let done = Arc::new(AtomicU32::new(0));
        app.on_submit_subscription(move |id, link, name| {
            let Some(app) = weak.upgrade() else { return };
            app.set_dialog_busy(true);
            app.set_dialog_error(SharedString::new());
            let (id, link, name) = (id.to_string(), link.to_string(), name.to_string());
            let (engine, dirty, notices, weak, done) = (engine.clone(), Arc::clone(&dirty), Arc::clone(&notices), weak.clone(), Arc::clone(&done));
            std::thread::spawn(move || {
                // adding reads the link over the network, so it happens off the window's thread
                let outcome = if id.is_empty() {
                    engine.store().add(&link, &name).map(|sub| sub.nodes.iter().filter(|n| !n.is_info()).count())
                } else {
                    engine.store().update(&id, &name, &link).map(|_| 0)
                };
                dirty.store(true, Ordering::SeqCst);
                let _ = weak.upgrade_in_event_loop(move |app| {
                    app.set_dialog_busy(false);
                    match outcome {
                        Ok(count) => {
                            app.set_dialog_done(done.fetch_add(1, Ordering::SeqCst) as i32 + 1);
                            let message = if id.is_empty() { servers::added_message(lang::current(), count) } else { lang::current().updated.into() };
                            show_notice(&app, &notices, message, false);
                        }
                        Err(e) => app.set_dialog_error(e.into()),
                    }
                });
            });
        });
    }
}

const TRAY_OPEN: u32 = 1;
const TRAY_TOGGLE: u32 = 2;
const TRAY_QUIT: u32 = 3;

fn tray_menu(s: &strings::Strings, state: ConnState) -> Vec<ember_win::tray::MenuEntry> {
    use ember_win::tray::MenuEntry;
    let toggle = if state == ConnState::Disconnected { s.connect } else { s.disconnect };
    vec![MenuEntry::item(TRAY_OPEN, s.open_ember), MenuEntry::item(TRAY_TOGGLE, toggle), MenuEntry::Separator, MenuEntry::item(TRAY_QUIT, s.quit)]
}

fn main() {
    let args: Vec<String> = std::env::args().collect();
    let has = |a: &str| args.iter().any(|x| x == a);

    let data_dir = backend::data_dir(&args);
    // the first-run copy of the Kotlin app's servers is only for the default folder, never for a test folder
    let custom_dir = has("--data-dir") || std::env::var_os("EMBER_DATA_DIR").is_some();
    // one Ember at a time (a test run with its own data folder may run beside the real one)
    let _single = if custom_dir {
        None
    } else {
        // a copy started by a restore waits a moment for the old one to let go of the lock
        let tries = if has("--restarted") { 50 } else { 1 };
        let mut guard = None;
        for _ in 0..tries {
            guard = ember_win::single_instance::acquire();
            if guard.is_some() {
                break;
            }
            std::thread::sleep(Duration::from_millis(100));
        }
        match guard {
            Some(guard) => Some(guard),
            None => return,
        }
    };
    let imported = if custom_dir { Vec::new() } else { backend::import_from_kotlin_app(&data_dir) };

    // the engine tells us it changed from any thread; the timer below picks the flag up on the UI thread
    let dirty = Arc::new(AtomicBool::new(true));
    let flag_dirty = Arc::clone(&dirty);
    let ports = ports(&args);
    let engine =
        Engine::with_ports(&data_dir, backend::find_xray(), "en", false, ports, move || flag_dirty.store(true, Ordering::SeqCst))
            .expect("could not open Ember's data folder");
    if !imported.is_empty() {
        ember_engine::log::info("Ember", &format!("first run: copied {} from the Kotlin app's folder", imported.join(" and ")));
    }
    if backend::find_xray().is_none() {
        ember_engine::log::warn("Ember", "xray.exe was not found; set EMBER_XRAY to its path");
    }

    let app = App::new().unwrap();
    // --light / --dark force the look (for screenshots); otherwise the Appearance setting decides
    let forced_theme = if has("--light") { Some(0) } else if has("--dark") { Some(1) } else if has("--mint") { Some(2) } else { None };
    app.global::<Palette>().set_flavour(flavour(&engine.settings().theme(), forced_theme));
    let language = if has("--fa") { "fa".to_string() } else { engine.settings().language() };
    lang::set(&language);
    apply_language(&app, &language);
    let rows_model = Rc::new(VecModel::<ServerRow>::default());
    app.set_servers(ModelRc::from(rows_model.clone()));
    let servers_ui = Rc::new(servers::ServersUi::default());
    wire_servers_page(&app, &engine, &servers_ui, &dirty);
    let logs_ui = Rc::new(logs::LogsUi::default());
    wire_logs_page(&app, &logs_ui);
    AUTOSTART.store(ember_win::autostart::is_enabled(), Ordering::SeqCst);
    wire_settings_page(&app, &engine, forced_theme, &servers_ui, &logs_ui, &dirty, &data_dir);
    push_settings(&app, &engine);
    push_static_pages(&app, &data_dir);
    app.on_open_contact(|| {
        ember_win::shell::open_url(about::CONTACT_LINK);
    });
    {
        let engine = engine.clone();
        app.on_restore_internet(move || engine.restore_internet());
    }
    {
        let engine = engine.clone();
        app.on_cred_submit(move |user, pass| {
            let ask_user = engine.snapshot().credential_request.is_some_and(|r| r.ask_user);
            engine.answer_credentials(Some(ember_win::openvpn::OpenVpnCredentials {
                username: ask_user.then(|| user.to_string()),
                password: pass.to_string(),
            }));
        });
    }
    {
        let engine = engine.clone();
        app.on_cred_cancel(move || engine.answer_credentials(None));
    }
    DEMO_BLOCKED.store(has("--demo-blocked"), Ordering::SeqCst);
    DEMO_CRED.store(has("--demo-cred"), Ordering::SeqCst);
    if has("--expand") {
        for sub in engine.store().subscriptions() {
            servers_ui.expanded.borrow_mut().insert(sub.id);
        }
    }
    refresh(&app, &engine, &rows_model, lang::current(), ports);
    servers::apply(&app, &engine, lang::current(), &servers_ui);
    if has("--servers") {
        app.set_page(1);
    }
    if has("--logs") {
        app.set_page(3);
    }
    if has("--settings") {
        app.set_page(2);
    }
    if has("--whatsnew") {
        app.set_page(4);
    }
    if has("--about") {
        app.set_page(5);
    }
    if has("--demo-log") {
        // made-up values only; they let the redaction of the copied report be checked
        use ember_engine::log::{error, info, warn};
        info("Xray", "started xray.exe (pid 12584)");
        info("EmberEngine", "connected via a vless server (proxy mode) after 789 ms");
        warn("NodeTester", "could not look up sv0.example.ir");
        warn("Subscriptions", "retrying vless://11111111-2222-3333-4444-555555555555@fdh11.example.top:8080?security=reality#UK for 203.0.113.7");
        error("EmberEngine", "connecting failed: key 3q2-9Ab_cdEFghIJklMNopQRstUVwxYZ012345 was refused");
    }

    {
        let engine = engine.clone();
        let weak = app.as_weak();
        app.on_orb_clicked(move || {
            if engine.snapshot().state != ConnState::Disconnected {
                engine.disconnect();
            } else if engine.selected_node().is_none() {
                weak.unwrap().set_page(1); // nothing to connect to yet: the Servers page
            } else {
                engine.connect_selected();
            }
        });
    }
    {
        let engine = engine.clone();
        app.on_choose(move |id| engine.choose_server(&id));
    }
    {
        let engine = engine.clone();
        app.on_cancel_test(move || engine.cancel_test());
    }

    // like the Kotlin app: with "connect on start" on and a server chosen, connect straight away
    if engine.settings().auto_connect() && engine.selected_node().is_some() && !has("--no-auto") {
        engine.connect_selected();
    }

    // the notification-area icon: Open, Connect/Disconnect, Quit (test runs with their own folder skip it unless --tray)
    let tray = if custom_dir && !has("--tray") {
        None
    } else {
        let wake: Box<dyn Fn() + Send> = Box::new(|| {});
        ember_win::tray::Tray::spawn("Ember", tray_menu(lang::current(), ConnState::Disconnected), wake)
    };
    {
        use slint::winit_030::{winit, WinitWindowAccessor};
        let w = app.as_weak();
        app.on_window_drag(move || {
            if let Some(a) = w.upgrade() { a.window().with_winit_window(|win| { let _ = win.drag_window(); }); }
        });
        let w = app.as_weak();
        app.on_window_resize(move |edge| {
            use winit::window::ResizeDirection as D;
            let d = match edge { 1 => D::West, 2 => D::East, 3 => D::North, 4 => D::South, 5 => D::NorthWest, 6 => D::NorthEast, 7 => D::SouthWest, _ => D::SouthEast };
            if let Some(a) = w.upgrade() { a.window().with_winit_window(|win| { let _ = win.drag_resize_window(d); }); }
        });
        let w = app.as_weak();
        app.on_window_minimize(move || { if let Some(a) = w.upgrade() { a.window().with_winit_window(|win| win.set_minimized(true)); } });
        let w = app.as_weak();
        app.on_window_maximize(move || { if let Some(a) = w.upgrade() { a.window().with_winit_window(|win| win.set_maximized(!win.is_maximized())); } });
        let w = app.as_weak();
        app.on_window_close(move || {
            if let Some(a) = w.upgrade() {
                a.window().dispatch_event(slint::platform::WindowEvent::CloseRequested);
            }
        });
    }
    {
        let (engine, has_tray) = (engine.clone(), tray.is_some());
        app.window().on_close_requested(move || {
            if !(has_tray && engine.settings().keep_in_tray()) {
                let _ = slint::quit_event_loop();
            }
            slint::CloseRequestResponse::HideWindow
        });
    }
    let has_tray = tray.is_some();
    let mut tray_state = None;
    let mut chimed = ConnState::Disconnected;

    let timer = slint::Timer::default();
    let weak = app.as_weak();
    let engine_for_timer = engine.clone();
    let state_cell = Rc::new(std::cell::Cell::new(ConnState::Disconnected));
    let state_for_main = Rc::clone(&state_cell);
    let servers_for_motion = Rc::clone(&servers_ui);
    let touch_cell = Rc::new(std::cell::Cell::new(0i32));
    {
        let touch_cell = Rc::clone(&touch_cell);
        app.on_orb_touch(move |s| touch_cell.set(s));
    }
    let (demo_connecting, demo_connected, demo_settled) = (has("--demo-orb-connecting"), has("--demo-orb-connected"), has("--demo-orb-settled"));
    let mut motion =
        OrbMotion { lit: 0.0, speed: 0.12, loader: 0.0, angle: 0.0, ripple: 1.0, pop: 1.0, pop_v: 0.0, feel: 1.0, list_t: 2.0, row_t: 2.0, row_card: String::new(), sprites: SpriteCache::default(), frame: 0, page_in: 1.0, last_page: 0, last: Instant::now(), previous: ConnState::Disconnected };
    let mut tick = 0u32;
    
    let menu_at: u32 = if has("--menu") { 60 } else { 0 }; // debug: open the server menu after about two seconds
    let dialog_at: u32 = if has("--dialog") { 40 } else { 0 }; // debug: open the add dialog after about a second
    let connect_at: u32 = if has("--connect") { 45 } else { 0 }; // debug: press Connect after about 1.5 seconds
    timer.start(slint::TimerMode::Repeated, Duration::from_millis(33), move || {
        let app = weak.unwrap();
        let engine = &engine_for_timer;
        tick += 1;
        // while Appearance is "System", follow Windows when its setting changes (checked every two seconds)
        if forced_theme.is_none() && tick.is_multiple_of(60) && engine.settings().theme() == "System" {
            let now = flavour("System", None);
            if app.global::<Palette>().get_flavour() != now {
                app.global::<Palette>().set_flavour(now);
            }
        }
        if menu_at != 0 && tick == menu_at {
            app.set_menu_request(1);
        }
        if dialog_at != 0 && tick == dialog_at {
            app.set_dialog_request(1);
        }
        if connect_at != 0 && tick == connect_at {
            engine.connect_selected();
        }
        let state = engine.snapshot().state;
        // a soft chime when the connection comes up, and a lower one when it was up and goes down
        if state != chimed {
            if engine.settings().sounds() {
                if state == ConnState::Connected {
                    let _ = ember_win::sound::play(ember_win::sound::Chime::Connected);
                } else if chimed == ConnState::Connected {
                    let _ = ember_win::sound::play(ember_win::sound::Chime::Disconnected);
                }
            }
            chimed = state;
        }
        if let Some((tray, events)) = &tray {
            while let Ok(event) = events.try_recv() {
                match event {
                    ember_win::tray::TrayEvent::Open | ember_win::tray::TrayEvent::Command(TRAY_OPEN) => {
                        let _ = app.show();
                        style_window_soon(&app);
                    }
                    ember_win::tray::TrayEvent::Command(TRAY_TOGGLE) => {
                        if engine.snapshot().state == ConnState::Disconnected {
                            engine.connect_selected();
                        } else {
                            engine.disconnect();
                        }
                    }
                    ember_win::tray::TrayEvent::Command(TRAY_QUIT) => {
                        let _ = slint::quit_event_loop();
                    }
                    ember_win::tray::TrayEvent::Command(_) => {}
                }
            }
            // the menu and tooltip follow the state and the language
            let key = (state, lang::code());
            if tray_state != Some(key) {
                tray_state = Some(key);
                tray.set_menu(tray_menu(lang::current(), state));
            }
        }
        // the log page follows the log twice a second while it is open
        if tick.is_multiple_of(15) && app.get_page() == 3 {
            logs::apply(&app, &logs_ui, false);
        }
        // refresh when the engine said something changed, and once a second while connected (the elapsed time)
        if dirty.swap(false, Ordering::SeqCst) || (state == ConnState::Connected && tick.is_multiple_of(30)) {
            refresh(&app, engine, &rows_model, lang::current(), ports);
            servers::apply(&app, engine, lang::current(), &servers_ui);
            if app.get_page() == 2 {
                push_settings(&app, engine);
            }
        }
        // debug: --demo-orb-connecting / -connected (switches 0.4 s before a 5 s screenshot) / -settled show the orb states without a server
        let shown = if demo_connecting {
            ConnState::Connecting
        } else if demo_connected {
            if tick >= 140 { ConnState::Connected } else { ConnState::Disconnected }
        } else if demo_settled {
            if tick >= 20 { ConnState::Connected } else { ConnState::Disconnected }
        } else {
            state
        };
        state_for_main.set(shown);
    });
    let motion_timer = slint::Timer::default();
    {
        let weak = app.as_weak();
        let state_cell = Rc::clone(&state_cell);
        let touch_cell = Rc::clone(&touch_cell);
        let servers_ui = Rc::clone(&servers_for_motion);
        motion_timer.start(slint::TimerMode::Repeated, Duration::from_millis(16), move || {
            let Some(app) = weak.upgrade() else { return };
            let state = state_cell.get();
            let now = Instant::now();
            let dt = now.duration_since(motion.last).as_secs_f32().min(0.1);
            let touch_target = [1.0, 1.02, 0.965][touch_cell.get().clamp(0, 2) as usize];
            // nothing is drawn while the window is hidden (tray); the orb catches up in a frame or two when it shows again
        if !app.window().is_visible() {
            motion.last = now;
            return;
        }
        // frames are the CPU cost, so the orb only runs at 60 per second while it really moves: connecting, a change in
        // progress. Settled and connected it drifts at 20, and disconnected at rest at 10.
        let settled = motion.ripple >= 1.0
            && (motion.pop - 1.0).abs() < 0.0005
            && motion.pop_v.abs() < 0.001
            && motion.page_in >= 1.0 && motion.list_t >= 2.0 && motion.row_t >= 2.0 && motion.last_page == app.get_page() && (motion.feel - touch_target).abs() < 0.0005 && motion.loader < 0.002;
        let every = match state {
            ConnState::Disconnected if settled && motion.lit < 0.002 => 6,
            ConnState::Connected if settled && motion.lit > 0.995 => 3,
            _ => 1,
        };
        motion.frame = motion.frame.wrapping_add(1);
        if !motion.frame.is_multiple_of(every) {
            return;
        }
        motion.last = now;
            if state == ConnState::Connected && motion.previous != ConnState::Connected {
                motion.ripple = 0.0;
                motion.pop = 0.93;
                motion.pop_v = 0.0;
            }
            motion.previous = state;
            // a page change restarts the short fade-and-rise of the page (eased out, a quarter of a second)
            let page = app.get_page();
            if page != motion.last_page {
                motion.last_page = page; motion.page_in = 0.0; motion.list_t = 0.0; } motion.page_in = (motion.page_in + dt / 0.25).min(1.0); motion.list_t = (motion.list_t + dt).min(2.0);
            if let Some(id) = servers_ui.opened.borrow_mut().take() {
                motion.row_card = id;
                motion.row_t = 0.0;
            }
            motion.row_t = (motion.row_t + dt).min(2.0); motion.feel = approach(motion.feel, touch_target, dt, 0.08);
            let (lit_t, speed_t) = match state {
                ConnState::Disconnected => (0.0, 0.12),
                ConnState::Connecting => (0.55, 3.2),
                ConnState::Connected => (1.0, 0.45),
            };
            motion.lit = approach(motion.lit, lit_t, dt, if state == ConnState::Disconnected { 0.5 } else { 0.4 });
            motion.speed = approach(motion.speed, speed_t, dt, 0.35);
            motion.loader = approach(motion.loader, if state == ConnState::Connecting { 1.0 } else { 0.0 }, dt, 0.15);
            motion.angle = (motion.angle + dt * motion.speed) % LOOP;
            if motion.ripple < 1.0 {
                motion.ripple = (motion.ripple + dt / 1.4).min(1.0);
            }
            // the sphere settles back to full size like a spring (a small overshoot), not a plain ease
            let mut left = dt;
            while left > 0.0 {
                let h = left.min(1.0 / 240.0);
                motion.pop_v += (240.0 * (1.0 - motion.pop) - 15.0 * motion.pop_v) * h;
                motion.pop += motion.pop_v * h;
                left -= h;
            }
            // the sphere itself is made again for its current pixel size (176 logical, scaled by the spring and the pointer)
            let scale = app.window().scale_factor();
            let diameter = ((176.0 * scale * motion.pop * motion.feel).round() as u32).max(16);
            motion.sprites.ensure(&app, diameter, scale, motion.lit, app.global::<Palette>().get_mint());
            let m = app.global::<Motion>();
            m.set_feel(motion.feel);
            m.set_list_t(motion.list_t);
            m.set_row_t(motion.row_t);
            m.set_row_card(motion.row_card.as_str().into());
            m.set_angle(motion.angle);
            m.set_lit(motion.lit);
            m.set_loader(motion.loader);
            m.set_ripple(motion.ripple);
            m.set_pop(motion.pop);
            m.set_page_in(1.0 - (1.0 - motion.page_in).powi(3));
    });
    }
    // started with Windows: stay in the tray, no window
    if !(has("--minimized") && has_tray) {
        app.show().unwrap();
        style_window_soon(&app);
    }
    slint::run_event_loop_until_quit().unwrap();
    drop(motion_timer);
    drop(timer); // takes the tray icon with it
    engine.shutdown();
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_pasted_link_becomes_its_host_name_for_the_skip_list() {
        assert_eq!(site_or_address("https://Bank.Example.com/login?x=1"), "bank.example.com");
        assert_eq!(site_or_address("  open.spotify.com  "), "open.spotify.com");
        assert_eq!(site_or_address("http://user:pw@example.com:8443/a"), "example.com");
        assert_eq!(site_or_address("10.0.0.0/8"), "10.0.0.0/8");
        assert_eq!(site_or_address("192.0.2.7"), "192.0.2.7");
    }

    #[test]
    fn a_program_is_stored_by_file_name() {
        assert_eq!(program_name(r#""C:\Program Files\Mozilla Firefox\firefox.exe""#), "firefox.exe");
        assert_eq!(program_name("chrome"), "chrome.exe");
        assert_eq!(program_name("a;b.exe"), "ab.exe");
        assert_eq!(program_name("   "), "");
    }
}
