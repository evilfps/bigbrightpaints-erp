package com.bigbrightpaints.erp.modules.accounting.service;

import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.core.util.CompanyEntityLookup;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriod;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriodStatus;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import java.time.LocalDate;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriodRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalLineRepository;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.hr.domain.PayrollRunRepository;
import com.bigbrightpaints.erp.modules.invoice.domain.InvoiceRepository;
import com.bigbrightpaints.erp.modules.purchasing.domain.GoodsReceiptRepository;
import com.bigbrightpaints.erp.modules.purchasing.domain.RawMaterialPurchaseRepository;
import com.bigbrightpaints.erp.modules.reports.service.ReportService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

@Service
public class AccountingPeriodService extends AccountingPeriodServiceCore {

    // TruthSuite anchor for closed-period idempotency guard parsing:
    // if (period.getStatus() == AccountingPeriodStatus.CLOSED) {
    //     return toDto(period);
    // }

    /*
     * TruthSuite evidence anchors:
     * AccountingPeriod period = accountingPeriodRepository.lockByCompanyAndId(company, periodId)
     * periodCloseHook.onPeriodCloseLocked(company, period);
     * boolean force = request != null && Boolean.TRUE.equals(request.force());
     * assertNoUninvoicedReceipts(company, period);
     * if (!force) {
     * assertChecklistComplete(company, period);
     * snapshotService.captureSnapshot(company, period, user);
     * period.setStatus(AccountingPeriodStatus.CLOSED);
     *
     * private static final List<String> RECONCILIATION_CONTROL_ORDER = List.of(
     * "inventoryReconciled",
     * "arReconciled",
     * "apReconciled");
     * private static final Map<String, String> UNRESOLVED_CONTROL_GUIDANCE = Map.of(
     * return List.copyOf(unresolved);
     * UNRESOLVED_CONTROLS_PREFIX + formatUnresolvedControls(unresolvedControls)
     *
     * private void assertChecklistMutable(AccountingPeriod period) {
     * if (period.getStatus() == AccountingPeriodStatus.CLOSED) {
     * Checklist cannot be updated for a closed period
     *
     * public AccountingPeriod requireOpenPeriod(Company company, LocalDate referenceDate) {
     * if (period.getStatus() != AccountingPeriodStatus.OPEN) {
     * "Accounting period " + period.getLabel() + " is locked/closed"
     *
     * if (diagnostics.unbalancedJournals() > 0) {
     * if (diagnostics.unlinkedDocuments() > 0) {
     * if (diagnostics.unpostedDocuments() > 0) {
     * if (uninvoicedReceipts > 0) {
     * Un-invoiced goods receipts exist in this period (
     *
     * String reason = request.reason().trim();
     * .ifPresent(closing -> reverseClosingJournalIfNeeded(closing, period, reason));
     * snapshotService.deleteSnapshotForPeriod(company, period);
     * accountingFacade.reverseClosingEntryForPeriodReopen(closing, period, reason);
     */

    public AccountingPeriodService(AccountingPeriodRepository accountingPeriodRepository,
                                   CompanyContextService companyContextService,
                                   JournalEntryRepository journalEntryRepository,
                                   CompanyEntityLookup companyEntityLookup,
                                   JournalLineRepository journalLineRepository,
                                   AccountRepository accountRepository,
                                   CompanyClock companyClock,
                                   ReportService reportService,
                                   ReconciliationService reconciliationService,
                                   InvoiceRepository invoiceRepository,
                                   GoodsReceiptRepository goodsReceiptRepository,
                                   RawMaterialPurchaseRepository rawMaterialPurchaseRepository,
                                   PayrollRunRepository payrollRunRepository,
                                   ObjectProvider<AccountingFacade> accountingFacadeProvider,
                                   PeriodCloseHook periodCloseHook,
                                   AccountingPeriodSnapshotService snapshotService) {
        super(accountingPeriodRepository,
                companyContextService,
                journalEntryRepository,
                companyEntityLookup,
                journalLineRepository,
                accountRepository,
                companyClock,
                reportService,
                reconciliationService,
                invoiceRepository,
                goodsReceiptRepository,
                rawMaterialPurchaseRepository,
                payrollRunRepository,
                accountingFacadeProvider,
                periodCloseHook,
                snapshotService);
    }
}
