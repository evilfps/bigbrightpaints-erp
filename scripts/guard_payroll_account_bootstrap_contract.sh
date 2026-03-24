#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PAYROLL_POSTING_SERVICE="$ROOT_DIR/erp-domain/src/main/java/com/bigbrightpaints/erp/modules/hr/service/PayrollPostingService.java"
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

require_absent_literal() {
  local file="$1"
  local needle="$2"
  local label="$3"
  if grep -Fq "$needle" "$file"; then
    fail "unexpected $label ($needle) in $file"
  fi
}

for file in "$PAYROLL_POSTING_SERVICE" "$PAYROLL_TEST"; do
  [[ -f "$file" ]] || fail "missing required file: $file"
done

for code in SALARY-EXP WAGE-EXP SALARY-PAYABLE EMP-ADV; do
  require_literal "$PAYROLL_POSTING_SERVICE" "\"$code\"" "payroll required account contract"
done

require_literal "$PAYROLL_POSTING_SERVICE" "expectedAccountType" "deterministic missing-account type detail"
require_literal "$PAYROLL_POSTING_SERVICE" "requiredPayrollAccounts" "required-account inventory detail"
require_literal "$PAYROLL_POSTING_SERVICE" "migrationSet" "v2 migration-set detail"
require_literal "$PAYROLL_POSTING_SERVICE" "\"v2\"" "v2 migration marker"
require_literal "$PAYROLL_POSTING_SERVICE" "manualProvisioningRequired" "manual provisioning detail for non-legacy bootstrap accounts"
require_literal "$PAYROLL_POSTING_SERVICE" "/api/v1/accounting/accounts" "chart of accounts canonical path"
require_absent_literal "$PAYROLL_POSTING_SERVICE" "V79__payroll_gl_accounts.sql" "v1 migration reference"

require_literal "$PAYROLL_TEST" "payrollPosting_missingSalaryExpenseAccount_failsWithDeterministicProvisioningGuidance" "missing SALARY-EXP regression coverage"
require_literal "$PAYROLL_TEST" "payrollPosting_missingEmployeeAdvanceAccount_requiresManualProvisioningGuidance" "missing EMP-ADV regression coverage"
require_literal "$PAYROLL_TEST" "migrationSet\", \"v2\"" "v2 assertion coverage"
require_literal "$PAYROLL_TEST" "doesNotContainKey(\"bootstrapMigration\")" "bootstrap migration exclusion assertion"

echo "[guard_payroll_account_bootstrap_contract] OK"
