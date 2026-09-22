pub const CORE_VERSION: &str = "telemetry-core/0.2";

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum TransportKind {
    Ble,
    WifiAware,
    WifiDirect,
    Internet,
    Lan,
    Lora,
    SatelliteGateway,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PublicIdentity {
    pub version: u16,
    pub device_id: String,
    pub signing_public_key: String,
    pub exchange_public_key: String,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct EnvelopeHeader {
    pub protocol: String,
    pub message_id: String,
    pub conversation_id: String,
    pub sender_id: String,
    pub recipient_id: String,
    pub created_at: String,
    pub hop_count: u8,
    pub hop_limit: u8,
    pub content_type: String,
}

impl EnvelopeHeader {
    pub fn can_forward(&self) -> bool {
        self.hop_count < self.hop_limit
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PairingOfferHeader {
    pub version: String,
    pub device_name: String,
    pub capabilities: Vec<String>,
    pub nonce: String,
    pub issued_at: String,
    pub expires_at: String,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DeliveryReceiptHeader {
    pub version: String,
    pub message_id: String,
    pub original_sender_id: String,
    pub recipient_id: String,
    pub state: String,
    pub at: String,
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn forwarding_obeys_hop_limit() {
        let header = EnvelopeHeader {
            protocol: "telemetry/0.1".into(),
            message_id: "m1".into(),
            conversation_id: "c1".into(),
            sender_id: "a".into(),
            recipient_id: "b".into(),
            created_at: "now".into(),
            hop_count: 1,
            hop_limit: 2,
            content_type: "application/telemetry+json".into(),
        };
        assert!(header.can_forward());
    }
}
