-- Seed action-level security permissions and role assignments

INSERT INTO permissions(code, description)
VALUES
    ('dispatch.confirm', 'Confirm dispatch operations'),
    ('factory.dispatch', 'Allow factory dispatch execution'),
    ('payroll.run', 'Run payroll workflows')
ON CONFLICT (code) DO NOTHING;

-- Factory role: dispatch actions
WITH factory_role AS (
    SELECT id FROM roles WHERE name = 'ROLE_FACTORY'
),
dispatch_confirm AS (
    SELECT id FROM permissions WHERE code = 'dispatch.confirm'
)
INSERT INTO role_permissions(role_id, permission_id)
SELECT factory_role.id, dispatch_confirm.id
FROM factory_role, dispatch_confirm
ON CONFLICT DO NOTHING;

WITH factory_role AS (
    SELECT id FROM roles WHERE name = 'ROLE_FACTORY'
),
factory_dispatch AS (
    SELECT id FROM permissions WHERE code = 'factory.dispatch'
)
INSERT INTO role_permissions(role_id, permission_id)
SELECT factory_role.id, factory_dispatch.id
FROM factory_role, factory_dispatch
ON CONFLICT DO NOTHING;

-- Accounting role: payroll run
WITH accounting_role AS (
    SELECT id FROM roles WHERE name = 'ROLE_ACCOUNTING'
),
payroll_perm AS (
    SELECT id FROM permissions WHERE code = 'payroll.run'
)
INSERT INTO role_permissions(role_id, permission_id)
SELECT accounting_role.id, payroll_perm.id
FROM accounting_role, payroll_perm
ON CONFLICT DO NOTHING;

-- Admin role: full access
WITH admin_role AS (
    SELECT id FROM roles WHERE name = 'ROLE_ADMIN'
),
admin_perms AS (
    SELECT id FROM permissions WHERE code IN ('dispatch.confirm', 'factory.dispatch', 'payroll.run')
)
INSERT INTO role_permissions(role_id, permission_id)
SELECT admin_role.id, admin_perms.id
FROM admin_role, admin_perms
ON CONFLICT DO NOTHING;
