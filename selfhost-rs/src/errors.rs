//! Typed API failures mirroring the Python reference's web.HTTP* exceptions.

use axum::http::StatusCode;
use axum::response::Response;

#[derive(Debug, Clone)]
pub enum ApiError {
    /// {code, msg, data} JSON body, HTTP 200 — the official NRD contract.
    Envelope(Option<serde_json::Value>, i32, String),
    Plain(StatusCode, &'static str),
}

impl ApiError {
    pub fn bad_request(text: &'static str) -> Self {
        Self::Plain(StatusCode::BAD_REQUEST, text)
    }
    pub fn unauthorized(text: &'static str) -> Self {
        Self::Plain(StatusCode::UNAUTHORIZED, text)
    }
    pub fn forbidden(text: &'static str) -> Self {
        Self::Plain(StatusCode::FORBIDDEN, text)
    }
    pub fn not_found() -> Self {
        Self::Plain(StatusCode::NOT_FOUND, "")
    }
    pub fn too_large(max: usize) -> Self {
        Self::Plain(StatusCode::PAYLOAD_TOO_LARGE, Box::leak(format!("max {max}").into_boxed_str()))
    }
    pub fn too_many(text: &'static str) -> Self {
        Self::Plain(StatusCode::TOO_MANY_REQUESTS, text)
    }
    pub fn unavailable(text: &'static str) -> Self {
        Self::Plain(StatusCode::SERVICE_UNAVAILABLE, text)
    }
    pub fn unsupported(text: &'static str) -> Self {
        Self::Plain(StatusCode::UNSUPPORTED_MEDIA_TYPE, text)
    }
}

impl From<ApiError> for std::io::Error {
    fn from(error: ApiError) -> Self {
        match error {
            ApiError::Plain(status, text) => {
                std::io::Error::new(std::io::ErrorKind::InvalidData, format!("{status}: {text}"))
            }
            ApiError::Envelope(_, code, msg) => {
                std::io::Error::new(std::io::ErrorKind::InvalidData, format!("envelope {code}: {msg}"))
            }
        }
    }
}

pub type ApiResult = Result<axum::response::Response, ApiError>;
