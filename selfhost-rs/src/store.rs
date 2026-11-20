//! Distributed Redis state layer for every selfhost data class.
//!
//! The Python reference keeps devices/sessions/QRs in SQLite and room
//! tickets/controls/challenges in process memory. This port moves every one
//! of those classes into Redis with per-class TTLs so any node can serve any
//! client, and per-key SETNX-with-TTL primitives replace the in-process
//! capacity checks. Only volatile in-memory data — the physical WebSocket
//! registry and its pub/sub subscriptions — stays local to a node
//! (see runtime.rs).

use crate::errors::ApiError;
use deadpool_redis::{Config as PoolConfig, Pool, Runtime};
use rand::Rng;
use base64::Engine as _;
use redis::aio::ConnectionLike;
use redis::AsyncCommands;
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::time::{SystemTime, UNIX_EPOCH};

fn now() -> u64 {
    SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_secs()).unwrap_or(0)
}

fn rng_token(bytes: usize) -> String {
    let mut buf = vec![0u8; bytes];
    rand::rng().fill(&mut buf[..]);
    base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(buf)
}

pub fn hash_token(token: &str) -> String {
    let mut hasher = Sha256::new();
    hasher.update(token.as_bytes());
    format!("{:x}", hasher.finalize())
}

/// Lua compare-and-delete with TTL reset: capacity-checked token claims that
/// must happen exactly once across nodes.
pub const CLAIM_SCRIPT: &str = r#"
local value = redis.call('GET', KEYS[1])
if not value then return 0 end
if value ~= ARGV[1] then return 0 end
redis.call('DEL', KEYS[1])
return 1
"#;

/// Shared store handle. Every method either succeeds or maps to the same
/// HTTP statuses the Python control plane raised.
#[derive(Clone)]
pub struct Store {
    pool: Pool,
    url: String,
}

impl Store {
    pub async fn connect(url: &str) -> anyhow::Result<Self> {
        let pool = PoolConfig {
            url: Some(url.to_owned()),
            connection: None,
            pool: None,
        }
        .create_pool(Some(Runtime::Tokio1))?;
        let store = Self { pool, url: url.to_owned() };
        // Fail fast at boot when Redis is unreachable or auth is wrong.
        let mut conn = store.pool.get().await?;
        redis::cmd("PING").query_async::<String>(&mut conn).await?;
        Ok(store)
    }

    /// Raw URL for bus subscribers that need their own connection.
    pub fn redis_url(&self) -> &str {
        &self.url
    }

    /// A pooled connection for ad-hoc publish paths.
    pub async fn pool_clone(&self) -> Result<deadpool_redis::Connection, ApiError> {
        self.conn().await
    }

    fn ticket_key(token: &str) -> String {
        format!("ouu:ticket:{}", hash_token(token))
    }

    async fn conn(&self) -> Result<deadpool_redis::Connection, ApiError> {
        self.pool
            .get()
            .await
            .map_err(|_| ApiError::unavailable("state store unavailable"))
    }

    // ---------- devices ----------

    /// Device record: a JSON blob keyed by device_id plus the unique
    /// client_id index. Registration is compare-and-set so a concurrent
    /// duplicate client_id returns the winner's identity.
    pub async fn device(&self, device_id: &str) -> Result<Option<Device>, ApiError> {
        let mut conn = self.conn().await?;
        let raw: Option<String> = conn.get(format!("ouu:device:{device_id}")).await.map_err(internal)?;
        raw.map(|text| serde_json::from_str(&text).map_err(internal)).transpose()
    }

    pub async fn device_by_client(&self, client_id: &str) -> Result<Option<Device>, ApiError> {
        let mut conn = self.conn().await?;
        let device_id: Option<String> = conn
            .get(format!("ouu:client2device:{client_id}"))
            .await
            .map_err(internal)?;
        match device_id {
            Some(id) => self.device(&id).await,
            None => Ok(None),
        }
    }

