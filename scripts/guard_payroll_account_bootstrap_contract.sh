#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PAYROLL_SERVICE="$ROOT_DIR/erp-domain/src/main/java/com/bigbrightpaints/erp/modules/hr/service/PayrollService.java"
PAYROLL_BOOTSTRAP_MIGRATION="$ROOT_DIR/erp-domain/src/main/resources/db/migration/V79__payroll_gl_accounts.sql"
PAYROLL_TEST="$ROOT_DIR/erp-domain/src/test/java/com/bigbrightpaints/erp/codered/CR_PayrollIdempotencyConcurrencyTest.java"

fail() {
  echo "[guard_payroll_account_bootstrap_contract] FAIL: $1" >&2
  exit 1
}

require_literal() {
  local file="$1"
  local needle="$2"
  local label="$3"
  if ! grep -Fq "$needle" "$file"; then
    fail "missing $label ($needle) in $file"
  fi
}

for file in "$PAYROLL_SERVICE" "$PAYROLL_BOOTSTRAP_MIGRATION" "$PAYROLL_TEST"; do
  [[ -f "$file" ]] || fail "missing required file: $file"
done

for code in SALARY-EXP WAGE-EXP SALARY-PAYABLE EMP-ADV; do
  require_literal "$PAYROLL_SERVICE" "\"$code\"" "payroll required account contract"
done

require_literal "$PAYROLL_SERVICE" "expectedAccountType" "deterministic missing-account type detail"
require_literal "$PAYROLL_SERVICE" "requiredPayrollAccounts" "required-account inventory detail"
require_literal "$PAYROLL_SERVICE" "bootstrapMigration" "bootstrap migration detail"
require_literal "$PAYROLL_SERVICE" "manualProvisioningRequired" "manual provisioning detail for non-legacy bootstrap accounts"
require_literal "$PAYROLL_SERVICE" "/api/v1/accounting/accounts" "chart of accounts canonical path"

for legacy_tuple in \
  "'SALARY-EXP', 'Salary Expense', 'EXPENSE'" \
  "'WAGE-EXP', 'Wage Expense', 'EXPENSE'" \
  "'SALARY-PAYABLE', 'Salary Payable', 'LIABILITY'"; do
  require_literal "$PAYROLL_BOOTSTRAP_MIGRATION" "$legacy_tuple" "legacy payroll bootstrap tuple"
done

require_literal "$PAYROLL_TEST" "payrollPosting_missingSalaryExpenseAccount_failsWithDeterministicProvisioningGuidance" "missing SALARY-EXP regression coverage"
require_literal "$PAYROLL_TEST" "payrollPosting_missingEmployeeAdvanceAccount_requiresManualProvisioningGuidance" "missing EMP-ADV regression coverage"

echo "[guard_payroll_account_bootstrap_contract] OK"
