CREATE TABLE IF NOT EXISTS artisan_users (
    id UUID PRIMARY KEY,
    username VARCHAR(80) NOT NULL UNIQUE,
    display_name VARCHAR(120) NOT NULL,
    password_hash VARCHAR(120) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS artisan_user_sessions (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES artisan_users(id) ON DELETE CASCADE,
    token_hash VARCHAR(128) NOT NULL UNIQUE,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_artisan_user_sessions_user_expires
    ON artisan_user_sessions(user_id, expires_at DESC);

ALTER TABLE artisan_canvases
    ADD COLUMN IF NOT EXISTS user_id UUID REFERENCES artisan_users(id) ON DELETE CASCADE;

ALTER TABLE artisan_assets
    ADD COLUMN IF NOT EXISTS user_id UUID REFERENCES artisan_users(id) ON DELETE SET NULL;

CREATE INDEX IF NOT EXISTS idx_artisan_canvases_user_updated
    ON artisan_canvases(user_id, updated_at DESC, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_artisan_assets_user_created
    ON artisan_assets(user_id, created_at DESC);
