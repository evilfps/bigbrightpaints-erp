-- Params:
--   :raw_material_id (numeric)

SELECT id, sku, name, unit_type, current_stock
FROM raw_materials
WHERE id = :raw_material_id;
