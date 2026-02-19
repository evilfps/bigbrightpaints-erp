package com.bigbrightpaints.erp.truthsuite.payroll;

import com.bigbrightpaints.erp.truthsuite.support.TruthSuiteFileAssert;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("critical")
@Tag("reconciliation")
class TS_PayrollLiabilityClearingPolicyTest {

    private static final String PAYROLL_SERVICE =
            "src/main/java/com/bigbrightpaints/erp/modules/hr/service/PayrollService.java";
    private static final String ACCOUNTING_CONTROLLER =
            "src/main/java/com/bigbrightpaints/erp/modules/accounting/controller/AccountingController.java";
    private static final String ACCOUNTING_FACADE =
            "src/main/java/com/bigbrightpaints/erp/modules/accounting/service/AccountingFacade.java";
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
    void payrollPostingGuardTracksStatusAndJournalLinkageForReplaySafety() {
        TruthSuiteFileAssert.assertContains(
                PAYROLL_SERVICE,
                "boolean hasPostingJournalLink = hasPostingJournalLink(run);",
                "boolean statusPosted = run.getStatus() == PayrollRun.PayrollStatus.POSTED;",
                "if (statusPosted && !hasPostingJournalLink) {",
                "if (!statusPosted && !hasPostingJournalLink && run.getStatus() != PayrollRun.PayrollStatus.APPROVED) {",
                "if (hasPostingJournalLink && run.getJournalEntryId() != null && !run.getJournalEntryId().equals(journal.id())) {",
                "\"Payroll run already linked to a different posting journal\"");
    }

    @Test
    void markAsPaidRequiresPaymentJournalLink() {
        TruthSuiteFileAssert.assertContains(
                PAYROLL_SERVICE,
                "private static final String PAYROLL_PAYMENTS_CANONICAL_PATH = \"/api/v1/accounting/payroll/payments\";",
                "if (run.getPaymentJournalEntryId() == null) {",
                "\"Payroll payment journal is required before marking payroll as PAID\"",
                "\"canonicalPath\", PAYROLL_PAYMENTS_CANONICAL_PATH");
    }

    @Test
    void markAsPaidUsesCanonicalPaymentJournalReference() {
        TruthSuiteFileAssert.assertContains(
                PAYROLL_SERVICE,
                "var paymentJournal = companyEntityLookup.requireJournalEntry(company, run.getPaymentJournalEntryId());",
                "String canonicalPaymentReference = paymentJournal.getReferenceNumber();",
                "line.setPaymentReference(canonicalPaymentReference);");
    }

    @Test
    void payrollPaymentAmountMustMatchPostedSalaryPayableExactly() {
        TruthSuiteFileAssert.assertContains(
                ACCOUNTING_SERVICE,
                "\"Salary payable account (SALARY-PAYABLE) is required to record payroll payments\"",
                "if (payableAmount.subtract(amount).abs().compareTo(ALLOCATION_TOLERANCE) > 0) {",
                "\"Payroll payment amount does not match salary payable from the posted payroll journal\"");
    }

    @Test
    void payrollPaymentUsesCanonicalAccountingBoundary() {
        TruthSuiteFileAssert.assertContains(
                ACCOUNTING_CONTROLLER,
                "@PostMapping(\"/payroll/payments\")",
                "accountingFacade.recordPayrollPayment(request)");
        TruthSuiteFileAssert.assertContains(
                ACCOUNTING_FACADE,
                "public JournalEntryDto recordPayrollPayment(PayrollPaymentRequest request) {",
                "return accountingService.recordPayrollPayment(request);");
    }
}
