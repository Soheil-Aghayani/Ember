//! A link drawn as a QR code. Port of the drawing half of `QrCodes.kt`; reading a code out of a picture is not ported.

use qrcode::{Color, EcLevel, QrCode};

/// The modules of a QR code, row by row (`true` is dark). Draw it with a quiet border of [`QUIET_ZONE`] modules.
pub struct QrMatrix {
    pub size: usize,
    pub dark: Vec<bool>,
}

impl QrMatrix {
    pub fn is_dark(&self, x: usize, y: usize) -> bool {
        self.dark[y * self.size + x]
    }
}

/// Modules of white border the Kotlin app drew around the code.
pub const QUIET_ZONE: usize = 2;

/// `text` as a QR code with medium error correction (the level the Kotlin app used).
pub fn qr_matrix(text: &str) -> Result<QrMatrix, qrcode::types::QrError> {
    let code = QrCode::with_error_correction_level(text.as_bytes(), EcLevel::M)?;
    Ok(QrMatrix { size: code.width(), dark: code.to_colors().into_iter().map(|c| c == Color::Dark).collect() })
}
