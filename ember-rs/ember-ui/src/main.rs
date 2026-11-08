mod sphere;
mod strings;

slint::include_modules!();

use slint::{ComponentHandle, Image, ModelRc, SharedString, VecModel};
use std::{cell::RefCell, path::PathBuf, rc::Rc, time::{Duration, Instant}};

#[derive(Clone, Copy, PartialEq)]
enum State { Off, Connecting, On }

/// Same wrap as `LOOP` in GlassOrb.kt: ten full turns, so every dot returns to where it started.
const LOOP: f32 = 20.0 * std::f32::consts::PI;

struct Server { id: &'static str, title: &'static str, flag: &'static str, detail: &'static str }

// Mock servers until the engine crate is wired in.
const SERVERS: [Server; 4] = [
    Server { id: "1", title: "Germany · Frankfurt", flag: "de", detail: "42 ms" },
    Server { id: "2", title: "United Kingdom · London", flag: "gb", detail: "58 ms" },
    Server { id: "3", title: "Netherlands · Amsterdam", flag: "nl", detail: "31 ms" },
    Server { id: "4", title: "Japan · Tokyo", flag: "jp", detail: "190 ms" },
];

struct Model {
    s: &'static strings::Strings,
    state: State, selected: String, since: Instant, connected_at: Option<Instant>,
    lit: f32, speed: f32, loader: f32, angle: f32, ripple: f32, pop: f32, last: Instant,
}

fn approach(v: f32, target: f32, dt: f32, seconds: f32) -> f32 { v + (target - v) * (dt / seconds).min(1.0) }

/// A round flag from the bundled circle-flags set: next to the exe in an install, in the source tree when developing.
fn flag(code: &str) -> Option<Image> {
    let name = format!("flags/{}.svg", code.to_lowercase());
    let beside_exe = std::env::current_exe().ok().and_then(|p| p.parent().map(|d| d.join(&name)));
    let in_source = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("ui/assets").join(&name);
    [beside_exe, Some(in_source)].into_iter().flatten().find(|p| p.exists()).and_then(|p| Image::load_from_path(&p).ok())
}

fn find<'a>(id: &str) -> Option<&'a Server> { SERVERS.iter().find(|s| s.id == id) }

fn duration(seconds: u64) -> String { format!("{:02}:{:02}:{:02}", seconds / 3600, seconds % 3600 / 60, seconds % 60) }

fn refresh(app: &App, m: &Model) {
    let s = m.s;
    let auto = m.selected == "auto";
    let node = if auto { SERVERS.iter().min_by_key(|s| s.detail.trim_end_matches(" ms").parse::<u32>().unwrap_or(9999)) } else { find(&m.selected) };
    let rows: Vec<ServerRow> = SERVERS.iter().map(|s| ServerRow {
        id: s.id.into(), title: s.title.into(), flag: flag(s.flag).unwrap_or_default(), has_flag: true,
        detail: s.detail.into(), selected: m.selected == s.id,
    }).collect();
    app.set_servers(ModelRc::new(VecModel::from(rows)));
    app.set_auto_selected(auto);
    let title = match (auto, node) {
        (true, Some(n)) if m.state != State::On => format!("{} · {}", s.fastest_server, n.title),
        (_, Some(n)) => n.title.to_string(),
        _ => s.choose_server.to_string(),
    };
    app.set_server_title(title.into());
    app.set_server_has_flag(!auto && node.is_some() || (auto && node.is_some()));
    if let Some(n) = node { if let Some(f) = flag(n.flag) { app.set_server_flag(f); } app.set_server_detail(n.detail.into()); }
    app.set_state(match m.state { State::Off => 0, State::Connecting => 1, State::On => 2 });
    app.set_status(match m.state { State::Off => s.disconnected, State::Connecting => s.connecting, State::On => s.connected }.into());
    app.set_can_pick(m.state == State::Off);
    app.set_secondary(match m.state {
        State::Off => SharedString::new(),
        State::Connecting => s.connecting.into(),
        State::On => duration(m.connected_at.map(|t| t.elapsed().as_secs()).unwrap_or(0)).into(),
    });
    app.set_caption(if m.state == State::On { strings::fill(s.proxy_mode_caption, &[("socks", "127.0.0.1:10808"), ("http", "127.0.0.1:10809")]).into() } else { SharedString::new() });
}

