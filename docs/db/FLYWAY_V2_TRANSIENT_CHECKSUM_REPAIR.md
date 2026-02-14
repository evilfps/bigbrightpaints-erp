# Flyway V2 Transient Checksum Repair

## Why this exists
- `db/migration_v2/V12__invoice_pending_exposure_status_norm_indexes.sql` had a transient variant during async-loop hardening.
- Final branch state converges back to the canonical `V12` content plus forward-fix `V13`.
- Environments that ever ran the transient `V12` variant can fail Flyway `validate` before `V13` is applied.

## Guard script
- Script: `scripts/guard_flyway_v2_transient_checksum.sh`
- Purpose: detect transient `V12` signature and post-`V13` non-canonical index shape.
- Exit codes:
  - `0`: safe/canonical state.
  - `2`: transient/non-canonical pre-`V13` signature detected; repair+migrate required.
  - `3`: `V13` present but normalized index shape is non-canonical; index rebuild required.
  - `1`: execution/config error.

## Detection workflow
1. Export database connectivity.
2. Run:
```bash
bash scripts/guard_flyway_v2_transient_checksum.sh <db_name>
```
3. If exit code is `2`, continue with repair workflow below.
4. If exit code is `3`, run post-`V13` index rebuild workflow below.

## Repair workflow (v2 chain)
1. Repair checksums in v2 history table:
```bash
mvn -B -ntp -f erp-domain/pom.xml org.flywaydb:flyway-maven-plugin:repair \
  -Dflyway.url=jdbc:postgresql://$PGHOST:$PGPORT/<db_name> \
  -Dflyway.user=$PGUSER \
  -Dflyway.password=$PGPASSWORD \
  -Dflyway.defaultSchema=${FLYWAY_GUARD_SCHEMA:-public} \
  -Dflyway.locations=filesystem:$(pwd)/erp-domain/src/main/resources/db/migration_v2 \
  -Dflyway.table=flyway_schema_history_v2
```
2. Re-run migrate:
```bash
mvn -B -ntp -f erp-domain/pom.xml org.flywaydb:flyway-maven-plugin:migrate \
  -Dflyway.url=jdbc:postgresql://$PGHOST:$PGPORT/<db_name> \
  -Dflyway.user=$PGUSER \
  -Dflyway.password=$PGPASSWORD \
  -Dflyway.defaultSchema=${FLYWAY_GUARD_SCHEMA:-public} \
  -Dflyway.locations=filesystem:$(pwd)/erp-domain/src/main/resources/db/migration_v2 \
  -Dflyway.table=flyway_schema_history_v2
```
3. Re-run guard script and confirm exit code `0`.

## Post-V13 index rebuild workflow (exit code 3)
Run during a maintenance window:
```sql
DROP INDEX CONCURRENTLY IF EXISTS public.idx_invoices_company_order_status_norm;
CREATE INDEX CONCURRENTLY idx_invoices_company_order_status_norm
    ON public.invoices USING btree (company_id, sales_order_id, upper(trim(status)))
    WHERE (sales_order_id IS NOT NULL AND status IS NOT NULL);
DROP INDEX CONCURRENTLY IF EXISTS public.idx_invoices_company_order_status_null;
```
Re-run guard script and confirm exit code `0`.

## Notes
- This is an operational recovery for transient checksum drift only.
- Do not rewrite applied versioned migrations. Use forward migrations for schema correction.
- Current flyway-v2 full-chain test run is blocked at `V7__enterprise_audit_ml_events.sql` FK setup; that issue is separate from this checksum guard.
