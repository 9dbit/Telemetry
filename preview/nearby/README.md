# Telemetry M1.3 Nearby Intelligence Preview

This folder is an isolated interactive product preview for M1.3 Nearby Intelligence.

## Purpose

Review UI/UX before native iOS integration. This preview does not use CoreBluetooth and does not modify the production message, trust, encryption, queue, or receipt pipeline.

## Preview features

- List and Radar views
- Mock peers with smoothed RSSI movement
- Signal-based proximity labels
- Trusted, Gateway, and Internet filters
- Peer detail bottom sheet
- Capability badges
- Last-seen and route-intelligence presentation
- Responsive iPhone-first layout

## Product rule

Radar placement represents signal proximity only. It must never be presented as precise GPS or physical positioning unless a future ranging transport can support that claim.

## Run locally

```bash
npm start
```

Then open `http://localhost:3000`.

## Railway

Set the service root directory to `preview/nearby` and deploy this branch. Healthcheck: `/health`.

## Integration gate

Preview changes should be reviewed and approved before porting into `mobile/ios-preview`. The native implementation must consume a Telemetry adapter interface so the UI remains transport-agnostic.
