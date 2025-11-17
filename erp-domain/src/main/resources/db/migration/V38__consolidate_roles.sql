-- Consolidate platform roles down to the five supported variants

-- Drop obsolete permissions connected to dealer-only role creation
DELETE FROM role_permissions
WHERE permission_id IN (
    SELECT id FROM permissions WHERE code = 'rbac:dealer-role:create'
);

DELETE FROM permissions
WHERE code = 'rbac:dealer-role:create';

-- Remove legacy role entries, including ROLE_DEALER_* prefixed records
DELETE FROM user_roles WHERE role_id IN (
    SELECT id FROM roles
    WHERE name IN ('ROLE_FACTORY_MANAGER', 'ROLE_SALES_MANAGER', 'ROLE_DEALER_OPERATOR', 'ROLE_INVENTORY', 'ROLE_HR', 'ROLE_FINANCE')
       OR name LIKE 'ROLE_DEALER_%'
);

DELETE FROM role_permissions WHERE role_id IN (
    SELECT id FROM roles
    WHERE name IN ('ROLE_FACTORY_MANAGER', 'ROLE_SALES_MANAGER', 'ROLE_DEALER_OPERATOR', 'ROLE_INVENTORY', 'ROLE_HR', 'ROLE_FINANCE')
       OR name LIKE 'ROLE_DEALER_%'
);

DELETE FROM roles
WHERE name IN ('ROLE_FACTORY_MANAGER', 'ROLE_SALES_MANAGER', 'ROLE_DEALER_OPERATOR', 'ROLE_INVENTORY', 'ROLE_HR', 'ROLE_FINANCE')
   OR name LIKE 'ROLE_DEALER_%';

-- Ensure portal permissions exist
INSERT INTO permissions(code, description)
VALUES
    ('portal:accounting', 'Access to the accounting and finance portal'),
    ('portal:factory', 'Access to the factory control portal'),
    ('portal:sales', 'Access to sales and order management'),
    ('portal:dealer', 'Access to the dealer workspace')
ON CONFLICT (code) DO NOTHING;

-- Seed consolidated roles
INSERT INTO roles(name, description)
VALUES
    ('ROLE_ACCOUNTING', 'Accounting, finance, HR, and inventory operator'),
    ('ROLE_FACTORY', 'Factory, production, and dispatch operator'),
    ('ROLE_SALES', 'Sales operations and dealer management'),
    ('ROLE_DEALER', 'Dealer workspace user')
ON CONFLICT (name) DO NOTHING;

-- Assign portal permissions to functional roles
WITH accounting_role AS (
    SELECT id FROM roles WHERE name = 'ROLE_ACCOUNTING'
),
accounting_perm AS (
    SELECT id FROM permissions WHERE code = 'portal:accounting'
)
INSERT INTO role_permissions(role_id, permission_id)
SELECT accounting_role.id, accounting_perm.id
FROM accounting_role, accounting_perm
ON CONFLICT DO NOTHING;

WITH factory_role AS (
    SELECT id FROM roles WHERE name = 'ROLE_FACTORY'
),
factory_perm AS (
    SELECT id FROM permissions WHERE code = 'portal:factory'
)
INSERT INTO role_permissions(role_id, permission_id)
SELECT factory_role.id, factory_perm.id
FROM factory_role, factory_perm
ON CONFLICT DO NOTHING;

WITH sales_role AS (
    SELECT id FROM roles WHERE name = 'ROLE_SALES'
),
sales_perm AS (
    SELECT id FROM permissions WHERE code = 'portal:sales'
)
INSERT INTO role_permissions(role_id, permission_id)
SELECT sales_role.id, sales_perm.id
FROM sales_role, sales_perm
ON CONFLICT DO NOTHING;

WITH dealer_role AS (
    SELECT id FROM roles WHERE name = 'ROLE_DEALER'
),
dealer_perm AS (
    SELECT id FROM permissions WHERE code = 'portal:dealer'
)
INSERT INTO role_permissions(role_id, permission_id)
SELECT dealer_role.id, dealer_perm.id
FROM dealer_role, dealer_perm
ON CONFLICT DO NOTHING;

-- Grant admins access to every portal
WITH admin_role AS (
    SELECT id FROM roles WHERE name = 'ROLE_ADMIN'
),
portal_perms AS (
    SELECT id FROM permissions WHERE code IN ('portal:accounting', 'portal:factory', 'portal:sales', 'portal:dealer')
)
INSERT INTO role_permissions(role_id, permission_id)
SELECT admin_role.id, portal_perms.id
FROM admin_role, portal_perms
ON CONFLICT DO NOTHING;
