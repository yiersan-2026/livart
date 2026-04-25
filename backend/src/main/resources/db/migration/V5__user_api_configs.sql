CREATE TABLE IF NOT EXISTS artisan_user_api_configs (
    user_id UUID PRIMARY KEY REFERENCES artisan_users(id) ON DELETE CASCADE,
    base_url VARCHAR(500) NOT NULL,
    api_key TEXT NOT NULL,
    image_model VARCHAR(120) NOT NULL,
    chat_model VARCHAR(120) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
