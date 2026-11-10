//! The soft chimes for connecting and disconnecting. They are made here (no sound files to ship): a few sine notes with a
//! quick attack and a long fade, as a 16-bit mono WAV that Windows plays from memory without blocking.

use std::sync::OnceLock;

const RATE: u32 = 22_050;

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum Chime {
    /// Two notes going up, like a spark catching.
    Connected,
    /// Two notes going down, quieter and shorter.
    Disconnected,
}

/// One note: a sine with a little second harmonic, a 6 ms attack and an exponential fade.
fn note(out: &mut [f32], start: f32, freq: f32, length: f32, gain: f32) {
    let first = (start * RATE as f32) as usize;
    let count = (length * RATE as f32) as usize;
    for i in 0..count {
        let Some(slot) = out.get_mut(first + i) else { break };
        let t = i as f32 / RATE as f32;
        let attack = (t / 0.006).min(1.0);
        let fade = (-t * 7.0 / length).exp() * (1.0 - t / length).max(0.0);
        let phase = std::f32::consts::TAU * freq * t;
        *slot += (phase.sin() + 0.25 * (phase * 2.0).sin()) * attack * fade * gain;
    }
}

/// The WAV file bytes of a chime.
pub fn wav(chime: Chime) -> Vec<u8> {
    let (total, notes): (f32, &[(f32, f32, f32, f32)]) = match chime {
        // C5 then G5
        Chime::Connected => (0.75, &[(0.0, 523.25, 0.45, 0.22), (0.11, 783.99, 0.6, 0.2)]),
        // G5 then C5, softer
        Chime::Disconnected => (0.5, &[(0.0, 783.99, 0.3, 0.14), (0.09, 523.25, 0.4, 0.14)]),
    };
    let mut samples = vec![0.0f32; (total * RATE as f32) as usize];
    for &(start, freq, length, gain) in notes {
        note(&mut samples, start, freq, length, gain);
    }
    let data_len = (samples.len() * 2) as u32;
    let mut wav = Vec::with_capacity(44 + data_len as usize);
    wav.extend_from_slice(b"RIFF");
    wav.extend_from_slice(&(36 + data_len).to_le_bytes());
    wav.extend_from_slice(b"WAVEfmt ");
    wav.extend_from_slice(&16u32.to_le_bytes());
    wav.extend_from_slice(&1u16.to_le_bytes()); // PCM
    wav.extend_from_slice(&1u16.to_le_bytes()); // mono
    wav.extend_from_slice(&RATE.to_le_bytes());
    wav.extend_from_slice(&(RATE * 2).to_le_bytes());
    wav.extend_from_slice(&2u16.to_le_bytes());
    wav.extend_from_slice(&16u16.to_le_bytes());
    wav.extend_from_slice(b"data");
    wav.extend_from_slice(&data_len.to_le_bytes());
    for s in samples {
        wav.extend_from_slice(&((s.clamp(-1.0, 1.0) * i16::MAX as f32) as i16).to_le_bytes());
    }
    wav
}

/// Plays a chime and returns at once; false when it could not be started (no sound device, or not Windows).
pub fn play(chime: Chime) -> bool {
    static CONNECTED: OnceLock<Vec<u8>> = OnceLock::new();
    static DISCONNECTED: OnceLock<Vec<u8>> = OnceLock::new();
    // the buffer must outlive the playback, so each lives in a static
    let bytes = match chime {
        Chime::Connected => CONNECTED.get_or_init(|| wav(chime)),
        Chime::Disconnected => DISCONNECTED.get_or_init(|| wav(chime)),
    };
    #[cfg(windows)]
    unsafe {
        use windows::core::PCWSTR;
        use windows::Win32::Media::Audio::{PlaySoundW, SND_ASYNC, SND_MEMORY, SND_NODEFAULT};
        PlaySoundW(PCWSTR(bytes.as_ptr() as *const u16), None, SND_MEMORY | SND_ASYNC | SND_NODEFAULT).as_bool()
    }
    #[cfg(not(windows))]
    {
        let _ = bytes;
        false
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Needs a sound device, so it is run by hand (`cargo test -p ember-win -- --ignored sound`).
    #[test]
    #[ignore]
    fn windows_accepts_both_chimes() {
        assert!(play(Chime::Connected));
        std::thread::sleep(std::time::Duration::from_millis(900));
        assert!(play(Chime::Disconnected));
        std::thread::sleep(std::time::Duration::from_millis(600));
    }

    #[test]
    fn wav_has_a_valid_header_and_stays_quiet() {
        for chime in [Chime::Connected, Chime::Disconnected] {
            let w = wav(chime);
            assert_eq!(&w[0..4], b"RIFF");
            assert_eq!(&w[8..12], b"WAVE");
            let data = u32::from_le_bytes(w[40..44].try_into().unwrap()) as usize;
            assert_eq!(w.len(), 44 + data);
            let peak = w[44..].chunks(2).map(|b| i16::from_le_bytes([b[0], b[1]]).unsigned_abs()).max().unwrap();
            assert!(peak > 3_000, "audible");
            assert!(peak < 14_000, "soft, below 45% of full scale");
        }
    }
}
