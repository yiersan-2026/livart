CREATE TABLE IF NOT EXISTS artisan_external_image_parse_history (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES artisan_users(id) ON DELETE CASCADE,
    source_url TEXT NOT NULL,
    source_host VARCHAR(255),
    image_count INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_parsed_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_artisan_external_image_parse_history_user_url UNIQUE (user_id, source_url)
);

CREATE INDEX IF NOT EXISTS idx_artisan_external_image_parse_history_user_time
    ON artisan_external_image_parse_history (user_id, last_parsed_at DESC);
