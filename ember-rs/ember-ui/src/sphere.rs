//! The orb's glass sphere, drawn at the exact pixel size it is shown at (the software renderer scales images with hard
//! steps, so a sprite shipped at one size would have jagged edges). Same colours and overlays as `drawOrb` in
//! desktop/.../ui/GlassOrb.kt; the UI cross-fades the off and on sprites by `lit`.

use slint::{Image, Rgba8Pixel, SharedPixelBuffer};

const OFF: [(f32, [f32; 3]); 6] = [
    (0.0, [0x7A as f32, 0x82 as f32, 0x92 as f32]), (0.22, [0x4A as f32, 0x50 as f32, 0x5C as f32]),
    (0.42, [0x2B as f32, 0x30 as f32, 0x3B as f32]), (0.60, [0x22 as f32, 0x26 as f32, 0x30 as f32]),
    (0.78, [0x3A as f32, 0x2E as f32, 0x2C as f32]), (1.0, [0x8A as f32, 0x4A as f32, 0x2A as f32]),
];
const ON: [(f32, [f32; 3]); 6] = [
    (0.0, [0x4E as f32, 0x1C as f32, 0xD6 as f32]), (0.22, [0x82 as f32, 0x22 as f32, 0xD6 as f32]),
    (0.42, [0xCE as f32, 0x24 as f32, 0x96 as f32]), (0.60, [0xF0 as f32, 0x34 as f32, 0x46 as f32]),
    (0.78, [0xFF as f32, 0x80 as f32, 0x1C as f32]), (1.0, [0xFF as f32, 0xCD as f32, 0x1E as f32]),
];

fn ramp(stops: &[(f32, [f32; 3]); 6], t: f32) -> [f32; 3] {
    let t = t.clamp(0.0, 1.0);
    for w in stops.windows(2) {
        let (a, b) = (w[0], w[1]);
        if t <= b.0 {
            let k = (t - a.0) / (b.0 - a.0);
            return [0, 1, 2].map(|i| a.1[i] + (b.1[i] - a.1[i]) * k);
        }
    }
    stops[5].1
}

fn mix(base: [f32; 3], over: [f32; 3], a: f32) -> [f32; 3] { [0, 1, 2].map(|i| base[i] + (over[i] - base[i]) * a) }

/// `size` is the sphere's diameter; the buffer is `pad` pixels larger on every side so the renderer's rounding never clips it.
fn pixels(size: u32, pad: u32, lit: f32) -> Vec<u8> {
    let r = size as f32 / 2.0;
    let total = size + 2 * pad;
    let centre = total as f32 / 2.0;
    let mut out = vec![0u8; (total * total * 4) as usize];
    for py in 0..total {
        for px in 0..total {
            let x = (px as f32 + 0.5 - centre) / r; // -1..1 across the sphere
            let y = (py as f32 + 0.5 - centre) / r;
            let d = (x * x + y * y).sqrt();
            let edge = ((1.0 - d) * r + 0.5).clamp(0.0, 1.0);
            if edge <= 0.0 { continue; }
            let stops = if lit > 0.5 { &ON } else { &OFF };
            let mut c = ramp(stops, (y + 1.0) / 2.0);
            // soft highlight, upper left
            let hd = ((x + 0.35).powi(2) + (y + 0.45).powi(2)).sqrt() / 0.62;
            c = mix(c, [255.0; 3], (0.10 + 0.30 * lit) * (1.0 - hd).clamp(0.0, 1.0));
            // darkening towards the rim
            let u = (x * x + (y - 0.10).powi(2)).sqrt() / 1.05;
            if u > 0.78 { c = mix(c, [0.0; 3], 0.30 * ((u - 0.78) / 0.22).clamp(0.0, 1.0)); }
            // thin bright rim on the upper half
            let rim = 1.5 / r * 2.0;
            if d > 1.0 - rim && y < 0.0 {
                c = mix(c, [255.0; 3], (0.22 + 0.28 * lit) * (-y).clamp(0.0, 1.0));
            }
            let i = ((py * total + px) * 4) as usize;
            out[i] = c[0].round() as u8; out[i + 1] = c[1].round() as u8; out[i + 2] = c[2].round() as u8;
            out[i + 3] = (edge * 255.0).round() as u8;
        }
    }
    out
}


/// Transparent border around the sphere in the sprite, in pixels.
pub const PAD: u32 = 4;

/// The off and on sprites: a sphere `diameter` pixels across inside a buffer `PAD` pixels larger on every side.
pub fn sprites(diameter: u32) -> (Image, Image) {
    let total = diameter + 2 * PAD;
    let make = |lit: f32| {
        let mut buffer = SharedPixelBuffer::<Rgba8Pixel>::new(total, total);
        buffer.make_mut_bytes().copy_from_slice(&pixels(diameter, PAD, lit));
        Image::from_rgba8(buffer)
    };
    (make(0.0), make(1.0))
}
