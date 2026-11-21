//! The orb's glass sphere, drawn at the exact pixel size it is shown at (the software renderer scales images with hard
//! steps, so a sprite shipped at one size would have jagged edges). Same colours and overlays as `drawOrb` in
//! desktop/.../ui/GlassOrb.kt; the UI cross-fades the off and on sprites by `lit`.

use slint::{Image, Rgba8Pixel, SharedPixelBuffer};

// Off: a calm slate-lavender pearl, light enough that the white power symbol reads and that the half-faded
// "connecting" blend with ON stays colourful instead of muddy.
const OFF: [(f32, [f32; 3]); 6] = [
    (0.0, [0xB4 as f32, 0xBC as f32, 0xD6 as f32]), (0.22, [0x92 as f32, 0x9B as f32, 0xBC as f32]),
    (0.42, [0x76 as f32, 0x7F as f32, 0xA4 as f32]), (0.60, [0x6A as f32, 0x72 as f32, 0x98 as f32]),
    (0.78, [0x8C as f32, 0x82 as f32, 0xA6 as f32]), (1.0, [0xD0 as f32, 0xA2 as f32, 0x94 as f32]),
];
const OFF_KITKAT: [(f32, [f32; 3]); 6] = [
    (0.0, [0xFF as f32, 0xE4 as f32, 0xBC as f32]), (0.22, [0xD9 as f32, 0x98 as f32, 0x64 as f32]),
    (0.42, [0x9B as f32, 0x50 as f32, 0x34 as f32]), (0.60, [0x5A as f32, 0x26 as f32, 0x1E as f32]),
    (0.78, [0x84 as f32, 0x35 as f32, 0x1F as f32]), (1.0, [0xE4 as f32, 0xAC as f32, 0x64 as f32]),
];
const ON: [(f32, [f32; 3]); 6] = [
    (0.0, [0x4E as f32, 0x1C as f32, 0xD6 as f32]), (0.22, [0x82 as f32, 0x22 as f32, 0xD6 as f32]),
    (0.42, [0xCE as f32, 0x24 as f32, 0x96 as f32]), (0.60, [0xF0 as f32, 0x34 as f32, 0x46 as f32]),
    (0.78, [0xFF as f32, 0x80 as f32, 0x1C as f32]), (1.0, [0xFF as f32, 0xCD as f32, 0x1E as f32]),
];

// On, mint: deep teal at the top, through green, to a pale lime glow at the bottom.
const ON_MINT: [(f32, [f32; 3]); 6] = [
    (0.0, [0x0B as f32, 0x4F as f32, 0x6C as f32]), (0.22, [0x0F as f32, 0x7A as f32, 0x7A as f32]),
    (0.42, [0x1F as f32, 0xB0 as f32, 0x8A as f32]), (0.60, [0x4F as f32, 0xD6 as f32, 0xA0 as f32]),
    (0.78, [0x9A as f32, 0xF0 as f32, 0xB8 as f32]), (1.0, [0xE0 as f32, 0xFF as f32, 0xC8 as f32]),
];
const ON_KITKAT: [(f32, [f32; 3]); 6] = [
    (0.0, [0x54 as f32, 0x1B as f32, 0x21 as f32]), (0.22, [0x9A as f32, 0x00 as f32, 0x16 as f32]),
    (0.42, [0xD2 as f32, 0x1E as f32, 0x25 as f32]), (0.60, [0xF0 as f32, 0x44 as f32, 0x2B as f32]),
    (0.78, [0xFF as f32, 0x8A as f32, 0x2D as f32]), (1.0, [0xFF as f32, 0xD5 as f32, 0x68 as f32]),
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

/// Interleaved gradient noise in -0.5..0.5 (fixed pattern, so the sprite looks the same every time).
fn dither(x: u32, y: u32) -> f32 {
    let v = 52.982_918 * (0.067_110_56 * x as f32 + 0.005_837_15 * y as f32).fract();
    v.fract() - 0.5
}

/// `size` is the sphere's diameter; the buffer is `pad` pixels larger on every side so the renderer's rounding never clips it.
fn pixels(size: u32, pad: u32, lit: f32, mint: bool, kitkat: bool) -> Vec<u8> {
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
            let stops = if lit <= 0.5 { if kitkat { &OFF_KITKAT } else { &OFF } } else if mint { &ON_MINT } else if kitkat { &ON_KITKAT } else { &ON };
            let mut c = ramp(stops, (y + 1.0) / 2.0);
            // soft highlight, upper left
            let hd = ((x + 0.35).powi(2) + (y + 0.45).powi(2)).sqrt() / 0.62;
            c = mix(c, [255.0; 3], (0.10 + 0.30 * lit) * (1.0 - hd).clamp(0.0, 1.0));
            // darkening towards the rim
            let u = (x * x + (y - 0.10).powi(2)).sqrt() / 1.05;
            if u > 0.78 { c = mix(c, [0.0; 3], 0.30 * ((u - 0.78) / 0.22).clamp(0.0, 1.0)); }
            // thin bright rim on the upper half
            let rim = 1.5 / r * 2.0;
            // (its inner edge is a one-pixel ramp, not a hard step, or the rim shows stair steps)
            let inner = ((d - (1.0 - rim)) * r).clamp(0.0, 1.0);
            c = mix(c, [255.0; 3], inner * (0.22 + 0.28 * lit) * (-y).clamp(0.0, 1.0));
            // a touch of noise before rounding to 8 bits, so the smooth gradient has no visible bands
            let n = dither(px, py);
            let i = ((py * total + px) * 4) as usize;
            out[i] = (c[0] + n).round().clamp(0.0, 255.0) as u8; out[i + 1] = (c[1] + n).round().clamp(0.0, 255.0) as u8; out[i + 2] = (c[2] + n).round().clamp(0.0, 255.0) as u8;
            out[i + 3] = (edge * 255.0).round() as u8;
        }
    }
    out
}


/// Transparent border around the sphere in the sprite, in pixels.
pub const PAD: u32 = 4;

/// One sprite (`lit` 0 is the off sphere, 1 the on sphere): a sphere `diameter` pixels across inside a buffer `PAD`
/// pixels larger on every side. It is drawn 1:1, so it is made again for every size the sphere takes while it pops.
pub fn sprite(diameter: u32, lit: f32, mint: bool, kitkat: bool) -> Image {
    let total = diameter + 2 * PAD;
    let mut buffer = SharedPixelBuffer::<Rgba8Pixel>::new(total, total);
    buffer.make_mut_bytes().copy_from_slice(&pixels(diameter, PAD, lit, mint, kitkat));
    Image::from_rgba8(buffer)
}
