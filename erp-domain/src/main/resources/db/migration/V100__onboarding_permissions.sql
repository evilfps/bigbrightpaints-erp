INSERT INTO permissions (code, description)
VALUES ('onboarding.manage', 'Onboarding management')
ON CONFLICT (code) DO NOTHING;

WITH role_row AS (
    SELECT id FROM roles WHERE name = 'ROLE_ADMIN'
), perm_row AS (
    SELECT id FROM permissions WHERE code = 'onboarding.manage'
)
INSERT INTO role_permissions (role_id, permission_id)
SELECT role_row.id, perm_row.id
FROM role_row, perm_row
ON CONFLICT DO NOTHING;
