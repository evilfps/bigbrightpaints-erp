## unify-error-handling-and-validation handoff notes

- Primary validation gate (`mvn test -Pgate-fast -Djacoco.skip=true`) passes after refactor.
- Full suite (`mvn test -Djacoco.skip=true -T4`) still reports existing broader-suite issues:
  - `AdminApprovalRbacIT` (2 assertion mismatches)
  - `AuthTenantAuthorityIT#tenant_admin_cannot_mutate_shared_role_permissions` (audit assertion timeout)
  - 7 `CR_*Prod*` context startup failures due strict JWT placeholder validation under `test,prod` profile.
- Commit `ff457f1f26988dbb2384b628440421616fc6de1e` contains feature implementation.
