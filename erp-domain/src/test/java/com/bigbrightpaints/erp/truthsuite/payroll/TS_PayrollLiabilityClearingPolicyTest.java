package com.bigbrightpaints.erp.truthsuite.payroll;

import com.bigbrightpaints.erp.truthsuite.support.TruthSuiteFileAssert;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("critical")
@Tag("reconciliation")
class TS_PayrollLiabilityClearingPolicyTest {

    private static final String PAYROLL_SERVICE =
            "src/main/java/com/bigbrightpaints/erp/modules/hr/service/PayrollService.java";
    private static final String ACCOUNTING_SERVICE =
            "src/main/java/com/bigbrightpaints/erp/modules/accounting/service/AccountingService.java";

    @Test
    void payrollPostingUsesCanonicalFacadeAndSalaryPayableAccount() {
        TruthSuiteFileAssert.assertContains(
                PAYROLL_SERVICE,
                "PayrollRun run = companyEntityLookup.lockPayrollRun(company, payrollRunId);",
                "Account salaryPayableAccount = findAccountByCode(company, \"SALARY-PAYABLE\");",
                "JournalEntryDto journal = accountingFacade.postPayrollRun(runNumber, run.getId(), postingDate, memo, lines);",
                "run.setJournalEntryId(journal.id());",
                "run.setStatus(PayrollRun.PayrollStatus.POSTED);");
    }

    @Test
    void markAsPaidRequiresPaymentJournalLink() {
        TruthSuiteFileAssert.assertContains(
                PAYROLL_SERVICE,
                "if (run.getPaymentJournalEntryId() == null) {",
                "\"Payroll payment journal is required before marking payroll as PAID\"",
                "\"canonicalPath\", \"/api/v1/accounting/payroll/payments\"");
    }

    @Test
    void payrollPaymentAmountMustMatchPostedSalaryPayableExactly() {
        TruthSuiteFileAssert.assertContains(
                ACCOUNTING_SERVICE,
                "\"Salary payable account (SALARY-PAYABLE) is required to record payroll payments\"",
                "if (payableAmount.subtract(amount).abs().compareTo(ALLOCATION_TOLERANCE) > 0) {",
                "\"Payroll payment amount does not match salary payable from the posted payroll journal\"");
    }
}