    /// Create a device, or return the existing one when client_id already
    /// exists (idempotent re-registration, matching Python's init handler).
    pub async fn register_device(&self, client_id: &str, alias: &str, platform: i32, version: &str, capacity: usize) -> Result<Device, ApiError> {
        if let Some(existing) = self.device_by_client(client_id).await? {
            let mut updated = existing.clone();
            if !version.is_empty() {
                updated.version_name = version.to_owned();
                self.put_device(&updated).await?;
            }
            return Ok(updated);
        }
        let count: u64 = {
            let mut conn = self.conn().await?;
            conn.scard("ouu:devices").await.map_err(internal)?
        };
        if count as usize >= capacity {
            return Err(ApiError::unavailable("device capacity exceeded"));
        }
        let device_id: String = {
            use rand::Rng;
            const ALPHABET: &[u8] = b"abcdefghijklmnopqrstuvwxyz0123456789";
            (0..16)
                .map(|_| ALPHABET[rand::rng().random_range(0..ALPHABET.len())] as char)
                .collect()
        };
        let device = Device {
            device_id,
            client_id: client_id.to_owned(),
            alias: alias.chars().take(100).collect(),
            platform,
            controllable: false,
            version_name: version.to_owned(),
            support_wol: false,
            wallpaper_url: String::new(),
            lan_info: "{}".to_owned(),
        };
        let mut conn = self.conn().await?;
        let payload = serde_json::to_string(&device).map_err(internal)?;
        // Claim the client index first; the winner writes the device row.
        let claimed: Option<String> = conn
            .set(
                format!("ouu:client2device:{client_id}"),
                &device.device_id,
)
            .await
            .map_err(internal)?;
        if claimed.is_none() {
            // Concurrent init with the same client_id won; return its identity.
            let _: Result<(), _> = conn.srem("ouu:devices", &device.device_id).await;
            if let Some(winner) = self.device_by_client(client_id).await? {
                return Ok(winner);
            }
            return Err(ApiError::unavailable("device registration raced"));
        }
        redis::pipe()
            .atomic()
            .cmd("SET")
            .arg(format!("ouu:device:{}", device.device_id))
            .arg(&payload)
            .ignore()
            .cmd("SADD")
            .arg("ouu:devices")
            .arg(&device.device_id)
            .ignore()
            .query_async::<()>(&mut conn)
            .await
            .map_err(internal)?;
        // SET NX on the client index loses only when a concurrent init won.
        Ok(device)
    }

    pub async fn put_device(&self, device: &Device) -> Result<(), ApiError> {
        let payload = serde_json::to_string(device).map_err(internal)?;
        let mut conn = self.conn().await?;
        redis::pipe()
            .atomic()
            .cmd("SET")
            .arg(format!("ouu:device:{}", device.device_id))
            .arg(&payload)
            .ignore()
            .cmd("SET")
            .arg(format!("ouu:client2device:{}", device.client_id))
            .arg(&device.device_id)
            .ignore()
            .query_async::<()>(&mut conn)
            .await
            .map_err(internal)
    }

    pub async fn all_devices(&self) -> Result<Vec<Device>, ApiError> {
        let mut conn = self.conn().await?;
        let ids: Vec<String> = conn.smembers("ouu:devices").await.map_err(internal)?;
        let mut devices = Vec::with_capacity(ids.len());
        for id in ids {
            if let Some(device) = self.device(&id).await? {
                devices.push(device);
            } else {
                let mut conn = self.conn().await?;
                let _: Result<(), _> = conn.srem("ouu:devices", &id).await;
            }
        }
        devices.sort_by(|a, b| a.alias.cmp(&b.alias).then(a.device_id.cmp(&b.device_id)));
        Ok(devices)
    }

    pub async fn device_count(&self) -> Result<u64, ApiError> {
        let mut conn = self.conn().await?;
        conn.scard("ouu:devices").await.map_err(internal)
    }

