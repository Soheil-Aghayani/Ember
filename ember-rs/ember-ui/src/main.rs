mod backend;
mod sphere;
mod strings;

slint::include_modules!();

use ember_engine::{ConnState, Engine, HTTP_PORT, LOCAL_BIND, SOCKS_PORT};
use slint::{ComponentHandle, Image, Model, ModelRc, SharedString, VecModel};
use std::{
    path::PathBuf,
    rc::Rc,
    sync::{
        atomic::{AtomicBool, Ordering},
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
fn flag(code: &str) -> Option<Image> {
    let name = format!("flags/{}.svg", code.to_lowercase());
    let beside_exe = std::env::current_exe().ok().and_then(|p| p.parent().map(|d| d.join(&name)));
    let in_source = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("ui/assets").join(&name);
    [beside_exe, Some(in_source)].into_iter().flatten().find(|p| p.exists()).and_then(|p| Image::load_from_path(&p).ok())
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
fn refresh(app: &App, engine: &Engine, model: &VecModel<ServerRow>, s: &strings::Strings) {
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
            &[("socks", &format!("{LOCAL_BIND}:{SOCKS_PORT}")), ("http", &format!("{LOCAL_BIND}:{HTTP_PORT}"))],
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
}

fn main() {
    let args: Vec<String> = std::env::args().collect();
    let has = |a: &str| args.iter().any(|x| x == a);

    let data_dir = backend::data_dir(&args);
    // the first-run copy of the Kotlin app's servers is only for the default folder, never for a test folder
    let custom_dir = has("--data-dir") || std::env::var_os("EMBER_DATA_DIR").is_some();
    let imported = if custom_dir { Vec::new() } else { backend::import_from_kotlin_app(&data_dir) };

    // the engine tells us it changed from any thread; the timer below picks the flag up on the UI thread
    let dirty = Arc::new(AtomicBool::new(true));
    let flag_dirty = Arc::clone(&dirty);
    let engine = Engine::new(&data_dir, backend::find_xray(), "en", false, move || flag_dirty.store(true, Ordering::SeqCst))
        .expect("could not open Ember's data folder");
    if !imported.is_empty() {
        ember_engine::log::info("Ember", &format!("first run: copied {} from the Kotlin app's folder", imported.join(" and ")));
    }
    if backend::find_xray().is_none() {
        ember_engine::log::warn("Ember", "xray.exe was not found; set EMBER_XRAY to its path");
    }

    let app = App::new().unwrap();
    app.global::<Palette>().set_dark(!has("--light"));
    let language = if has("--fa") { "fa".to_string() } else { engine.settings().language() };
    apply_language(&app, &language);
    let rows_model = Rc::new(VecModel::<ServerRow>::default());
    app.set_servers(ModelRc::from(rows_model.clone()));
    let text = strings::for_language(&language);
    refresh(&app, &engine, &rows_model, text);
    if has("--servers") {
        app.set_page(1);
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

    let timer = slint::Timer::default();
    let weak = app.as_weak();
    let engine_for_timer = engine.clone();
    let mut motion =
        OrbMotion { lit: 0.0, speed: 0.12, loader: 0.0, angle: 0.0, ripple: 1.0, pop: 1.0, last: Instant::now(), previous: ConnState::Disconnected };
    let mut tick = 0u32;
    let mut sprite_px = 0u32;
    let menu_at: u32 = if has("--menu") { 60 } else { 0 }; // debug: open the server menu after about two seconds
    let connect_at: u32 = if has("--connect") { 45 } else { 0 }; // debug: press Connect after about 1.5 seconds
    timer.start(slint::TimerMode::Repeated, Duration::from_millis(33), move || {
        let app = weak.unwrap();
        let engine = &engine_for_timer;
        tick += 1;
        if menu_at != 0 && tick == menu_at {
            app.set_menu_request(1);
        }
        if connect_at != 0 && tick == connect_at {
            engine.connect_selected();
        }
        let state = engine.snapshot().state;
        // refresh when the engine said something changed, and once a second while connected (the elapsed time)
        if dirty.swap(false, Ordering::SeqCst) || (state == ConnState::Connected && tick.is_multiple_of(30)) {
            refresh(&app, engine, &rows_model, text);
        }
        // the sphere is drawn at the exact pixel size it is shown at (88 logical radius), again if the window moves to a screen with another scale
        let px = (176.0 * app.window().scale_factor()).round() as u32;
        if px != sprite_px {
            sprite_px = px;
            let (off, on) = sphere::sprites(px);
            let m = app.global::<Motion>();
            m.set_sphere_off(off);
            m.set_sphere_on(on);
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
    app.run().unwrap();
    engine.shutdown();
}
