ALTER TABLE companies
    ADD COLUMN IF NOT EXISTS default_gst_rate NUMERIC(9,4) NOT NULL DEFAULT 18;

UPDATE companies
SET default_gst_rate = 18
WHERE default_gst_rate IS NULL;
