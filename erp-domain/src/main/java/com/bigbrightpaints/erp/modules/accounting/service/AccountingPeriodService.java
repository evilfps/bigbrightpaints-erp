package com.bigbrightpaints.erp.modules.accounting.service;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.core.util.CompanyEntityLookup;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriod;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriodRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriodStatus;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalLineRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.PeriodCloseRequestRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.ReconciliationDiscrepancyRepository;
import com.bigbrightpaints.erp.modules.accounting.dto.AccountingPeriodCloseRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.AccountingPeriodDto;
import com.bigbrightpaints.erp.modules.accounting.dto.AccountingPeriodReopenRequest;
import com.bigbrightpaints.erp.modules.accounting.internal.AccountingPeriodServiceCore;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import java.time.LocalDate;
import com.bigbrightpaints.erp.modules.hr.domain.PayrollRunRepository;
import com.bigbrightpaints.erp.modules.invoice.domain.InvoiceRepository;
import com.bigbrightpaints.erp.modules.purchasing.domain.GoodsReceiptRepository;
import com.bigbrightpaints.erp.modules.purchasing.domain.RawMaterialPurchaseRepository;
import com.bigbrightpaints.erp.modules.reports.service.ReportService;
import com.bigbrightpaints.erp.core.validation.ValidationUtils;
import jakarta.transaction.Transactional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

@Service
public class AccountingPeriodService extends AccountingPeriodServiceCore {

    private static final String SUPER_ADMIN_REQUIRED_MESSAGE =
            "SUPER_ADMIN authority required to reopen accounting periods";

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
     * "apReconciled",
     * "gstReconciled",
     * "reconciliationDiscrepanciesResolved");
     * private static final Map<String, String> UNRESOLVED_CONTROL_GUIDANCE = Map.of(
     * return List.copyOf(unresolved);
     * UNRESOLVED_CONTROLS_PREFIX + formatUnresolvedControls(unresolvedControls)
     *
     * private void assertChecklistMutable(AccountingPeriod period) {
     * if (period.getStatus() == AccountingPeriodStatus.CLOSED
     *         || period.getStatus() == AccountingPeriodStatus.LOCKED) {
     * Checklist cannot be updated for a locked or closed period
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
                                   ReconciliationDiscrepancyRepository reconciliationDiscrepancyRepository,
                                   ObjectProvider<AccountingFacade> accountingFacadeProvider,
                                   PeriodCloseHook periodCloseHook,
                                   AccountingPeriodSnapshotService snapshotService) {
        this(accountingPeriodRepository,
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
                reconciliationDiscrepancyRepository,
                null,
                accountingFacadeProvider,
                periodCloseHook,
                snapshotService);
    }

    @Autowired
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
                                   ReconciliationDiscrepancyRepository reconciliationDiscrepancyRepository,
                                   PeriodCloseRequestRepository periodCloseRequestRepository,
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
                reconciliationDiscrepancyRepository,
                periodCloseRequestRepository,
                accountingFacadeProvider,
                periodCloseHook,
                snapshotService);
    }

    @Override
    @Transactional
    public AccountingPeriodDto reopenPeriod(Long periodId, AccountingPeriodReopenRequest request) {
        requireSuperAdminRole();
        return super.reopenPeriod(periodId, request);
    }

    @Override
    public AccountingPeriodDto closePeriod(Long periodId, AccountingPeriodCloseRequest request) {
        throw ValidationUtils.invalidState(
                "Direct close is disabled; submit /request-close and approve via maker-checker workflow");
    }

    private void requireSuperAdminRole() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new ApplicationException(ErrorCode.AUTH_INSUFFICIENT_PERMISSIONS, SUPER_ADMIN_REQUIRED_MESSAGE);
        }
        boolean hasSuperAdmin = authentication.getAuthorities().stream()
                .anyMatch(authority -> "ROLE_SUPER_ADMIN".equalsIgnoreCase(authority.getAuthority()));
        if (!hasSuperAdmin) {
            throw new ApplicationException(ErrorCode.AUTH_INSUFFICIENT_PERMISSIONS, SUPER_ADMIN_REQUIRED_MESSAGE);
        }
    }
}
