slint::include_modules!();
use std::{cell::Cell, rc::Rc, time::Duration};

fn main() {
    let app = App::new().unwrap();
    let lit = Rc::new(Cell::new(0f32));
    let angle = Rc::new(Cell::new(0f32));
    let connected = Rc::new(Cell::new(false));
    {
        let weak = app.as_weak();
        let connected = connected.clone();
        app.on_toggle(move || {
            connected.set(!connected.get());
            weak.unwrap().set_label(if connected.get() { "Connected".into() } else { "Connect".into() });
        });
    }
    let timer = slint::Timer::default();
    let weak = app.as_weak();
    timer.start(slint::TimerMode::Repeated, Duration::from_millis(33), move || {
        let target = if connected.get() { 1.0 } else { 0.0 };
        lit.set(lit.get() + (target - lit.get()) * 0.1);
        let speed = if connected.get() { 0.8 } else { 0.2 };
        angle.set((angle.get() + speed) % 360.0);
        let a = weak.unwrap();
        a.set_angle(angle.get());
        a.set_lit(lit.get());
    });
    app.run().unwrap();
}
