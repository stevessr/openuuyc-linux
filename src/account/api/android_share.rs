use super::*;

const ANDROID_CONTROL_RPC: Contract = Contract {
    retry: RetryPolicy::ConnectOnly,
    method: Method::Post,
    path: "/api/v1/transport/{id}/rpc/request",
    identity: IdentityScope::AccountDevice,
    timeout: REQUEST_TIMEOUT,
    json_content_type: true,
};

impl NrdApi {
    pub(crate) async fn android_control_rpc(
        &self,
        target_id: &str,
        action: &str,
        local_id: &str,
        alias: &str,
    ) -> Result<ApiEnvelope<serde_json::Value>> {
        validate_device_id(target_id)?;
        validate_device_id(local_id)?;
        if !matches!(action, "request_control" | "cancel_request") {
            bail!("invalid Android share RPC action");
        }
        let path = ANDROID_CONTROL_RPC.path.replace("{id}", target_id);
        let body = android_control_request(action, local_id, alias)?;
        self.send(ANDROID_CONTROL_RPC, &path, serde_json::to_vec(&body)?).await
    }
}

fn android_control_request(action: &str, local_id: &str, alias: &str) -> Result<serde_json::Value> {
    validate_device_id(local_id)?;
    if !matches!(action, "request_control" | "cancel_request") {
        bail!("invalid Android share RPC action");
    }
    Ok(serde_json::json!({
        "p": "android_control_request",
        "need_response": false,
        "data": { "action": action, "device_info": {
            "device_id": local_id, "platform": 1, "alias": alias,
            "version_name": crate::protocol::official_version::CLIENT
        }}
    }))
}

#[cfg(test)]
mod tests {
    use super::android_control_request;

    #[test]
    fn android_share_rpc_matches_official_request_shape() {
        let request = android_control_request("request_control", "0123456789abcdef", "OpenUUYC Desktop").unwrap();
        assert_eq!(request["p"], "android_control_request");
        assert_eq!(request["need_response"], false);
        assert_eq!(request["data"]["action"], "request_control");
        assert_eq!(request["data"]["device_info"]["device_id"], "0123456789abcdef");
        assert_eq!(request["data"]["device_info"]["platform"], 1);
        let cancel = android_control_request("cancel_request", "0123456789abcdef", "OpenUUYC Desktop").unwrap();
        assert_eq!(cancel["data"]["action"], "cancel_request");
        assert!(android_control_request("accept_control", "0123456789abcdef", "desktop").is_err());
        assert!(android_control_request("request_control", "invalid", "desktop").is_err());
    }
}
