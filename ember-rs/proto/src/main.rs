slint::include_modules!();
use std::{cell::RefCell, rc::Rc, time::{Duration, Instant}};

#[derive(Clone, Copy, PartialEq)]
enum State { Off, Connecting, On }

/// Same wrap as `LOOP` in GlassOrb.kt: ten full turns, so every dot returns to where it started.
const LOOP: f32 = 20.0 * std::f32::consts::PI;

struct Orb { state: State, lit: f32, speed: f32, loader: f32, angle: f32, ripple: f32, pop: f32, last: Instant, since: Instant }

fn approach(v: f32, target: f32, dt: f32, seconds: f32) -> f32 {
    let k = (dt / seconds).min(1.0);
    v + (target - v) * k
}

fn main() {
    let app = App::new().unwrap();
    let start = std::env::args().nth(1).unwrap_or_default();
    let state = match start.as_str() { "--on" => State::On, "--connecting" => State::Connecting, _ => State::Off };
    let orb = Rc::new(RefCell::new(Orb {
        state, lit: match state { State::Off => 0.0, State::Connecting => 0.55, State::On => 1.0 },
        speed: 0.12, loader: if state == State::Connecting { 1.0 } else { 0.0 }, angle: 0.0, ripple: 1.0, pop: 1.0,
        last: Instant::now(), since: Instant::now(),
    }));
    app.set_label(match state { State::Off => "Connect", State::Connecting => "Connecting…", State::On => "Connected" }.into());
    {
        let orb = orb.clone();
        let weak = app.as_weak();
        app.on_toggle(move || {
            let mut o = orb.borrow_mut();
            let next = match o.state { State::Off => State::Connecting, State::Connecting => State::Off, State::On => State::Off };
            o.state = next;
            o.since = Instant::now();
            let text = match next { State::Off => "Connect", State::Connecting => "Connecting…", State::On => "Connected" };
            weak.unwrap().set_label(text.into());
        });
    }
    let timer = slint::Timer::default();
    let weak = app.as_weak();
    timer.start(slint::TimerMode::Repeated, Duration::from_millis(33), move || {
        let mut o = orb.borrow_mut();
        let now = Instant::now();
        let dt = now.duration_since(o.last).as_secs_f32().min(0.1);
        o.last = now;
        // the demo "connects" after two seconds
        if o.state == State::Connecting && now.duration_since(o.since) > Duration::from_secs(2) {
            o.state = State::On;
            o.ripple = 0.0;
            o.pop = 0.94;
            weak.unwrap().set_label("Connected".into());
        }
        let (lit_t, speed_t) = match o.state { State::Off => (0.0, 0.12), State::Connecting => (0.55, 3.2), State::On => (1.0, 0.45) };
        o.lit = approach(o.lit, lit_t, dt, if o.state == State::Off { 0.5 } else { 0.4 });
        o.speed = approach(o.speed, speed_t, dt, 0.35);
        o.loader = approach(o.loader, if o.state == State::Connecting { 1.0 } else { 0.0 }, dt, 0.15);
        o.angle = (o.angle + dt * o.speed) % LOOP;
        if o.ripple < 1.0 { o.ripple = (o.ripple + dt / 1.2).min(1.0); }
        o.pop = approach(o.pop, 1.0, dt, 0.25);
        let a = weak.unwrap();
        a.set_angle(o.angle); a.set_lit(o.lit); a.set_loader(o.loader); a.set_ripple(o.ripple); a.set_pop(o.pop);
    });
    app.run().unwrap();
}
