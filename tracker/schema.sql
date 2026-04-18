-- Run once in your Vercel Postgres console (Storage → your DB → Query)
CREATE TABLE IF NOT EXISTS events (
  id             SERIAL PRIMARY KEY,
  client_id      TEXT        NOT NULL,   -- anonymous UUID, persisted on user's machine
  tool_name      TEXT        NOT NULL,   -- e.g. "get_open_editors"
  plugin_version TEXT,                   -- e.g. "2.4.0"
  ide_product    TEXT,                   -- e.g. "IntelliJ IDEA"
  ide_version    TEXT,                   -- e.g. "2025.3"
  locale         TEXT,                   -- e.g. "fr-FR"
  created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Index for fast GROUP BY queries
CREATE INDEX IF NOT EXISTS events_tool_name_idx ON events (tool_name);

-- Migration: add columns to existing table
ALTER TABLE events ADD COLUMN IF NOT EXISTS ide_product TEXT;
ALTER TABLE events ADD COLUMN IF NOT EXISTS ide_version TEXT;
ALTER TABLE events ADD COLUMN IF NOT EXISTS locale      TEXT;
ALTER TABLE events ADD COLUMN IF NOT EXISTS ai_client   TEXT;
