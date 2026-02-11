# Flyway v2 Migration Chain

- This directory hosts the rebuilt migration chain executed only under the `flyway-v2` profile.
- Existing legacy chain in `db/migration` remains untouched for safe parallel validation.
- v2 profile uses:
  - Flyway location: `classpath:db/migration_v2`
  - History table: `flyway_schema_history_v2`
  - Dev DB default: `erp_domain_v2`
- Reference/sample data stays in application seeders (see `docs/db/SEED_REFERENCE_STRATEGY.md`).
