ALTER TABLE public.packaging_slips
    ADD COLUMN IF NOT EXISTS transporter_name character varying(255),
    ADD COLUMN IF NOT EXISTS driver_name character varying(255),
    ADD COLUMN IF NOT EXISTS vehicle_number character varying(120),
    ADD COLUMN IF NOT EXISTS challan_reference character varying(255);
