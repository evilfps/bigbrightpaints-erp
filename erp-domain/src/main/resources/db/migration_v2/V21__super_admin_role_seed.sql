-- Ensure ROLE_SUPER_ADMIN exists in platform catalog with baseline control-plane permissions.

INSERT INTO roles(name, description)
VALUES ('ROLE_SUPER_ADMIN', 'Platform super administrator')
ON CONFLICT (name) DO UPDATE
SET description = EXCLUDED.description;

INSERT INTO permissions(code, description)
VALUES
    ('portal:accounting', 'Access to the accounting and finance portal'),
    ('portal:factory', 'Access to the factory control portal'),
    ('portal:sales', 'Access to sales and order management'),
    ('portal:dealer', 'Access to the dealer workspace'),
    ('dispatch.confirm', 'Permission to confirm dispatch completion'),
    ('factory.dispatch', 'Permission to dispatch factory goods'),
    ('payroll.run', 'Permission to run payroll')
ON CONFLICT (code) DO NOTHING;

WITH super_admin_role AS (
    SELECT id FROM roles WHERE name = 'ROLE_SUPER_ADMIN'
),
super_admin_perms AS (
    SELECT id FROM permissions
    WHERE code IN (
        'portal:accounting',
        'portal:factory',
        'portal:sales',
        'portal:dealer',
        'dispatch.confirm',
        'factory.dispatch',
        'payroll.run'
    )
)
INSERT INTO role_permissions(role_id, permission_id)
SELECT super_admin_role.id, super_admin_perms.id
FROM super_admin_role, super_admin_perms
ON CONFLICT DO NOTHING;
