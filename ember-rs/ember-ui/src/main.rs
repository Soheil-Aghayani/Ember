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
    last: Instant,
    previous: ConnState,
}

fn approach(v: f32, target: f32, dt: f32, seconds: f32) -> f32 {
    v + (target - v) * (dt / seconds).min(1.0)
}

/// A round flag from the bundled circle-flags set: next to the exe in an install, in the source tree when developing.
pub(crate) fn flag(code: &str) -> Option<Image> {
    let name = format!("flags/{}.svg", code.to_lowercase());
    let beside_exe = std::env::current_exe().ok().and_then(|p| p.parent().map(|d| d.join(&name)));
    let in_source = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("ui/assets").join(&name);
    [beside_exe, Some(in_source)].into_iter().flatten().find(|p| p.exists()).and_then(|p| Image::load_from_path(&p).ok())
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
    let image = node.as_ref().and_then(|n| n.flag_code()).as_deref().and_then(flag);
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
    app.set_caption(if connected {
        strings::fill(
            s.proxy_mode_caption,
            &[("socks", &format!("{LOCAL_BIND}:{}", ports.0)), ("http", &format!("{LOCAL_BIND}:{}", ports.1))],
        )
        .into()
    } else {
        SharedString::new()
    });
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

/// Whether the window is dark for an Appearance value ("Light", "Dark" or "System").
fn is_dark(theme: &str, forced: Option<bool>) -> bool {
    forced.unwrap_or_else(|| match theme {
        "Light" => false,
        "Dark" => true,
        _ => ember_win::theme::apps_use_dark(),
    })
}

/// Puts the saved settings into the Settings page.
fn push_settings(app: &App, engine: &Engine) {
    let s = engine.settings();
    app.set_set_language(lang::code().into());
    app.set_set_theme(s.theme().into());
    app.set_set_auto_connect(s.auto_connect());
    app.set_set_auto_failover(s.auto_failover());
    app.set_set_diagnostic_log(s.diagnostic_log());
    app.set_set_keep_in_tray(s.keep_in_tray());
    app.set_set_start_with_windows(ember_win::autostart::is_enabled());
}

/// The Settings page's choices and switches.
fn wire_settings_page(
    app: &App,
    engine: &Engine,
    forced_theme: Option<bool>,
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
            app.global::<Palette>().set_dark(is_dark(&theme, forced_theme));
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
                "startWithWindows" => {
                    if !ember_win::autostart::set_enabled(value) {
                        // the switch goes back to what Windows really has (push_settings below)
                        ember_engine::log::warn("Ember", "could not change the Windows startup entry");
                    }
                }
                _ => {}
            }
            push_settings(&app, &engine);
        });
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
    {
        let (ui, dirty) = (Rc::clone(ui), Arc::clone(dirty));
        app.on_toggle_expanded(move |id| {
            {
                let mut open = ui.expanded.borrow_mut();
                if !open.remove(id.as_str()) {
                    open.insert(id.to_string());
                }
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
                engine.test_nodes(sub.nodes);
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
        match ember_win::single_instance::acquire() {
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
    let forced_theme = if has("--light") { Some(false) } else if has("--dark") { Some(true) } else { None };
    app.global::<Palette>().set_dark(is_dark(&engine.settings().theme(), forced_theme));
    let language = if has("--fa") { "fa".to_string() } else { engine.settings().language() };
    lang::set(&language);
    apply_language(&app, &language);
    let rows_model = Rc::new(VecModel::<ServerRow>::default());
    app.set_servers(ModelRc::from(rows_model.clone()));
    let servers_ui = Rc::new(servers::ServersUi::default());
    wire_servers_page(&app, &engine, &servers_ui, &dirty);
    let logs_ui = Rc::new(logs::LogsUi::default());
    wire_logs_page(&app, &logs_ui);
    wire_settings_page(&app, &engine, forced_theme, &servers_ui, &logs_ui, &dirty, &data_dir);
    push_settings(&app, &engine);
    push_static_pages(&app, &data_dir);
    app.on_open_contact(|| {
        ember_win::shell::open_url(about::CONTACT_LINK);
    });
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
        app.on_test_speed(move || {
            let nodes: Vec<_> = engine.store().all_nodes().into_iter().filter(|n| !n.is_info()).collect();
            engine.test_nodes(nodes);
        });
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

    let timer = slint::Timer::default();
    let weak = app.as_weak();
    let engine_for_timer = engine.clone();
    let mut motion =
        OrbMotion { lit: 0.0, speed: 0.12, loader: 0.0, angle: 0.0, ripple: 1.0, pop: 1.0, last: Instant::now(), previous: ConnState::Disconnected };
    let mut tick = 0u32;
    let mut sprite_px = 0u32;
    let menu_at: u32 = if has("--menu") { 60 } else { 0 }; // debug: open the server menu after about two seconds
    let dialog_at: u32 = if has("--dialog") { 40 } else { 0 }; // debug: open the add dialog after about a second
    let connect_at: u32 = if has("--connect") { 45 } else { 0 }; // debug: press Connect after about 1.5 seconds
    timer.start(slint::TimerMode::Repeated, Duration::from_millis(33), move || {
        let app = weak.unwrap();
        let engine = &engine_for_timer;
        tick += 1;
        // while Appearance is "System", follow Windows when its setting changes (checked every two seconds)
        if forced_theme.is_none() && tick.is_multiple_of(60) && engine.settings().theme() == "System" {
            let dark = ember_win::theme::apps_use_dark();
            if app.global::<Palette>().get_dark() != dark {
                app.global::<Palette>().set_dark(dark);
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
        if let Some((tray, events)) = &tray {
            while let Ok(event) = events.try_recv() {
                match event {
                    ember_win::tray::TrayEvent::Open | ember_win::tray::TrayEvent::Command(TRAY_OPEN) => {
                        let _ = app.show();
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
        }
        // the sphere is drawn at the exact pixel size it is shown at (88 logical radius), again if the window moves to a screen with another scale
        let px = (176.0 * app.window().scale_factor()).round() as u32;
        if px != sprite_px {
            sprite_px = px;
            let (off, on) = sphere::sprites(px);
            let m = app.global::<Motion>();
            m.set_sphere_off(off);
            m.set_sphere_on(on);
            m.set_sprite_size((px + 2 * sphere::PAD) as f32 / app.window().scale_factor());
        }
        let now = Instant::now();
        let dt = now.duration_since(motion.last).as_secs_f32().min(0.1);
        motion.last = now;
        if state == ConnState::Connected && motion.previous != ConnState::Connected {
            motion.ripple = 0.0;
            motion.pop = 0.94;
        }
        motion.previous = state;
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
            motion.ripple = (motion.ripple + dt / 1.2).min(1.0);
        }
        motion.pop = approach(motion.pop, 1.0, dt, 0.25);
        let m = app.global::<Motion>();
        m.set_angle(motion.angle);
        m.set_lit(motion.lit);
        m.set_loader(motion.loader);
        m.set_ripple(motion.ripple);
        m.set_pop(motion.pop);
    });
    // started with Windows: stay in the tray, no window
    if !(has("--minimized") && has_tray) {
        app.show().unwrap();
    }
    slint::run_event_loop_until_quit().unwrap();
    drop(timer); // takes the tray icon with it
    engine.shutdown();
}
