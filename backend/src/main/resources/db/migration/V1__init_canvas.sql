CREATE TABLE IF NOT EXISTS artisan_canvases (
    id UUID PRIMARY KEY,
    title VARCHAR(200) NOT NULL DEFAULT '默认画布',
    state_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS artisan_canvas_snapshots (
    id UUID PRIMARY KEY,
    canvas_id UUID NOT NULL REFERENCES artisan_canvases(id) ON DELETE CASCADE,
    state_json JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_artisan_canvas_snapshots_canvas_created
    ON artisan_canvas_snapshots(canvas_id, created_at DESC);

CREATE TABLE IF NOT EXISTS artisan_assets (
    id UUID PRIMARY KEY,
    canvas_id UUID REFERENCES artisan_canvases(id) ON DELETE SET NULL,
    object_key VARCHAR(512) NOT NULL UNIQUE,
    url_path VARCHAR(512) NOT NULL,
    original_filename VARCHAR(255),
    mime_type VARCHAR(120) NOT NULL,
    size_bytes BIGINT NOT NULL,
    width INTEGER,
    height INTEGER,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_artisan_assets_canvas_created
    ON artisan_assets(canvas_id, created_at DESC);
