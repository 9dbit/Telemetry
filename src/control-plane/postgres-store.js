import pg from 'pg';
import fs from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const { Pool } = pg;
const __dirname = path.dirname(fileURLToPath(import.meta.url));

export class PostgresTelemetryStore {
  constructor({ connectionString }) {
    if (!connectionString) throw new Error('DATABASE_URL is required');
    this.pool = new Pool({ connectionString, ssl: shouldUseSsl(connectionString) ? { rejectUnauthorized: false } : undefined });
  }

  async init() {
    const sql = await fs.readFile(path.join(__dirname, 'schema.sql'), 'utf8');
    await this.pool.query(sql);
  }

  async recordBatch(events, sourceIp) {
    const client = await this.pool.connect();
    try {
      await client.query('BEGIN');
      for (const event of events) {
        await this.upsertInstall(client, event, sourceIp);
        await client.query(
          `INSERT INTO telemetry_events (install_id,event_type,occurred_at,platform,app_version,event_data,source_ip)
           VALUES ($1,$2,$3,$4,$5,$6::jsonb,$7)`,
          [event.installId, event.type, event.timestamp, event.platform ?? null, event.appVersion ?? null, JSON.stringify(event.data ?? {}), sourceIp]
        );
        await this.applyAggregate(client, event);
      }
      await client.query('COMMIT');
    } catch (error) {
      await client.query('ROLLBACK');
      throw error;
    } finally {
      client.release();
    }
  }

  async recordDownload({ installId = null, platform, appVersion = null, sourceIp = null }) {
    await this.pool.query(
      'INSERT INTO telemetry_downloads (install_id,platform,app_version,source_ip) VALUES ($1,$2,$3,$4)',
      [installId, platform, appVersion, sourceIp]
    );
  }

  async overview() {
    const { rows } = await this.pool.query(`
      WITH install_stats AS (
        SELECT
          count(*)::int AS installs,
          count(*) FILTER (WHERE last_seen_at >= now() - interval '5 minutes')::int AS active_now,
          count(*) FILTER (WHERE last_seen_at >= now() - interval '1 day')::int AS dau,
          count(*) FILTER (WHERE last_seen_at >= now() - interval '7 days')::int AS wau,
          count(*) FILTER (WHERE last_seen_at >= now() - interval '30 days')::int AS mau,
          coalesce(sum(paired_contact_count),0)::bigint AS paired_contacts,
          coalesce(sum(total_screen_seconds),0)::bigint AS screen_seconds
        FROM telemetry_installs
      ), event_stats AS (
        SELECT
          count(*) FILTER (WHERE event_type='media.transfer')::int AS media_transfers,
          count(*) FILTER (WHERE event_type='call.summary' AND event_data->>'kind'='voice')::int AS voice_calls,
          count(*) FILTER (WHERE event_type='call.summary' AND event_data->>'kind'='video')::int AS video_calls,
          coalesce(sum((event_data->>'durationSeconds')::bigint) FILTER (WHERE event_type='call.summary'),0)::bigint AS call_seconds,
          coalesce(sum((event_data->>'bytes')::bigint) FILTER (WHERE event_type='media.transfer'),0)::bigint AS media_bytes,
          coalesce(sum((event_data->>'bytesSent')::bigint) FILTER (WHERE event_type='transport.usage'),0)::bigint AS transport_bytes_sent,
          coalesce(sum((event_data->>'bytesReceived')::bigint) FILTER (WHERE event_type='transport.usage'),0)::bigint AS transport_bytes_received
        FROM telemetry_events
      ), download_stats AS (
        SELECT count(*)::int AS downloads FROM telemetry_downloads
      )
      SELECT * FROM install_stats CROSS JOIN event_stats CROSS JOIN download_stats
    `);
    return rows[0];
  }

  async listDevices({ limit = 100, offset = 0 } = {}) {
    const { rows } = await this.pool.query(`
      SELECT i.install_id, i.platform, i.app_version, i.first_seen_at, i.last_seen_at,
             i.last_ip::text, i.status, i.paired_contact_count, i.total_screen_seconds,
             p.display_name, p.about, p.avatar_url, p.avatar_template_id
      FROM telemetry_installs i
      LEFT JOIN telemetry_profiles p USING (install_id)
      ORDER BY i.last_seen_at DESC
      LIMIT $1 OFFSET $2`, [limit, offset]);
    return rows;
  }

