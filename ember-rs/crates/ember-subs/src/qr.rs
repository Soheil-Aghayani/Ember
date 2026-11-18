//! QR codes for sharing a server and importing one from a picture.

use std::path::Path;

use qrcode::{Color, EcLevel, QrCode};
use thiserror::Error;

const MAX_IMAGE_EDGE: u32 = 4096;
const MAX_IMAGE_ALLOCATION: u64 = 64 * 1024 * 1024;

#[derive(Debug, Error, PartialEq, Eq)]
pub enum QrImageError {
    #[error("The image could not be decoded.")]
    InvalidImage,
    #[error("No readable QR code was found.")]
    NoCode,
    #[error("The image contains more than one QR code.")]
    MultipleCodes,
}

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
    Ok(QrMatrix {
        size: code.width(),
        dark: code
            .to_colors()
            .into_iter()
            .map(|c| c == Color::Dark)
            .collect(),
    })
}

/// Reads one QR payload from a PNG, JPEG, WebP, or BMP image. Multiple codes are rejected so a single picture cannot
/// silently add several unrelated servers. Decoding stays local; callers decide how to handle the returned text.
pub fn decode_image_file(path: &Path) -> Result<String, QrImageError> {
    let mut reader = image::ImageReader::open(path).map_err(|_| QrImageError::InvalidImage)?;
    let mut limits = image::Limits::default();
    limits.max_image_width = Some(MAX_IMAGE_EDGE);
    limits.max_image_height = Some(MAX_IMAGE_EDGE);
    limits.max_alloc = Some(MAX_IMAGE_ALLOCATION);
    reader.limits(limits);

    let image = reader.decode().map_err(|_| QrImageError::InvalidImage)?;
    let mut prepared = rqrr::PreparedImage::prepare(image.to_luma8());
    let mut payloads = Vec::new();
    for grid in prepared.detect_grids() {
        let Ok((_, payload)) = grid.decode() else {
            continue;
        };
        let payload = payload.trim();
        if !payload.is_empty() && !payloads.iter().any(|found| found == payload) {
            payloads.push(payload.to_string());
        }
    }

    match payloads.len() {
        0 => Err(QrImageError::NoCode),
        1 => Ok(payloads.pop().expect("one payload was counted")),
        _ => Err(QrImageError::MultipleCodes),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use image::{GrayImage, Luma};
    use std::time::{SystemTime, UNIX_EPOCH};

    const SCALE: u32 = 5;
    const BORDER: u32 = 4;

    fn image_with_qr(text: &str) -> GrayImage {
        let code = QrCode::with_error_correction_level(text.as_bytes(), EcLevel::M).unwrap();
        let size = (code.width() as u32 + BORDER * 2) * SCALE;
        GrayImage::from_fn(size, size, |x, y| {
            let module_x = x / SCALE;
            let module_y = y / SCALE;
            let dark = module_x >= BORDER
                && module_y >= BORDER
                && module_x < BORDER + code.width() as u32
                && module_y < BORDER + code.width() as u32
                && code[((module_x - BORDER) as usize, (module_y - BORDER) as usize)]
                    == Color::Dark;
            Luma([if dark { 0 } else { 255 }])
        })
    }

    fn temp_image_path(label: &str, extension: &str) -> std::path::PathBuf {
        let nonce = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap()
            .as_nanos();
        std::env::temp_dir().join(format!(
            "ember-qr-{label}-{}-{nonce}.{extension}",
            std::process::id()
        ))
    }

    #[test]
    fn decodes_a_server_link_from_a_png() {
        let text =
            "vless://11111111-2222-3333-4444-555555555555@node.example.com:443?type=tcp#Test";
        let path = temp_image_path("valid", "png");
        image_with_qr(text).save(&path).unwrap();

        let result = decode_image_file(&path);
        let _ = std::fs::remove_file(path);
        assert_eq!(result.unwrap(), text);
    }

    #[test]
    fn decodes_qr_images_in_every_supported_format() {
        let text =
            "vless://11111111-2222-3333-4444-555555555555@node.example.com:443?type=tcp#Test";
        for (label, extension, format) in [
            ("png", "png", image::ImageFormat::Png),
            ("jpeg", "jpg", image::ImageFormat::Jpeg),
            ("webp", "webp", image::ImageFormat::WebP),
            ("bmp", "bmp", image::ImageFormat::Bmp),
        ] {
            let path = temp_image_path(label, extension);
            image_with_qr(text).save_with_format(&path, format).unwrap();

            let result = decode_image_file(&path);
            let _ = std::fs::remove_file(path);
            assert_eq!(result.unwrap(), text, "{label}");
        }
    }

    #[test]
    fn rejects_an_image_without_a_qr_code() {
        let path = temp_image_path("blank", "png");
        GrayImage::from_pixel(128, 128, Luma([255]))
            .save(&path)
            .unwrap();

        let result = decode_image_file(&path);
        let _ = std::fs::remove_file(path);
        assert_eq!(result, Err(QrImageError::NoCode));
    }

    #[test]
    fn rejects_unreadable_image_files() {
        let path = temp_image_path("invalid", "png");
        std::fs::write(&path, b"not an image").unwrap();

        let result = decode_image_file(&path);
        let _ = std::fs::remove_file(path);
        assert_eq!(result, Err(QrImageError::InvalidImage));
    }

    #[test]
    fn rejects_images_above_the_dimension_limit() {
        let path = temp_image_path("large", "png");
        GrayImage::from_pixel(MAX_IMAGE_EDGE + 1, 128, Luma([255]))
            .save(&path)
            .unwrap();

        let result = decode_image_file(&path);
        let _ = std::fs::remove_file(path);
        assert_eq!(result, Err(QrImageError::InvalidImage));
    }

    #[test]
    fn rejects_images_with_multiple_codes() {
        let first =
            image_with_qr("vless://11111111-2222-3333-4444-555555555555@one.example.com:443#One");
        let second =
            image_with_qr("vless://11111111-2222-3333-4444-555555555555@two.example.com:443#Two");
        let gap = SCALE * 8;
        let mut combined = GrayImage::from_pixel(
            first.width() + second.width() + gap,
            first.height(),
            Luma([255]),
        );
        for (x, y, pixel) in first.enumerate_pixels() {
            combined.put_pixel(x, y, *pixel);
        }
        for (x, y, pixel) in second.enumerate_pixels() {
            combined.put_pixel(x + first.width() + gap, y, *pixel);
        }
        let path = temp_image_path("multiple", "png");
        combined.save(&path).unwrap();

        let result = decode_image_file(&path);
        let _ = std::fs::remove_file(path);
        assert_eq!(result, Err(QrImageError::MultipleCodes));
    }
}