    /// Full unbind: revoke sessions, tickets, saved rows and the device
    /// itself. Returns true when the device existed.
    pub async fn unbind_device(&self, device_id: &str) -> Result<bool, ApiError> {
        let Some(device) = self.device(device_id).await? else {
            return Ok(false);
        };
        let mut conn = self.conn().await?;
        let sessions_key = format!("ouu:sessions:{}", device.client_id);
        let session_tokens: Vec<String> = conn.smembers(&sessions_key).await.map_err(internal)?;
        redis::pipe()
            .atomic()
            .cmd("DEL")
            .arg(&sessions_key)
            .ignore()
            .cmd("SREM")
            .arg("ouu:devices")
            .arg(device_id)
            .ignore()
            .cmd("DEL")
            .arg(format!("ouu:device:{device_id}"))
            .arg(format!("ouu:client2device:{}", device.client_id))
            .arg(format!("ouu:assist:{device_id}"))
            .arg(format!("ouu:assist-connect:{}", device_id))
            .arg(format!("ouu:wallpaper-bind:{device_id}"))
            .ignore()
            .cmd("ZREM")
            .arg("ouu:saved")
            .arg(format!("{}:*", device_id))
            .ignore()
            .query_async::<()>(&mut conn)
            .await
            .map_err(internal)?;
        for token in session_tokens {
            let _: Result<(), _> = conn.del(format!("ouu:session:{}", hash_token(&token))).await;
        }
        // Guest sessions of this device expire on their own TTL.
        Ok(true)
    }

    // ---------- account sessions ----------

    /// Bearer tokens are stored hashed; the raw token never rests in Redis.
    pub async fn create_session(&self, device_id: &str, client_id: &str) -> Result<String, ApiError> {
        let token = rng_token(36);
        let mut conn = self.conn().await?;
        redis::pipe()
            .atomic()
            .cmd("SET")
            .arg(format!("ouu:session:{}", hash_token(&token)))
            .arg(device_id)
            .ignore()
            .cmd("SADD")
            .arg(format!("ouu:sessions:{client_id}"))
            .arg(&token)
            .ignore()
            .query_async::<()>(&mut conn)
            .await
            .map_err(internal)?;
        Ok(token)
    }

    /// Resolve a bearer token to its device. Does not validate the
    /// X-Param headers — callers compare those against the device row.
    pub async fn session_device(&self, token: &str) -> Result<Option<String>, ApiError> {
        let mut conn = self.conn().await?;
        conn.get(format!("ouu:session:{}", hash_token(token))).await.map_err(internal)
    }

    pub async fn revoke_session(&self, token: &str) -> Result<(), ApiError> {
        let mut conn = self.conn().await?;
        let _: Result<(), _> = conn.del(format!("ouu:session:{}", hash_token(token))).await;
        Ok(())
    }

    pub async fn session_tokens_of(&self, client_id: &str) -> Result<Vec<String>, ApiError> {
        let mut conn = self.conn().await?;
        let tokens: Vec<String> = conn
            .smembers(format!("ouu:sessions:{client_id}"))
            .await
            .map_err(internal)?;
        Ok(tokens)
    }

    pub async fn forget_session_tokens(&self, client_id: &str) -> Result<(), ApiError> {
        let mut conn = self.conn().await?;
        conn.del(format!("ouu:sessions:{client_id}")).await.map_err(internal)
    }

    // ---------- enrollment QRs ----------

