CREATE TABLE IF NOT EXISTS artisan_external_generated_images (
    id UUID PRIMARY KEY,
    job_id UUID NOT NULL,
    owner_id UUID NOT NULL,
    asset_id UUID NOT NULL REFERENCES artisan_assets(id) ON DELETE CASCADE,
    label VARCHAR(40) NOT NULL DEFAULT 'external-image',
    image_index INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_artisan_external_generated_images_job_index UNIQUE (job_id, image_index)
);

CREATE INDEX IF NOT EXISTS idx_artisan_external_generated_images_owner_job
    ON artisan_external_generated_images (owner_id, job_id, image_index, created_at DESC);