  async fieldView() {
    const { rows } = await this.pool.query(`
      SELECT DISTINCT ON (l.install_id)
        l.install_id, l.latitude, l.longitude, l.accuracy_meters, l.occurred_at,
        i.status, i.platform, i.app_version, p.display_name, p.avatar_url, p.avatar_template_id
      FROM telemetry_locations l
      JOIN telemetry_installs i USING (install_id)
      LEFT JOIN telemetry_profiles p USING (install_id)
      WHERE l.occurred_at >= now() - interval '24 hours'
      ORDER BY l.install_id, l.occurred_at DESC`);
    return rows;
  }

  async setDeviceStatus(installId, state, { actor = 'admin', reason = '' } = {}) {
    const client = await this.pool.connect();
    try {
      await client.query('BEGIN');
      const result = await client.query(
        `UPDATE telemetry_installs SET status=$2, updated_at=now() WHERE install_id=$1 RETURNING install_id,status`,
        [installId, state]
      );
      if (!result.rowCount) throw new Error('device not found');
      await client.query(
        'INSERT INTO telemetry_admin_actions (install_id,action,actor,reason) VALUES ($1,$2,$3,$4)',
        [installId, state, actor, reason]
      );
      await client.query('COMMIT');
      return result.rows[0];
    } catch (error) {
      await client.query('ROLLBACK');
      throw error;
    } finally {
      client.release();
    }
  }

  async upsertInstall(client, event, sourceIp) {
    await client.query(`
      INSERT INTO telemetry_installs (install_id,platform,app_version,last_ip)
      VALUES ($1,$2,$3,$4)
      ON CONFLICT (install_id) DO UPDATE SET
        platform=coalesce(EXCLUDED.platform,telemetry_installs.platform),
        app_version=coalesce(EXCLUDED.app_version,telemetry_installs.app_version),
        last_seen_at=now(), last_ip=EXCLUDED.last_ip, updated_at=now()`,
      [event.installId, event.platform ?? null, event.appVersion ?? null, sourceIp]
    );
  }

  async applyAggregate(client, event) {
    const data = event.data ?? {};
    if (event.type === 'contacts.snapshot') {
      await client.query('UPDATE telemetry_installs SET paired_contact_count=$2,updated_at=now() WHERE install_id=$1', [event.installId, data.pairedCount]);
    } else if (event.type === 'screen.time') {
      await client.query('UPDATE telemetry_installs SET total_screen_seconds=total_screen_seconds+$2,updated_at=now() WHERE install_id=$1', [event.installId, data.seconds]);
    } else if (event.type === 'location.snapshot') {
      await client.query(
        'INSERT INTO telemetry_locations (install_id,occurred_at,latitude,longitude,accuracy_meters) VALUES ($1,$2,$3,$4,$5)',
        [event.installId, event.timestamp, data.latitude, data.longitude, data.accuracyMeters ?? null]
      );
    } else if (event.type === 'profile.updated' && data.cloudSync === true) {
      await client.query(`
        INSERT INTO telemetry_profiles (install_id,display_name,about,avatar_url,avatar_template_id,cloud_sync_enabled,updated_at)
        VALUES ($1,$2,$3,$4,$5,true,now())
        ON CONFLICT (install_id) DO UPDATE SET
          display_name=EXCLUDED.display_name, about=EXCLUDED.about, avatar_url=EXCLUDED.avatar_url,
          avatar_template_id=EXCLUDED.avatar_template_id, cloud_sync_enabled=true, updated_at=now()`,
        [event.installId, String(data.displayName ?? '').slice(0,48), String(data.about ?? '').slice(0,120), data.avatarUrl ?? null, data.avatarTemplateId ?? null]
      );
    }
  }
}

function shouldUseSsl(connectionString) {
  return !/localhost|127\.0\.0\.1/.test(connectionString);
}