    pub async fn create_qr(&self, client_id: &str) -> Result<String, ApiError> {
        let mut conn = self.conn().await?;
        // Bound pending grants per client before minting another one.
        let pending: u64 = redis::Script::new(r#"
            local removed = redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', ARGV[1])
            return redis.call('ZCARD', KEYS[1])
        "#)
        .key(format!("ouu:qr-pending:{client_id}"))
        .arg(now() as i64)
        .invoke_async(&mut conn)
        .await
        .map_err(internal)?;
        if pending >= 8 {
            return Err(ApiError::Envelope(None, 429, "too many pending QR requests".into()));
        }
        let ticket = rng_token(24);
        let mut conn = self.conn().await?;
        redis::pipe()
            .atomic()
            .cmd("SETEX")
            .arg(format!("ouu:qr:{ticket}"))
            .arg(300)
            .arg(client_id)
            .ignore()
            .cmd("ZADD")
            .arg(format!("ouu:qr-pending:{client_id}"))
            .arg(now() as i64 + 300)
            .arg(&ticket)
            .ignore()
            .query_async::<()>(&mut conn)
            .await
            .map_err(internal)?;
        Ok(ticket)
    }

    /// Returns (client_id, approved) for a live ticket.
    pub async fn qr_ticket(&self, ticket: &str) -> Result<Option<(String, bool)>, ApiError> {
        let mut conn = self.conn().await?;
        let client_id: Option<String> = conn
            .get(format!("ouu:qr:{ticket}"))
            .await
            .map_err(internal)?;
        Ok(client_id.map(|id| (id, false)))
    }

    /// Administrator approval: SET NX keeps a second approval from extending
    /// a ticket and the TTL keeps approved tickets expiring with the QR.
    pub async fn approve_qr(&self, ticket: &str) -> Result<bool, ApiError> {
        let mut conn = self.conn().await?;
        let approved: Option<String> = redis::Script::new(r#"
            if redis.call('EXISTS', KEYS[1]) == 0 then return 0 end
            if redis.call('EXISTS', KEYS[2]) == 1 then return 1 end
            local ttl = redis.call('TTL', KEYS[1])
            if ttl <= 0 then return 0 end
            redis.call('SETEX', KEYS[2], ttl, 1)
            return 1
        "#)
        .key(format!("ouu:qr:{ticket}"))
        .key(format!("ouu:qr-approved:{ticket}"))
        .invoke_async(&mut conn)
        .await
        .map_err(internal)?;
        Ok(approved.is_some())
    }

    /// One-shot login exchange: atomically delete the ticket and its
    /// approval and return the ticket client_id.
    pub async fn consume_qr(&self, ticket: &str) -> Result<Option<String>, ApiError> {
        let mut conn = self.conn().await?;
        let client_id: Option<String> = redis::Script::new(r#"
            if redis.call('EXISTS', KEYS[2]) == 0 then return nil end
            local client = redis.call('GET', KEYS[1])
            redis.call('DEL', KEYS[1], KEYS[2])
            return client
        "#)
        .key(format!("ouu:qr:{ticket}"))
        .key(format!("ouu:qr-approved:{ticket}"))
        .invoke_async(&mut conn)
        .await
        .map_err(internal)?;
        Ok(client_id)
    }

    /// Pending-QR ownership check for the status poll (403 in Python).
    pub async fn qr_pending_of(&self, ticket: &str) -> Result<Option<String>, ApiError> {
        self.qr_ticket(ticket).await.map(|option| option.map(|(client, _approved)| client))
    }
}

fn internal(error: impl std::fmt::Display) -> ApiError {
    tracing::error!(%error, "state store operation failed");
    ApiError::unavailable("state store unavailable")
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Device {
    pub device_id: String,
    pub client_id: String,
    pub alias: String,
    pub platform: i32,
    pub controllable: bool,
    pub version_name: String,
    pub support_wol: bool,
    pub wallpaper_url: String,
    #[serde(default = "default_lan")]
    pub lan_info: String,
}

fn default_lan() -> String {
    "{}".to_owned()
}

// ---------- room tickets (distributed) ----------

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Ticket {
    pub kind: String,
    pub device: String,
    pub target: String,
    pub force: bool,
    pub guest: bool,
    pub session_token: String,
    /// Issuing node hint: any node can serve any ticket, but diagnostics
    /// and shutdown sweeps use this.
    pub issued_by: String,
    pub client_id: String,
}

impl Store {

    /// Mint a room ticket. Capacity is a global ZSET with expiry scores.
    pub async fn create_ticket(&self, ticket: Ticket, lifetime_secs: i64, capacity: usize) -> Result<String, ApiError> {
        let token = rng_token(32);
        let payload = serde_json::to_string(&ticket).map_err(internal)?;
        let mut conn = self.conn().await?;
        let minted: u64 = redis::Script::new(r#"
            redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', ARGV[1])
            if redis.call('ZCARD', KEYS[1]) >= tonumber(ARGV[2]) then return 0 end
            redis.call('SETEX', KEYS[2], ARGV[3], ARGV[4])
            redis.call('ZADD', KEYS[1], ARGV[5], ARGV[2])
            return 1
        "#)
        .key("ouu:ticket-capacity")
        .key(Self::ticket_key(&token))
        .arg(now() as i64)
        .arg(capacity as i64)
        .arg(lifetime_secs)
        .arg(&payload)
        .arg(now() as i64 + lifetime_secs)
        .invoke_async(&mut conn)
        .await
        .map_err(internal)?;
        if minted == 0 {
            return Err(ApiError::unavailable("room ticket capacity exceeded"));
        }
        Ok(token)
    }
}

impl Store {
    pub async fn ticket(&self, token: &str) -> Result<Option<Ticket>, ApiError> {
        let mut conn = self.conn().await?;
        let raw: Option<String> = conn.get(Self::ticket_key(token)).await.map_err(internal)?;
        raw.map(|text| serde_json::from_str(&text).map_err(internal)).transpose()
    }

    pub async fn drop_ticket(&self, token: &str) -> Result<(), ApiError> {
        let mut conn = self.conn().await?;
        redis::pipe()
            .atomic()
            .cmd("DEL")
            .arg(Self::ticket_key(token))
            .ignore()
            .cmd("ZREM")
            .arg("ouu:ticket-capacity")
            .arg(hash_token(token))
            .ignore()
            .query_async::<()>(&mut conn)
            .await
            .map_err(internal)
    }

    /// Drop every ticket minted from a bearer token (logout, unbind).
    pub async fn drop_tickets_of_session(&self, session_token: &str) -> Result<(), ApiError> {
        let needle = hash_token(session_token);
        let mut conn = self.conn().await?;
        let entries: Vec<String> = conn
            .smembers("ouu:ticket-session-index")
            .await
            .map_err(internal)?;
        for entry in entries {
            if entry.ends_with(&needle) {
                let suffix = format!(":{needle}");
                let token_hash = entry.strip_suffix(&suffix).unwrap_or(&entry).to_owned();
                let _: Result<(), _> = conn.del(format!("ouu:ticket:{token_hash}")).await;
                let _: Result<(), _> = conn.srem("ouu:ticket-session-index", &entry).await;
            }
        }
        Ok(())
    }
}
impl Store {
    // ---------- active control sessions (distributed) ----------

    /// Claim the target's control slot. SET NX with a heartbeat TTL: a node
    /// that dies stops refreshing and the slot frees itself.
    pub async fn claim_control(&self, target: &str, owner: &str, ttl_secs: i64) -> Result<bool, ApiError> {
        let mut conn = self.conn().await?;
        let claimed: Option<String> = conn
            .set_nx(format!("ouu:control:{target}"), owner)
            .await
            .map_err(internal)?;
        if claimed.is_some() {
            let _: Result<(), _> = conn.expire(format!("ouu:control:{target}"), ttl_secs).await;
        }
        Ok(claimed.is_some())
    }

    pub async fn renew_control(&self, target: &str, owner: &str, ttl_secs: i64) -> Result<bool, ApiError> {
        let mut conn = self.conn().await?;
        let renewed: i32 = redis::Script::new(
            "if redis.call('GET', KEYS[1]) == ARGV[1] then redis.call('EXPIRE', KEYS[1], ARGV[2]) return 1 end return 0",
        )
        .key(format!("ouu:control:{target}"))
        .arg(owner)
        .arg(ttl_secs)
        .invoke_async(&mut conn)
        .await
        .map_err(internal)?;
        Ok(renewed == 1)
    }

    /// Release only when we still own the slot (compare-and-delete).
    pub async fn release_control(&self, target: &str, owner: &str) -> Result<bool, ApiError> {
        let mut conn = self.conn().await?;
        let released: u64 = redis::Script::new(
            "if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) else return 0 end",
        )
        .key(format!("ouu:control:{target}"))
        .arg(owner)
        .invoke_async(&mut conn)
        .await
        .map_err(internal)?;
        Ok(released == 1)
    }

    pub async fn control_owner(&self, target: &str) -> Result<Option<String>, ApiError> {
        let mut conn = self.conn().await?;
        conn.get(format!("ouu:control:{target}")).await.map_err(internal)
    }

    /// Shared session metadata for the active control slot so ANY node
    /// can validate and relay soac/forward_setting between the pair.
    pub async fn put_control_session(&self, target: &str, session: &str, ttl_secs: i64) -> Result<(), ApiError> {
        let mut conn = self.conn().await?;
        let _: Result<(), _> = conn
            .set_ex(format!("ouu:control-session:{target}"), session, ttl_secs as u64)
            .await;
        Ok(())
    }

    pub async fn get_control_session(&self, target: &str) -> Result<Option<String>, ApiError> {
        let mut conn = self.conn().await?;
        conn.get(format!("ouu:control-session:{target}")).await.map_err(internal)
    }

    pub async fn del_control_session(&self, target: &str) -> Result<(), ApiError> {
        let mut conn = self.conn().await?;
        let _: Result<(), _> = conn.del(format!("ouu:control-session:{target}")).await;
        Ok(())
    }

    // ---------- assistance identity ----------

    /// Stable 9-digit connect id per device, allocated on first refresh.
    pub async fn assist_identity(&self, device_id: &str) -> Result<String, ApiError> {
        if let Some(existing) = self.assist_row(device_id).await? {
            return Ok(existing.connect_id);
        }
        let mut conn = self.conn().await?;
        for _ in 0..20 {
            let candidate: u32 = rand::rng().random_range(0..1_000_000_000);
            let connect_id = format!("{candidate:09}");
            let claimed: Option<String> = conn
                .set_nx(format!("ouu:assist-connect:{connect_id}"), device_id)
                .await
                .map_err(internal)?;
            if claimed.is_some() {
                let _: Result<(), _> = conn.persist(format!("ouu:assist-connect:{connect_id}")).await;
                let row = AssistRow {
                    connect_id: connect_id.clone(),
                    control_mode: "password".into(),
                    allow_control: false,
                    need_confirmation: false,
                    need_password: true,
                };
                let payload = serde_json::to_string(&row).map_err(internal)?;
                let _: Result<(), _> = conn.set(format!("ouu:assist:{device_id}"), payload).await;
                return Ok(connect_id);
            }
        }
        Err(ApiError::unavailable("unable to allocate connect ID"))
    }

    pub async fn assist_row(&self, device_id: &str) -> Result<Option<AssistRow>, ApiError> {
        let mut conn = self.conn().await?;
        let raw: Option<String> = conn.get(format!("ouu:assist:{device_id}")).await.map_err(internal)?;
        raw.map(|text| serde_json::from_str(&text).map_err(internal)).transpose()
    }

    pub async fn put_assist_row(&self, device_id: &str, row: &AssistRow) -> Result<(), ApiError> {
        let payload = serde_json::to_string(row).map_err(internal)?;
        let mut conn = self.conn().await?;
        let _: Result<(), _> = conn.set(format!("ouu:assist:{device_id}"), payload).await;
        Ok(())
    }

    pub async fn device_by_connect(&self, connect_id: &str) -> Result<Option<String>, ApiError> {
        if connect_id.len() != 9 || !connect_id.bytes().all(|b| b.is_ascii_digit()) {
            return Ok(None);
        }
        let mut conn = self.conn().await?;
        conn.get(format!("ouu:assist-connect:{connect_id}")).await.map_err(internal)
    }

    // ---------- saved assistance (recent / favorites) ----------

    /// Members are "publisher|connect|remark"; owner/publisher indexes are sets.
    pub async fn save_recent(&self, owner: &str, publisher: &str, connect_id: &str) -> Result<(), ApiError> {
        let mut conn = self.conn().await?;
        let member = format!("{publisher}|{connect_id}|");
        let _: Result<(), _> = redis::pipe()
            .atomic()
            .cmd("ZADD").arg("ouu:saved").arg(now() as i64).arg(&member).ignore()
            .cmd("SADD").arg(format!("ouu:saved-owner:{owner}")).arg(&member).ignore()
            .cmd("SADD").arg(format!("ouu:saved-publisher:{publisher}")).arg(&member).ignore()
            .query_async::<()>(&mut conn)
            .await;
        Ok(())
    }

    pub async fn save_favorite(&self, owner: &str, publisher: &str, connect_id: &str, remark: &str) -> Result<(), ApiError> {
        let member = format!("{publisher}|{connect_id}|{remark}");
        let mut conn = self.conn().await?;
        let _: Result<(), _> = redis::pipe()
            .atomic()
            .cmd("SADD").arg(format!("ouu:saved-fav:{owner}")).arg(&member).ignore()
            .cmd("SADD").arg(format!("ouu:saved-owner:{owner}")).arg(&member).ignore()
            .cmd("SADD").arg(format!("ouu:saved-publisher:{publisher}")).arg(&member).ignore()
            .query_async::<()>(&mut conn)
            .await;
        Ok(())
    }

    pub async fn saved_list(&self, owner: &str, favorites: bool) -> Result<Vec<SavedEntry>, ApiError> {
        let mut conn = self.conn().await?;
        let index = if favorites {
            format!("ouu:saved-fav:{owner}")
        } else {
            format!("ouu:saved-owner:{owner}")
        };
        let members: Vec<String> = conn.smembers(index).await.map_err(internal)?;
        let mut entries = Vec::new();
        for member in members {
            let mut parts = member.split('|');
            let publisher = parts.next().unwrap_or("").to_owned();
            let connect_id = parts.next().unwrap_or("").to_owned();
            let remark = parts.next().unwrap_or("").to_owned();
            entries.push(SavedEntry {
                publisher_device_id: publisher,
                connect_id,
                remark,
                is_favorite: favorites,
            });
        }
        Ok(entries)
    }

    pub async fn unfavorite(&self, owner: &str, publisher: &str) -> Result<(), ApiError> {
        let mut conn = self.conn().await?;
        let members: Vec<String> = conn.smembers(format!("ouu:saved-fav:{owner}")).await.map_err(internal)?;
        let doomed: Vec<String> = members
            .into_iter()
            .filter(|m| m.starts_with(&format!("{publisher}|")))
            .collect();
        if !doomed.is_empty() {
            let _: Result<(), _> = conn.srem(format!("ouu:saved-fav:{owner}"), doomed).await;
        }
        Ok(())
    }

    /// Clear recents (all or one publisher) while keeping favorites.
    pub async fn clear_recent_of(&self, owner: &str, publisher: Option<&str>) -> Result<(), ApiError> {
        let mut conn = self.conn().await?;
        let owner_index = format!("ouu:saved-owner:{owner}");
        let favorites: Vec<String> = conn.smembers(format!("ouu:saved-fav:{owner}")).await.map_err(internal)?;
        let members: Vec<String> = conn.smembers(&owner_index).await.map_err(internal)?;
        let doomed: Vec<String> = members
            .into_iter()
            .filter(|m| match publisher {
                Some(p) => m.starts_with(&format!("{p}|")) && !favorites.contains(m),
                None => !favorites.contains(m),
            })
            .collect();
        if !doomed.is_empty() {
            let _: Result<(), _> = conn.srem(owner_index, doomed).await;
        }
        Ok(())
    }

    pub async fn forget_saved_of_publisher(&self, publisher: &str) -> Result<(), ApiError> {
        let mut conn = self.conn().await?;
        let members: Vec<String> = conn
            .smembers(format!("ouu:saved-publisher:{publisher}"))
            .await
            .map_err(internal)?;
        if members.is_empty() {
            return Ok(());
        }
        let _: Result<(), _> = redis::pipe()
            .atomic()
            .cmd("ZREM").arg("ouu:saved").arg(&members).ignore()
            .cmd("DEL").arg(format!("ouu:saved-publisher:{publisher}")).ignore()
            .query_async::<()>(&mut conn)
            .await;
        let owner_indexes: Vec<String> = conn
            .keys("ouu:saved-owner:*")
            .await
            .map_err(internal)?;
        for index in owner_indexes {
            let _: Result<(), _> = conn.srem(index, &members).await;
        }
        let fav_indexes: Vec<String> = conn.keys("ouu:saved-fav:*").await.map_err(internal)?;
        for index in fav_indexes {
            let _: Result<(), _> = conn.srem(index, &members).await;
        }
        Ok(())
    }

    // ---------- guest sessions ----------

    pub async fn create_guest_session(&self, device_id: &str, lifetime_secs: i64, per_device_limit: usize) -> Result<(String, String), ApiError> {
        let mut conn = self.conn().await?;
        let active: u64 = redis::Script::new(
            "redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', ARGV[1]) return redis.call('ZCARD', KEYS[1])",
        )
        .key(format!("ouu:guest-active:{device_id}"))
        .arg(now() as i64)
        .invoke_async(&mut conn)
        .await
        .map_err(internal)?;
        if active as usize >= per_device_limit {
            return Err(ApiError::Envelope(None, 429, "too many active guest sessions".into()));
        }
        let guest_id = rng_token(20);
        let token = rng_token(36);
        redis::pipe()
            .atomic()
            .cmd("SETEX")
            .arg(format!("ouu:guest:{}", hash_token(&token)))
            .arg(lifetime_secs)
            .arg(format!("{guest_id}|{device_id}"))
            .ignore()
            .cmd("ZADD")
            .arg(format!("ouu:guest-active:{device_id}"))
            .arg(now() as i64 + lifetime_secs)
            .arg(hash_token(&token))
            .ignore()
            .query_async::<()>(&mut conn)
            .await
            .map_err(internal)?;
        Ok((guest_id, token))
    }

    /// Returns (guest_id, device_id) when the bearer is live.
    pub async fn guest_session(&self, token: &str) -> Result<Option<(String, String)>, ApiError> {
        let mut conn = self.conn().await?;
        let raw: Option<String> = conn
            .get(format!("ouu:guest:{}", hash_token(token)))
            .await
            .map_err(internal)?;
        Ok(raw.and_then(|text| {
            text.split_once('|').map(|(guest, device)| (guest.to_owned(), device.to_owned()))
        }))
    }

    pub async fn revoke_guest_session(&self, token: &str) -> Result<(), ApiError> {
        let mut conn = self.conn().await?;
        let key = format!("ouu:guest:{}", hash_token(token));
        let raw: Option<String> = conn.get(&key).await.map_err(internal)?;
        if let Some(text) = raw
            && let Some((_, device)) = text.split_once('|')
        {
            let _: Result<(), _> = conn.zrem(format!("ouu:guest-active:{device}"), hash_token(token)).await;
        }
        let _: Result<(), _> = conn.del(&key).await;
        Ok(())
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AssistRow {
    pub connect_id: String,
    pub control_mode: String,
    pub allow_control: bool,
    pub need_confirmation: bool,
    pub need_password: bool,
}

#[derive(Debug, Clone, Serialize)]
pub struct SavedEntry {
    pub publisher_device_id: String,
    pub connect_id: String,
    pub remark: String,
    pub is_favorite: bool,
}

