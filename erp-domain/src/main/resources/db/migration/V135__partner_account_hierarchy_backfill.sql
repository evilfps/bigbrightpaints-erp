UPDATE accounts child
SET parent_id = control.id,
    hierarchy_level = COALESCE(control.hierarchy_level, 1) + 1
FROM accounts control
WHERE child.company_id = control.company_id
  AND child.parent_id IS NULL
  AND child.id <> control.id
  AND child.type = 'ASSET'
  AND child.code ILIKE 'AR-%'
  AND control.type = 'ASSET'
  AND control.code ILIKE 'AR';

UPDATE accounts child
SET parent_id = control.id,
    hierarchy_level = COALESCE(control.hierarchy_level, 1) + 1
FROM accounts control
WHERE child.company_id = control.company_id
  AND child.parent_id IS NULL
  AND child.id <> control.id
  AND child.type = 'LIABILITY'
  AND child.code ILIKE 'AP-%'
  AND control.type = 'LIABILITY'
  AND control.code ILIKE 'AP';
