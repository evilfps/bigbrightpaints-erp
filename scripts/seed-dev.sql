-- Seed dev data for ERP Domain (run after app has created schema via Flyway)
-- Safe to re-run: uses ON CONFLICT DO NOTHING where applicable

CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- Ensure base role exists
INSERT INTO roles(name, description)
VALUES ('ROLE_ADMIN', 'Administrator')
ON CONFLICT (name) DO NOTHING;

-- Company
INSERT INTO companies(name, code, timezone)
VALUES ('Acme Corp', 'ACME', 'UTC')
ON CONFLICT (code) DO NOTHING;

-- User with bcrypt password
INSERT INTO app_users(email, password_hash, display_name, enabled)
VALUES ('admin@bbp.com', crypt('admin123', gen_salt('bf')), 'Admin', true)
ON CONFLICT (email) DO NOTHING;

-- Link user to ROLE_ADMIN
WITH r AS (
    SELECT id FROM roles WHERE name = 'ROLE_ADMIN'
), u AS (
    SELECT id FROM app_users WHERE email = 'admin@bbp.com'
)
INSERT INTO user_roles(user_id, role_id)
SELECT u.id, r.id FROM u, r
ON CONFLICT DO NOTHING;

-- Link user to ACME company
WITH c AS (
    SELECT id FROM companies WHERE code = 'ACME'
), u AS (
    SELECT id FROM app_users WHERE email = 'admin@bbp.com'
)
INSERT INTO user_companies(user_id, company_id)
SELECT u.id, c.id FROM u, c
ON CONFLICT DO NOTHING;

