UPDATE companies
SET enabled_modules = '["MANUFACTURING","PURCHASING","PORTAL","REPORTS_ADVANCED"]'::jsonb
WHERE enabled_modules IS NULL;

UPDATE companies
SET enabled_modules = COALESCE((
    SELECT jsonb_agg(module_name ORDER BY ordinality)
    FROM jsonb_array_elements_text(enabled_modules) WITH ORDINALITY AS modules(module_name, ordinality)
    WHERE module_name <> 'HR_PAYROLL'
), '[]'::jsonb)
WHERE enabled_modules IS NOT NULL
  AND enabled_modules ? 'HR_PAYROLL';

ALTER TABLE companies
    ALTER COLUMN enabled_modules SET DEFAULT '["MANUFACTURING","PURCHASING","PORTAL","REPORTS_ADVANCED"]'::jsonb;
