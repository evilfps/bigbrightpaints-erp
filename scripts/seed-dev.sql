-- Seed dev data for ERP Domain (run after app has created schema via Flyway)
-- Safe to re-run: uses ON CONFLICT DO NOTHING where applicable

CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- Ensure platform roles exist
INSERT INTO roles(name, description)
VALUES
    ('ROLE_ADMIN', 'Administrator'),
    ('ROLE_ACCOUNTING', 'Accounting, finance, HR, and inventory operator'),
    ('ROLE_FACTORY', 'Factory, production, and dispatch operator'),
    ('ROLE_SALES', 'Sales operations and dealer management'),
    ('ROLE_DEALER', 'Dealer workspace user')
ON CONFLICT (name) DO NOTHING;

-- Portal permissions used by the UI
INSERT INTO permissions(code, description)
VALUES
    ('portal:accounting', 'Access to the accounting operator portal'),
    ('portal:factory', 'Access to the factory control portal'),
    ('portal:sales', 'Access to the sales console'),
    ('portal:dealer', 'Access to dealer workspace')
ON CONFLICT (code) DO NOTHING;

-- Map permissions to functional roles
WITH accounting_role AS (
    SELECT id FROM roles WHERE name = 'ROLE_ACCOUNTING'
), accounting_perm AS (
    SELECT id FROM permissions WHERE code = 'portal:accounting'
)
INSERT INTO role_permissions(role_id, permission_id)
SELECT accounting_role.id, accounting_perm.id FROM accounting_role, accounting_perm
ON CONFLICT DO NOTHING;

WITH factory_role AS (
    SELECT id FROM roles WHERE name = 'ROLE_FACTORY'
), factory_perm AS (
    SELECT id FROM permissions WHERE code = 'portal:factory'
)
INSERT INTO role_permissions(role_id, permission_id)
SELECT factory_role.id, factory_perm.id FROM factory_role, factory_perm
ON CONFLICT DO NOTHING;

WITH sales_role AS (
    SELECT id FROM roles WHERE name = 'ROLE_SALES'
), sales_perm AS (
    SELECT id FROM permissions WHERE code = 'portal:sales'
)
INSERT INTO role_permissions(role_id, permission_id)
SELECT sales_role.id, sales_perm.id FROM sales_role, sales_perm
ON CONFLICT DO NOTHING;

WITH dealer_role AS (
    SELECT id FROM roles WHERE name = 'ROLE_DEALER'
), dealer_perm AS (
    SELECT id FROM permissions WHERE code = 'portal:dealer'
)
INSERT INTO role_permissions(role_id, permission_id)
SELECT dealer_role.id, dealer_perm.id FROM dealer_role, dealer_perm
ON CONFLICT DO NOTHING;

-- Admins get access to every portal surface
WITH admin_role AS (
    SELECT id FROM roles WHERE name = 'ROLE_ADMIN'
), portal_perms AS (
    SELECT id FROM permissions WHERE code IN ('portal:accounting', 'portal:factory', 'portal:sales', 'portal:dealer')
)
INSERT INTO role_permissions(role_id, permission_id)
SELECT admin_role.id, portal_perms.id FROM admin_role, portal_perms
ON CONFLICT DO NOTHING;

-- Company
INSERT INTO companies(name, code, timezone)
VALUES ('Acme Corp', 'ACME', 'UTC')
ON CONFLICT (code) DO NOTHING;

-- Base users for quick manual testing
INSERT INTO app_users(email, password_hash, display_name, enabled)
VALUES
    ('admin@bbp.com', crypt('admin123', gen_salt('bf')), 'Admin', true),
    ('accounting@bbp.com', crypt('accounting123', gen_salt('bf')), 'Accounting Ops', true),
    ('factory@bbp.com', crypt('factory123', gen_salt('bf')), 'Factory Lead', true),
    ('sales@bbp.com', crypt('sales123', gen_salt('bf')), 'Sales Lead', true),
    ('dealer@bbp.com', crypt('dealer123', gen_salt('bf')), 'Dealer Portal User', true)
ON CONFLICT (email) DO NOTHING;

-- Link users to roles
WITH admin_role AS (
    SELECT id FROM roles WHERE name = 'ROLE_ADMIN'
), admin_user AS (
    SELECT id FROM app_users WHERE email = 'admin@bbp.com'
)
INSERT INTO user_roles(user_id, role_id)
SELECT admin_user.id, admin_role.id FROM admin_user, admin_role
ON CONFLICT DO NOTHING;

WITH accounting_role AS (
    SELECT id FROM roles WHERE name = 'ROLE_ACCOUNTING'
), accounting_user AS (
    SELECT id FROM app_users WHERE email = 'accounting@bbp.com'
)
INSERT INTO user_roles(user_id, role_id)
SELECT accounting_user.id, accounting_role.id FROM accounting_user, accounting_role
ON CONFLICT DO NOTHING;

WITH factory_role AS (
    SELECT id FROM roles WHERE name = 'ROLE_FACTORY'
), factory_user AS (
    SELECT id FROM app_users WHERE email = 'factory@bbp.com'
)
INSERT INTO user_roles(user_id, role_id)
SELECT factory_user.id, factory_role.id FROM factory_user, factory_role
ON CONFLICT DO NOTHING;

WITH sales_role AS (
    SELECT id FROM roles WHERE name = 'ROLE_SALES'
), sales_user AS (
    SELECT id FROM app_users WHERE email = 'sales@bbp.com'
)
INSERT INTO user_roles(user_id, role_id)
SELECT sales_user.id, sales_role.id FROM sales_user, sales_role
ON CONFLICT DO NOTHING;

WITH dealer_role AS (
    SELECT id FROM roles WHERE name = 'ROLE_DEALER'
), dealer_user AS (
    SELECT id FROM app_users WHERE email = 'dealer@bbp.com'
)
INSERT INTO user_roles(user_id, role_id)
SELECT dealer_user.id, dealer_role.id FROM dealer_user, dealer_role
ON CONFLICT DO NOTHING;

-- Link every seed user to ACME company
WITH company_row AS (
    SELECT id FROM companies WHERE code = 'ACME'
)
INSERT INTO user_companies(user_id, company_id)
SELECT id, company_row.id
FROM app_users, company_row
WHERE email IN ('admin@bbp.com', 'accounting@bbp.com', 'factory@bbp.com', 'sales@bbp.com', 'dealer@bbp.com')
ON CONFLICT DO NOTHING;
