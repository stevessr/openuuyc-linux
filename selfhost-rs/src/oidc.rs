//! OIDC / Keycloak login provider for the self-hosted control plane.
//!
//! The device-approval page delegates user authentication to Keycloak via
//! OAuth2 authorization code flow with PKCE (S256). Identity is resolved
//! through the userinfo endpoint: Keycloak validates the access token
//! server-side, so this crate never verifies JWT signatures or fetches a
//! JWKS. The client secret never leaves the server: code exchange happens
//! entirely server-side.

use anyhow::{Context as _, Result};
use base64::Engine as _;
use rand::Rng;
use serde::Deserialize;
use sha2::{Digest, Sha256};
use std::sync::Arc;
use std::time::Duration;
use arc_swap::ArcSwap;

#[derive(Debug, Clone, Deserialize)]
pub struct Discovery {
    pub authorization_endpoint: String,
    pub token_endpoint: String,
    pub userinfo_endpoint: String,
}

#[derive(Debug, Clone, Deserialize)]
pub struct TokenResponse {
    pub access_token: String,
    #[serde(default)]
    pub token_type: String,
    #[serde(default)]
    pub error: Option<String>,
    #[serde(default)]
    pub error_description: Option<String>,
}

#[derive(Debug, Clone, Deserialize)]
pub struct UserInfo {
    pub sub: String,
    #[serde(default)]
    pub preferred_username: Option<String>,
    #[serde(default)]
    pub email: Option<String>,
}

impl UserInfo {
    /// Display name for the enrollment page and the account nickname.
    pub fn display(&self) -> String {
        self.preferred_username
            .clone()
            .or_else(|| self.email.clone())
            .unwrap_or_else(|| self.sub.clone())
    }
}

pub struct OidcProvider {
    pub issuer: String,
    pub client_id: String,
    pub client_secret: Option<String>,
    http: reqwest::Client,
    endpoints: ArcSwap<Option<Arc<Discovery>>>,
}

impl OidcProvider {
    pub fn new(issuer: &str, client_id: &str, client_secret: Option<String>) -> Self {
        Self {
            issuer: issuer.trim_end_matches('/').to_owned(),
            client_id: client_id.to_owned(),
            client_secret,
            http: reqwest::Client::builder()
                .timeout(Duration::from_secs(10))
                .build()
                .expect("oidc http client"),
            endpoints: ArcSwap::from_pointee(None),
        }
    }

    /// Fetch (and cache) the discovery document. A cached copy is reused so
    /// a Keycloak restart does not break in-flight approvals.
    pub async fn endpoints(&self) -> Result<Arc<Discovery>> {
        if let Some(endpoints) = self.endpoints.load().as_ref() {
            return Ok(endpoints.clone());
        }
        let url = format!("{}/.well-known/openid-configuration", self.issuer);
        let discovery: Discovery = self
            .http
            .get(&url)
            .timeout(Duration::from_secs(10))
            .send()
            .await
            .context("fetch OIDC discovery document")?
            .error_for_status()
            .context("OIDC discovery document returned an error")?
            .json()
            .await
            .context("parse OIDC discovery document")?;
        let arc = Arc::new(discovery);
        self.endpoints.store(Arc::new(Some(arc.clone())));
        Ok(arc)
    }

    /// PKCE S256 verifier + challenge pair.
    pub fn pkce() -> (String, String) {
        let mut buf = [0u8; 32];
        rand::rng().fill(&mut buf[..]);
        let verifier = base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(buf);
        let mut hasher = Sha256::new();
        hasher.update(verifier.as_bytes());
        let challenge = base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(hasher.finalize());
        (verifier, challenge)
    }

    pub fn nonce() -> String {
        let mut buf = [0u8; 16];
        rand::rng().fill(&mut buf[..]);
        buf.iter().map(|b| format!("{b:02x}")).collect()
    }

    pub fn authorization_url(
        &self,
        endpoints: &Discovery,
        redirect_uri: &str,
        state: &str,
        nonce: &str,
        code_challenge: &str,
    ) -> String {
        let pairs = [
            ("response_type", "code"),
            ("client_id", self.client_id.as_str()),
            ("redirect_uri", redirect_uri),
            ("scope", "openid profile email"),
            ("state", state),
            ("nonce", nonce),
            ("code_challenge", code_challenge),
            ("code_challenge_method", "S256"),
        ];
        let query = pairs
            .iter()
            .map(|(k, v)| format!("{}={}", k, urlencoding::encode(v)))
            .collect::<Vec<_>>()
            .join("&");
        format!("{}?{}", endpoints.authorization_endpoint, query)
    }

    /// Exchange the authorization code for tokens. Confidential clients send
    /// the client secret; public PKCE clients send the verifier.
    pub async fn exchange_code(
        &self,
        endpoints: &Discovery,
        redirect_uri: &str,
        code: &str,
        credential: &str,
        confidential: bool,
    ) -> Result<TokenResponse> {
        let mut form = vec![
            ("grant_type", "authorization_code".to_owned()),
            ("client_id", self.client_id.clone()),
            ("code", code.to_owned()),
            ("redirect_uri", redirect_uri.to_owned()),
        ];
        if confidential {
            form.push(("client_secret", credential.to_owned()));
        } else {
            form.push(("code_verifier", credential.to_owned()));
        }
        let response = self
            .http
            .post(&endpoints.token_endpoint)
            .form(&form)
            .send()
            .await
            .context("token endpoint request")?;
        let tokens: TokenResponse = response.json().await.context("parse token response")?;
        if let Some(error) = tokens.error {
            anyhow::bail!(
                "token endpoint rejected the code: {} ({})",
                error,
                tokens.error_description.unwrap_or_default()
            );
        }
        Ok(tokens)
    }

    /// Resolve the authenticated identity through userinfo.
    pub async fn userinfo(&self, endpoints: &Discovery, access_token: &str) -> Result<UserInfo> {
        let user: UserInfo = self
            .http
            .get(&endpoints.userinfo_endpoint)
            .bearer_auth(access_token)
            .send()
            .await
            .context("userinfo request")?
            .error_for_status()
            .context("userinfo rejected the token")?
            .json()
            .await
            .context("parse userinfo")?;
        if user.sub.is_empty() {
            anyhow::bail!("userinfo returned an empty subject");
        }
        Ok(user)
    }
}
