CREATE TABLE IF NOT EXISTS production_plans (
    id BIGSERIAL PRIMARY KEY,
    public_id UUID NOT NULL DEFAULT gen_random_uuid(),
    company_id BIGINT NOT NULL REFERENCES companies(id) ON DELETE CASCADE,
    plan_number VARCHAR(64) NOT NULL,
    product_name VARCHAR(255) NOT NULL,
    quantity NUMERIC(18,2) NOT NULL,
    planned_date DATE NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PLANNED',
    notes TEXT,
    UNIQUE(company_id, plan_number)
);

CREATE TABLE IF NOT EXISTS production_batches (
    id BIGSERIAL PRIMARY KEY,
    public_id UUID NOT NULL DEFAULT gen_random_uuid(),
    company_id BIGINT NOT NULL REFERENCES companies(id) ON DELETE CASCADE,
    plan_id BIGINT REFERENCES production_plans(id) ON DELETE SET NULL,
    batch_number VARCHAR(64) NOT NULL,
    quantity_produced NUMERIC(18,2) NOT NULL,
    produced_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    logged_by VARCHAR(255),
    notes TEXT,
    UNIQUE(company_id, batch_number)
);

CREATE TABLE IF NOT EXISTS factory_tasks (
    id BIGSERIAL PRIMARY KEY,
    public_id UUID NOT NULL DEFAULT gen_random_uuid(),
    company_id BIGINT NOT NULL REFERENCES companies(id) ON DELETE CASCADE,
    title VARCHAR(255) NOT NULL,
    description TEXT,
    assignee VARCHAR(255),
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    due_date DATE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