fn main() {
    let app = App::new().unwrap();
    let args: Vec<String> = std::env::args().collect();
    let has = |a: &str| args.iter().any(|x| x == a);
    app.global::<Palette>().set_dark(!has("--light"));
    let language = if has("--fa") { "fa" } else { "en" };
    let s = strings::for_language(language);
    app.global::<Palette>().set_rtl(language == "fa");
    {
        let tr = app.global::<Tr>();
        tr.set_home(s.home.into()); tr.set_servers(s.servers.into()); tr.set_settings(s.settings.into());
        tr.set_logs(s.logs.into()); tr.set_whats_new(s.whats_new.into()); tr.set_about(s.about.into());
        tr.set_test_speed(s.test_speed.into()); tr.set_fastest_server(s.fastest_server.into()); tr.set_manage_servers(s.manage_servers.into());
    }
    let now = Instant::now();
    let state = if has("--on") { State::On } else if has("--connecting") { State::Connecting } else { State::Off };
    let model = Rc::new(RefCell::new(Model {
        s, state, selected: "1".into(), since: now, connected_at: if state == State::On { Some(now) } else { None },
        lit: match state { State::Off => 0.0, State::Connecting => 0.55, State::On => 1.0 },
        speed: 0.12, loader: if state == State::Connecting { 1.0 } else { 0.0 }, angle: 0.0, ripple: 1.0, pop: 1.0, last: now,
    }));
    refresh(&app, &model.borrow());
    if has("--servers") { app.set_page(1); }
    {
        let model = model.clone();
        let weak = app.as_weak();
        app.on_orb_clicked(move || {
            let mut m = model.borrow_mut();
            m.state = if m.state == State::Off { State::Connecting } else { State::Off };
            m.since = Instant::now();
            m.connected_at = None;
            refresh(&weak.unwrap(), &m);
        });
    }
    {
        let model = model.clone();
        let weak = app.as_weak();
        app.on_choose(move |id| {
            let mut m = model.borrow_mut();
            m.selected = id.to_string();
            refresh(&weak.unwrap(), &m);
        });
    }
    let timer = slint::Timer::default();
    let weak = app.as_weak();
    let mut tick = 0u32;
    let mut sprite_px = 0u32;
    let menu_at: u32 = if has("--menu") { 60 } else { 0 }; // debug: open the server menu after about two seconds
    timer.start(slint::TimerMode::Repeated, Duration::from_millis(33), move || {
        let mut m = model.borrow_mut();
        let now = Instant::now();
        let dt = now.duration_since(m.last).as_secs_f32().min(0.1);
        m.last = now;
        let app = weak.unwrap();
        // the demo "connects" after two seconds
        if m.state == State::Connecting && now.duration_since(m.since) > Duration::from_secs(2) {
            m.state = State::On; m.connected_at = Some(now); m.ripple = 0.0; m.pop = 0.94;
            refresh(&app, &m);
        }
        tick += 1;
        if menu_at != 0 && tick == menu_at { app.set_menu_request(1); }
        // the sphere is drawn at the exact pixel size it is shown at (88 logical radius), again if the window moves to a screen with another scale
        let px = (176.0 * app.window().scale_factor()).round() as u32;
        if px != sprite_px {
            sprite_px = px;
            let (off, on) = sphere::sprites(px);
            let motion = app.global::<Motion>();
            motion.set_sphere_off(off); motion.set_sphere_on(on);
        }
        if m.state == State::On && tick % 30 == 0 { refresh(&app, &m); } // the elapsed-time line
        let (lit_t, speed_t) = match m.state { State::Off => (0.0, 0.12), State::Connecting => (0.55, 3.2), State::On => (1.0, 0.45) };
        m.lit = approach(m.lit, lit_t, dt, if m.state == State::Off { 0.5 } else { 0.4 });
        m.speed = approach(m.speed, speed_t, dt, 0.35);
        m.loader = approach(m.loader, if m.state == State::Connecting { 1.0 } else { 0.0 }, dt, 0.15);
        m.angle = (m.angle + dt * m.speed) % LOOP;
        if m.ripple < 1.0 { m.ripple = (m.ripple + dt / 1.2).min(1.0); }
        m.pop = approach(m.pop, 1.0, dt, 0.25);
        let motion = app.global::<Motion>();
        motion.set_angle(m.angle); motion.set_lit(m.lit); motion.set_loader(m.loader); motion.set_ripple(m.ripple); motion.set_pop(m.pop);
    });
    app.run().unwrap();
}
