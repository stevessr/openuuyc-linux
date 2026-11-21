//! WebRTC STUN / coturn REST shared-secret ICE credential generation.
//!
//! Direct port of selfhost/ice.py: TURN URLs receive a freshly signed REST
//! username (expiry:nonce) and HMAC-SHA1 credential; STUN stays bare.

use base64::Engine as _;
use hmac::{Hmac, Mac};
use rand::Rng;
use sha1::Sha1;
use serde_json::{Value, json};

fn hmac_sha1(secret: &str, message: &str) -> String {
    let mut mac = Hmac::<Sha1>::new_from_slice(secret.as_bytes())
        .expect("HMAC accepts any key length");
    mac.update(message.as_bytes());
    base64::engine::general_purpose::STANDARD.encode(mac.finalize().into_bytes())
}

/// Build one ICE server object per configured URL. TURN REST credentials are
/// minted per control handshake with a one-hour lifetime.
pub fn ice_servers(urls: &[String], shared_secret: &str, static_user: &str, static_password: &str, ttl: u64) -> Vec<Value> {
    urls.iter()
        .map(|url| {
            let mut server = json!({ "urls": url });
            if url.starts_with("turn:") || url.starts_with("turns:") {
                if !shared_secret.is_empty() {
                    let username = format!(
                        "{}:{}",
                        std::time::SystemTime::now()
                            .duration_since(std::time::UNIX_EPOCH)
                            .map(|d| d.as_secs() + ttl)
                            .unwrap_or(ttl),
                        {
                            let mut rng = rand::rng();
                            (0..6).map(|_| format!("{:02x}", rng.random::<u8>())).collect::<String>()
                        }
                    );
                    server["username"] = json!(username);
                    server["credential"] = json!(hmac_sha1(shared_secret, &username));
                } else if !static_user.is_empty() && !static_password.is_empty() {
                    server["username"] = json!(static_user);
                    server["credential"] = json!(static_password);
                }
            }
            server
        })
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn rest_credentials_match_coturn_hmac() {
        let servers = ice_servers(
            &["stun:stun.test:3478".into(), "turn:turn.test:3478".into()],
            "private-secret", "", "", 3600,
        );
        assert!(servers[0].get("username").is_none());
        let turn = &servers[1];
        let username = turn["username"].as_str().unwrap();
        let expiry: u64 = username.split(':').next().unwrap().parse().unwrap();
        let now = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap()
            .as_secs();
        assert!(expiry > now && expiry <= now + 3601);
        assert_eq!(
            turn["credential"].as_str().unwrap(),
            hmac_sha1("private-secret", username),
        );
    }

    #[test]
    fn static_credentials_are_passed_through() {
        let servers = ice_servers(&["turn:turn.test:3478".into()], "", "user", "pass", 3600);
        assert_eq!(servers[0]["username"], "user");
        assert_eq!(servers[0]["credential"], "pass");
    }
}
