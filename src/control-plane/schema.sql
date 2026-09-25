CREATE TABLE IF NOT EXISTS telemetry_installs (
  install_id TEXT PRIMARY KEY,
  platform TEXT,
  app_version TEXT,
  first_seen_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  last_seen_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  last_ip INET,
  status TEXT NOT NULL DEFAULT 'active' CHECK (status IN ('active','suspended','released')),
  paired_contact_count INTEGER NOT NULL DEFAULT 0,
  total_screen_seconds BIGINT NOT NULL DEFAULT 0,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS telemetry_profiles (
  install_id TEXT PRIMARY KEY REFERENCES telemetry_installs(install_id) ON DELETE CASCADE,
  display_name TEXT NOT NULL DEFAULT '',
  about TEXT NOT NULL DEFAULT '',
  avatar_url TEXT,
  avatar_template_id TEXT,
  cloud_sync_enabled BOOLEAN NOT NULL DEFAULT false,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS telemetry_events (
  id BIGSERIAL PRIMARY KEY,
  install_id TEXT NOT NULL REFERENCES telemetry_installs(install_id) ON DELETE CASCADE,
  event_type TEXT NOT NULL,
  occurred_at TIMESTAMPTZ NOT NULL,
  platform TEXT,
  app_version TEXT,
  event_data JSONB NOT NULL DEFAULT '{}'::jsonb,
  source_ip INET,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS telemetry_events_install_time_idx ON telemetry_events(install_id, occurred_at DESC);
CREATE INDEX IF NOT EXISTS telemetry_events_type_time_idx ON telemetry_events(event_type, occurred_at DESC);

CREATE TABLE IF NOT EXISTS telemetry_locations (
  id BIGSERIAL PRIMARY KEY,
  install_id TEXT NOT NULL REFERENCES telemetry_installs(install_id) ON DELETE CASCADE,
  occurred_at TIMESTAMPTZ NOT NULL,
  latitude DOUBLE PRECISION NOT NULL CHECK (latitude BETWEEN -90 AND 90),
  longitude DOUBLE PRECISION NOT NULL CHECK (longitude BETWEEN -180 AND 180),
  accuracy_meters DOUBLE PRECISION,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS telemetry_locations_install_time_idx ON telemetry_locations(install_id, occurred_at DESC);

CREATE TABLE IF NOT EXISTS telemetry_admin_actions (
  id BIGSERIAL PRIMARY KEY,
  install_id TEXT REFERENCES telemetry_installs(install_id) ON DELETE SET NULL,
  action TEXT NOT NULL,
  actor TEXT NOT NULL DEFAULT 'admin',
  reason TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS telemetry_downloads (
  id BIGSERIAL PRIMARY KEY,
  install_id TEXT,
  platform TEXT NOT NULL,
  app_version TEXT,
  source_ip INET,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS telemetry_downloads_created_idx ON telemetry_downloads(created_at DESC);
