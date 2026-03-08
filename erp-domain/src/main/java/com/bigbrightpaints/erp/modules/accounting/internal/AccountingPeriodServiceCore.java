package com.bigbrightpaints.erp.modules.accounting.service;

import com.bigbrightpaints.erp.core.util.CompanyTime;
import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.core.security.SecurityActorResolver;
import com.bigbrightpaints.erp.core.validation.ValidationUtils;
import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.core.util.CompanyEntityLookup;
import com.bigbrightpaints.erp.modules.accounting.domain.*;
import com.bigbrightpaints.erp.modules.accounting.dto.AccountingPeriodCloseRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.AccountingPeriodLockRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.AccountingPeriodDto;
import com.bigbrightpaints.erp.modules.accounting.dto.AccountingPeriodReopenRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.AccountingPeriodUpdateRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.AccountingPeriodUpsertRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.GstReconciliationDto;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalCreationRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryDto;
import com.bigbrightpaints.erp.modules.accounting.dto.MonthEndChecklistDto;
import com.bigbrightpaints.erp.modules.accounting.dto.MonthEndChecklistItemDto;
import com.bigbrightpaints.erp.modules.accounting.dto.MonthEndChecklistUpdateRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.PeriodCloseRequestActionRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.PeriodCloseRequestDto;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingComplianceAuditService;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingFacade;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingPeriodSnapshotService;
import com.bigbrightpaints.erp.modules.accounting.service.PeriodCloseHook;
import com.bigbrightpaints.erp.modules.accounting.service.ReconciliationService;
import com.bigbrightpaints.erp.modules.accounting.domain.ReconciliationDiscrepancyRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.ReconciliationDiscrepancyStatus;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.hr.domain.PayrollRun;
import com.bigbrightpaints.erp.modules.hr.domain.PayrollRunRepository;
import com.bigbrightpaints.erp.modules.invoice.domain.InvoiceRepository;
import com.bigbrightpaints.erp.modules.purchasing.domain.GoodsReceiptStatus;
import com.bigbrightpaints.erp.modules.purchasing.domain.GoodsReceiptRepository;
import com.bigbrightpaints.erp.modules.purchasing.domain.RawMaterialPurchaseRepository;
import com.bigbrightpaints.erp.modules.reports.dto.ReconciliationSummaryDto;
import com.bigbrightpaints.erp.modules.reports.dto.TrialBalanceDto;
import com.bigbrightpaints.erp.modules.reports.service.ReportService;
import jakarta.transaction.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public class AccountingPeriodServiceCore {

    private static final BigDecimal RECONCILIATION_TOLERANCE = new BigDecimal("0.01");
    private static final String UNRESOLVED_CONTROLS_PREFIX = "Checklist controls unresolved for this period: ";
    private static final List<String> RECONCILIATION_CONTROL_ORDER = List.of(
            "inventoryReconciled",
            "arReconciled",
            "apReconciled",
            "gstReconciled",
            "reconciliationDiscrepanciesResolved");
    private static final Map<String, String> UNRESOLVED_CONTROL_GUIDANCE = Map.of(
            "inventoryReconciled",
            "inventory reconciliation result unavailable; run inventory reconciliation before close",
            "arReconciled",
            "AR subledger reconciliation result unavailable; reconcile dealer ledger before close",
            "apReconciled",
            "AP subledger reconciliation result unavailable; reconcile supplier ledger before close",
            "gstReconciled",
            "GST reconciliation result unavailable; run GST reconciliation before close",
            "reconciliationDiscrepanciesResolved",
            "open reconciliation discrepancies exist; resolve discrepancies before close");

    private final AccountingPeriodRepository accountingPeriodRepository;
    private final CompanyContextService companyContextService;
    private final JournalEntryRepository journalEntryRepository;
    private final CompanyEntityLookup companyEntityLookup;
    private final JournalLineRepository journalLineRepository;
    private final AccountRepository accountRepository;
    private final CompanyClock companyClock;
    private final ReportService reportService;
    private final ReconciliationService reconciliationService;
    private final InvoiceRepository invoiceRepository;
    private final GoodsReceiptRepository goodsReceiptRepository;
    private final RawMaterialPurchaseRepository rawMaterialPurchaseRepository;
    private final PayrollRunRepository payrollRunRepository;
    private final ReconciliationDiscrepancyRepository reconciliationDiscrepancyRepository;
    private final PeriodCloseRequestRepository periodCloseRequestRepository;
    private final ObjectProvider<AccountingFacade> accountingFacadeProvider;
    private final PeriodCloseHook periodCloseHook;
    private final AccountingPeriodSnapshotService snapshotService;

    @Autowired(required = false)
    private AccountingComplianceAuditService accountingComplianceAuditService;

    public AccountingPeriodServiceCore(AccountingPeriodRepository accountingPeriodRepository,
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
        this.accountingPeriodRepository = accountingPeriodRepository;
        this.companyContextService = companyContextService;
        this.journalEntryRepository = journalEntryRepository;
        this.companyEntityLookup = companyEntityLookup;
        this.journalLineRepository = journalLineRepository;
        this.accountRepository = accountRepository;
        this.companyClock = companyClock;
        this.reportService = reportService;
        this.reconciliationService = reconciliationService;
        this.invoiceRepository = invoiceRepository;
        this.goodsReceiptRepository = goodsReceiptRepository;
        this.rawMaterialPurchaseRepository = rawMaterialPurchaseRepository;
        this.payrollRunRepository = payrollRunRepository;
        this.reconciliationDiscrepancyRepository = reconciliationDiscrepancyRepository;
        this.periodCloseRequestRepository = periodCloseRequestRepository;
        this.accountingFacadeProvider = accountingFacadeProvider;
        this.periodCloseHook = periodCloseHook;
        this.snapshotService = snapshotService;
    }

    public List<AccountingPeriodDto> listPeriods() {
        Company company = companyContextService.requireCurrentCompany();
        ensureSurroundingPeriods(company);
        return accountingPeriodRepository.findByCompanyOrderByYearDescMonthDesc(company).stream()
                .map(this::toDto)
                .toList();
    }

    public AccountingPeriodDto getPeriod(Long periodId) {
        Company company = companyContextService.requireCurrentCompany();
        AccountingPeriod period = companyEntityLookup.requireAccountingPeriod(company, periodId);
        return toDto(period);
    }

    @Transactional
    public AccountingPeriodDto createOrUpdatePeriod(AccountingPeriodUpsertRequest request) {
        if (request == null) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT, "Accounting period request is required");
        }
        if (request.month() < 1 || request.month() > 12) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT, "Accounting period month must be between 1 and 12");
        }
        Company company = companyContextService.requireCurrentCompany();
        AccountingPeriod period = accountingPeriodRepository
                .lockByCompanyAndYearAndMonth(company, request.year(), request.month())
                .orElseGet(() -> {
                    AccountingPeriod created = new AccountingPeriod();
                    created.setCompany(company);
                    created.setYear(request.year());
                    created.setMonth(request.month());
                    LocalDate start = LocalDate.of(request.year(), request.month(), 1);
                    created.setStartDate(start);
                    created.setEndDate(start.plusMonths(1).minusDays(1));
                    created.setStatus(AccountingPeriodStatus.OPEN);
                    return created;
                });
        CostingMethod beforeCostingMethod = period.getCostingMethod();
        period.setCostingMethod(resolveCostingMethodOrDefault(request.costingMethod()));
        AccountingPeriod saved = accountingPeriodRepository.save(period);
        if (accountingComplianceAuditService != null
                && !Objects.equals(beforeCostingMethod, saved.getCostingMethod())) {
            accountingComplianceAuditService.recordCostingMethodChange(
                    company,
                    saved,
                    beforeCostingMethod,
                    saved.getCostingMethod());
        }
        return toDto(saved);
    }

    @Transactional
    public AccountingPeriodDto updatePeriod(Long periodId, AccountingPeriodUpdateRequest request) {
        if (request == null || request.costingMethod() == null) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT, "Costing method is required");
        }
        Company company = companyContextService.requireCurrentCompany();
        AccountingPeriod period = accountingPeriodRepository.lockByCompanyAndId(company, periodId)
                .orElseThrow(() -> new ApplicationException(ErrorCode.VALIDATION_INVALID_REFERENCE,
                        "Accounting period not found"));
        CostingMethod beforeCostingMethod = period.getCostingMethod();
        period.setCostingMethod(resolveCostingMethodOrDefault(request.costingMethod()));
        AccountingPeriod saved = accountingPeriodRepository.save(period);
        if (accountingComplianceAuditService != null
                && !Objects.equals(beforeCostingMethod, saved.getCostingMethod())) {
            accountingComplianceAuditService.recordCostingMethodChange(
                    company,
                    saved,
                    beforeCostingMethod,
                    saved.getCostingMethod());
        }
        return toDto(saved);
    }

    @Transactional
    public PeriodCloseRequestDto requestPeriodClose(Long periodId, PeriodCloseRequestActionRequest request) {
        Company company = companyContextService.requireCurrentCompany();
        AccountingPeriod period = accountingPeriodRepository.lockByCompanyAndId(company, periodId)
                .orElseThrow(() -> new ApplicationException(ErrorCode.VALIDATION_INVALID_REFERENCE,
                        "Accounting period not found"));
        if (period.getStatus() == AccountingPeriodStatus.CLOSED) {
            throw ValidationUtils.invalidState("Accounting period " + period.getLabel() + " is already closed");
        }
        if (periodCloseRequestRepository == null) {
            throw ValidationUtils.invalidState("Period close request workflow is not configured");
        }
        String requester = resolveCurrentUsername();
        String note = normalizeRequiredNote(request != null ? request.note() : null, "Close request note is required");
        boolean force = request != null && Boolean.TRUE.equals(request.force());

        PeriodCloseRequest pending = periodCloseRequestRepository
                .lockByCompanyAndAccountingPeriodAndStatus(company, period, PeriodCloseRequestStatus.PENDING)
                .orElse(null);
        if (pending != null) {
            if (!requester.equalsIgnoreCase(normalizeActor(pending.getRequestedBy(), "requestedBy"))) {
                throw ValidationUtils.invalidState(
                        "A pending period close request already exists for " + period.getLabel());
            }
            pending.setRequestNote(note);
            pending.setForceRequested(force);
            pending.setRequestedAt(CompanyTime.now(company));
            pending.setReviewedBy(null);
            pending.setReviewedAt(null);
            pending.setReviewNote(null);
            pending.setApprovalNote(null);
            PeriodCloseRequest saved = periodCloseRequestRepository.save(pending);
            if (accountingComplianceAuditService != null) {
                accountingComplianceAuditService.recordPeriodCloseRequestLifecycle(
                        company,
                        saved,
                        PeriodCloseRequestStatus.PENDING,
                        PeriodCloseRequestStatus.PENDING,
                        "PERIOD_CLOSE_REQUEST_UPDATED",
                        "PERIOD_CLOSE_REQUEST_UPDATED",
                        requester,
                        note,
                        false);
            }
            return toPeriodCloseRequestDto(saved);
        }

        PeriodCloseRequest created = new PeriodCloseRequest();
        created.setCompany(company);
        created.setAccountingPeriod(period);
        created.setStatus(PeriodCloseRequestStatus.PENDING);
        created.setRequestedBy(requester);
        created.setRequestNote(note);
        created.setForceRequested(force);
        created.setRequestedAt(CompanyTime.now(company));
        PeriodCloseRequest saved = periodCloseRequestRepository.save(created);
        if (accountingComplianceAuditService != null) {
            accountingComplianceAuditService.recordPeriodCloseRequestLifecycle(
                    company,
                    saved,
                    null,
                    PeriodCloseRequestStatus.PENDING,
                    "PERIOD_CLOSE_REQUESTED",
                    "PERIOD_CLOSE_REQUESTED",
                    requester,
                    note,
                    false);
        }
        return toPeriodCloseRequestDto(saved);
    }

    @Transactional
    public AccountingPeriodDto approvePeriodClose(Long periodId, PeriodCloseRequestActionRequest request) {
        Company company = companyContextService.requireCurrentCompany();
        AccountingPeriod period = accountingPeriodRepository.lockByCompanyAndId(company, periodId)
                .orElseThrow(() -> new ApplicationException(ErrorCode.VALIDATION_INVALID_REFERENCE,
                        "Accounting period not found"));
        if (period.getStatus() == AccountingPeriodStatus.CLOSED) {
            throw ValidationUtils.invalidState("Accounting period " + period.getLabel() + " is already closed");
        }
        if (periodCloseRequestRepository == null) {
            throw ValidationUtils.invalidState("Period close request workflow is not configured");
        }

        PeriodCloseRequest pending = periodCloseRequestRepository
                .lockByCompanyAndAccountingPeriodAndStatus(company, period, PeriodCloseRequestStatus.PENDING)
                .orElseThrow(() -> ValidationUtils.invalidState(
                        "No pending period close request found for " + period.getLabel()));

        String reviewer = resolveCurrentUsername();
        assertMakerCheckerBoundary(pending, reviewer);
        String approvalNote = normalizeRequiredNote(request != null ? request.note() : null, "Approval note is required");
        boolean force = request != null && request.force() != null
                ? Boolean.TRUE.equals(request.force())
                : pending.isForceRequested();

        pending.setStatus(PeriodCloseRequestStatus.APPROVED);
        pending.setReviewedBy(reviewer);
        pending.setReviewedAt(CompanyTime.now(company));
        pending.setReviewNote(approvalNote);
        pending.setApprovalNote(approvalNote);
        pending.setForceRequested(force);

        AccountingPeriodDto closed = closePeriod(
                periodId,
                new AccountingPeriodCloseRequest(force, approvalNote),
                true,
                pending);
        periodCloseRequestRepository.save(pending);
        if (accountingComplianceAuditService != null) {
            accountingComplianceAuditService.recordPeriodCloseRequestLifecycle(
                    company,
                    pending,
                    PeriodCloseRequestStatus.PENDING,
                    PeriodCloseRequestStatus.APPROVED,
                    "PERIOD_CLOSE_APPROVED",
                    "PERIOD_CLOSE_APPROVED",
                    reviewer,
                    approvalNote,
                    true);
        }
        return closed;
    }

    @Transactional
    public PeriodCloseRequestDto rejectPeriodClose(Long periodId, PeriodCloseRequestActionRequest request) {
        Company company = companyContextService.requireCurrentCompany();
        AccountingPeriod period = accountingPeriodRepository.lockByCompanyAndId(company, periodId)
                .orElseThrow(() -> new ApplicationException(ErrorCode.VALIDATION_INVALID_REFERENCE,
                        "Accounting period not found"));
        if (periodCloseRequestRepository == null) {
            throw ValidationUtils.invalidState("Period close request workflow is not configured");
        }

        PeriodCloseRequest pending = periodCloseRequestRepository
                .lockByCompanyAndAccountingPeriodAndStatus(company, period, PeriodCloseRequestStatus.PENDING)
                .orElseThrow(() -> ValidationUtils.invalidState(
                        "No pending period close request found for " + period.getLabel()));

        String reviewer = resolveCurrentUsername();
        assertMakerCheckerBoundary(pending, reviewer);
        String rejectionNote = normalizeRequiredNote(request != null ? request.note() : null, "Rejection note is required");

        pending.setStatus(PeriodCloseRequestStatus.REJECTED);
        pending.setReviewedBy(reviewer);
        pending.setReviewedAt(CompanyTime.now(company));
        pending.setReviewNote(rejectionNote);
        pending.setApprovalNote(null);

        PeriodCloseRequest saved = periodCloseRequestRepository.save(pending);
        if (accountingComplianceAuditService != null) {
            accountingComplianceAuditService.recordPeriodCloseRequestLifecycle(
                    company,
                    saved,
                    PeriodCloseRequestStatus.PENDING,
                    PeriodCloseRequestStatus.REJECTED,
                    "PERIOD_CLOSE_REJECTED",
                    "PERIOD_CLOSE_REJECTED",
                    reviewer,
                    rejectionNote,
                    true);
        }
        return toPeriodCloseRequestDto(saved);
    }

    @Transactional
    public AccountingPeriodDto closePeriod(Long periodId, AccountingPeriodCloseRequest request) {
        return closePeriod(periodId, request, false, null);
    }

    @Transactional
    private AccountingPeriodDto closePeriod(Long periodId,
                                            AccountingPeriodCloseRequest request,
                                            boolean fromApprovedRequest,
                                            PeriodCloseRequest approvedRequest) {
        Company company = companyContextService.requireCurrentCompany();
        AccountingPeriod period = accountingPeriodRepository.lockByCompanyAndId(company, periodId)
                .orElseThrow(() -> new ApplicationException(ErrorCode.VALIDATION_INVALID_REFERENCE,
                        "Accounting period not found"));
        String beforeStatus = period.getStatus() != null ? period.getStatus().name() : null;
        if (period.getStatus() == AccountingPeriodStatus.CLOSED) {
            return toDto(period);
        }
        String note = request != null && StringUtils.hasText(request.note()) ? request.note().trim() : null;
        if (!StringUtils.hasText(note)) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT, "Close reason is required");
        }
        if (fromApprovedRequest && (approvedRequest == null
                || approvedRequest.getStatus() != PeriodCloseRequestStatus.APPROVED
                || approvedRequest.getAccountingPeriod() == null
                || !Objects.equals(approvedRequest.getAccountingPeriod().getId(), periodId))) {
            throw ValidationUtils.invalidState("Approved period close request is required before closing period");
        }
        periodCloseHook.onPeriodCloseLocked(company, period);
        boolean force = request != null && Boolean.TRUE.equals(request.force());
        assertNoUninvoicedReceipts(company, period);
        if (!force) {
            assertChecklistComplete(company, period);
        }
        if (note != null) {
            period.setChecklistNotes(note);
        }
        BigDecimal netIncome = computeNetIncome(company, period);
        Long closingJournalId = null;
        if (netIncome.compareTo(BigDecimal.ZERO) != 0) {
            JournalEntry closingJe = postClosingJournal(company, period, netIncome, note);
            closingJournalId = closingJe.getId();
        }
        String user = resolveCurrentUsername();
        snapshotService.captureSnapshot(company, period, user);
        Instant now = CompanyTime.now(company);
        period.setStatus(AccountingPeriodStatus.CLOSED);
        period.setClosedAt(now);
        period.setClosedBy(user);
        period.setLockedAt(now);
        period.setLockedBy(user);
        period.setLockReason(note);
        period.setClosingJournalEntryId(closingJournalId);
        AccountingPeriod saved = accountingPeriodRepository.save(period);
        if (accountingComplianceAuditService != null) {
            accountingComplianceAuditService.recordPeriodTransition(
                    company,
                    saved,
                    "PERIOD_CLOSED",
                    beforeStatus,
                    saved.getStatus() != null ? saved.getStatus().name() : null,
                    note,
                    false);
        }
        ensurePeriod(company, period.getEndDate().plusDays(1));
        return toDto(saved);
    }

    @Transactional
    public AccountingPeriodDto confirmBankReconciliation(Long periodId, LocalDate referenceDate, String note) {
        Company company = companyContextService.requireCurrentCompany();
        AccountingPeriod period = resolvePeriod(company, periodId, referenceDate);
        assertChecklistMutable(period);
        period.setBankReconciled(true);
        period.setBankReconciledAt(CompanyTime.now(company));
        period.setBankReconciledBy(resolveCurrentUsername());
        if (StringUtils.hasText(note)) {
            period.setChecklistNotes(note.trim());
        }
        return toDto(accountingPeriodRepository.save(period));
    }

    @Transactional
    public AccountingPeriodDto confirmInventoryCount(Long periodId, LocalDate referenceDate, String note) {
        Company company = companyContextService.requireCurrentCompany();
        AccountingPeriod period = resolvePeriod(company, periodId, referenceDate);
        assertChecklistMutable(period);
        period.setInventoryCounted(true);
        period.setInventoryCountedAt(CompanyTime.now(company));
        period.setInventoryCountedBy(resolveCurrentUsername());
        if (StringUtils.hasText(note)) {
            period.setChecklistNotes(note.trim());
        }
        return toDto(accountingPeriodRepository.save(period));
    }

    @Transactional
    public AccountingPeriodDto lockPeriod(Long periodId, AccountingPeriodLockRequest request) {
        Company company = companyContextService.requireCurrentCompany();
        AccountingPeriod period = accountingPeriodRepository.lockByCompanyAndId(company, periodId)
                .orElseThrow(() -> new ApplicationException(ErrorCode.VALIDATION_INVALID_REFERENCE,
                        "Accounting period not found"));
        if (period.getStatus() == AccountingPeriodStatus.LOCKED || period.getStatus() == AccountingPeriodStatus.CLOSED) {
            return toDto(period);
        }
        if (request == null || !StringUtils.hasText(request.reason())) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT, "Lock reason is required");
        }
        String beforeStatus = period.getStatus() != null ? period.getStatus().name() : null;
        period.setStatus(AccountingPeriodStatus.LOCKED);
        period.setLockedAt(CompanyTime.now(company));
        period.setLockedBy(resolveCurrentUsername());
        period.setLockReason(request.reason().trim());
        AccountingPeriod saved = accountingPeriodRepository.save(period);
        if (accountingComplianceAuditService != null) {
            accountingComplianceAuditService.recordPeriodTransition(
                    company,
                    saved,
                    "PERIOD_LOCKED",
                    beforeStatus,
                    saved.getStatus() != null ? saved.getStatus().name() : null,
                    saved.getLockReason(),
                    false);
        }
        return toDto(saved);
    }

    @Transactional
    public AccountingPeriodDto reopenPeriod(Long periodId, AccountingPeriodReopenRequest request) {
        Company company = companyContextService.requireCurrentCompany();
        AccountingPeriod period = accountingPeriodRepository.lockByCompanyAndId(company, periodId)
                .orElseThrow(() -> new ApplicationException(ErrorCode.VALIDATION_INVALID_REFERENCE,
                        "Accounting period not found"));
        String beforeStatus = period.getStatus() != null ? period.getStatus().name() : null;
        if (period.getStatus() == AccountingPeriodStatus.OPEN) {
            return toDto(period);
        }
        if (request == null || !StringUtils.hasText(request.reason())) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT, "Reopen reason is required");
        }
        String reason = request.reason().trim();
        Instant now = CompanyTime.now(company);
        period.setStatus(AccountingPeriodStatus.OPEN);
        period.setReopenedAt(now);
        period.setReopenedBy(resolveCurrentUsername());
        period.setReopenReason(reason);
        // Auto-reverse closing journal if present
        if (period.getClosingJournalEntryId() != null) {
            journalEntryRepository.findByCompanyAndId(company, period.getClosingJournalEntryId())
                    .ifPresent(closing -> reverseClosingJournalIfNeeded(closing, period, reason));
            period.setClosingJournalEntryId(null);
        }
        snapshotService.deleteSnapshotForPeriod(company, period);
        AccountingPeriod saved = accountingPeriodRepository.save(period);
        if (accountingComplianceAuditService != null) {
            accountingComplianceAuditService.recordPeriodTransition(
                    company,
                    saved,
                    "PERIOD_REOPENED",
                    beforeStatus,
                    saved.getStatus() != null ? saved.getStatus().name() : null,
                    reason,
                    true);
        }
        return toDto(saved);
    }

    public MonthEndChecklistDto getMonthEndChecklist(Long periodId) {
        Company company = companyContextService.requireCurrentCompany();
        AccountingPeriod period = resolvePeriod(company, periodId);
        return buildChecklist(company, period);
    }

    @Transactional
    public MonthEndChecklistDto updateMonthEndChecklist(Long periodId, MonthEndChecklistUpdateRequest request) {
        Company company = companyContextService.requireCurrentCompany();
        AccountingPeriod period = resolvePeriod(company, periodId);
        assertChecklistMutable(period);
        if (request != null) {
            if (request.bankReconciled() != null) {
                period.setBankReconciled(request.bankReconciled());
            }
            if (request.inventoryCounted() != null) {
                period.setInventoryCounted(request.inventoryCounted());
            }
            if (StringUtils.hasText(request.note())) {
                period.setChecklistNotes(request.note().trim());
            }
        }
        AccountingPeriod saved = accountingPeriodRepository.save(period);
        return buildChecklist(company, saved);
    }

    public AccountingPeriod requireOpenPeriod(Company company, LocalDate referenceDate) {
        AccountingPeriod period = lockOrCreatePeriod(company, referenceDate);
        if (period.getStatus() != AccountingPeriodStatus.OPEN) {
            throw new ApplicationException(
                    ErrorCode.VALIDATION_INVALID_INPUT,
                    "Accounting period " + period.getLabel() + " is locked/closed");
        }
        return period;
    }

    private void assertChecklistMutable(AccountingPeriod period) {
        if (period.getStatus() == AccountingPeriodStatus.CLOSED
                || period.getStatus() == AccountingPeriodStatus.LOCKED) {
            throw new ApplicationException(
                    ErrorCode.VALIDATION_INVALID_INPUT,
                    "Checklist cannot be updated for a locked or closed period");
        }
    }

    private AccountingPeriod lockOrCreatePeriod(Company company, LocalDate referenceDate) {
        LocalDate baseDate = referenceDate == null ? resolveCurrentDate(company) : referenceDate;
        LocalDate safeDate = baseDate.withDayOfMonth(1);
        int year = safeDate.getYear();
        int month = safeDate.getMonthValue();
        Optional<AccountingPeriod> locked = accountingPeriodRepository.lockByCompanyAndYearAndMonth(company, year, month);
        if (locked.isPresent()) {
            return locked.get();
        }
        return ensurePeriod(company, safeDate);
    }

    /**
     * Computes net income for a period using double-entry accounting principles.
     * 
     * Net Income = (Revenue + Other Income) - COGS - Operating Expenses - Other Expenses
     * 
     * For each account type:
     * - REVENUE: Normal credit balance. Net = Credits - Debits (positive = income)
     *   Contra accounts (returns, discounts) have debit balances, reducing revenue.
     * - OTHER_INCOME: Normal credit balance (interest income, gains on sales)
     * - EXPENSE: Normal debit balance. Net = Debits - Credits (positive = expense)
     * - OTHER_EXPENSE: Normal debit balance (interest expense, losses)
     * - COGS: Normal debit balance. Net = Debits - Credits (positive = cost)
     * 
     * The query includes all journal entries within the period date range,
     * regardless of posting date (accrual basis).
     * 
     * Prior period adjustments should be posted to EQUITY (Retained Earnings)
     * and will not affect current period net income.
     */
    private BigDecimal computeNetIncome(Company company, AccountingPeriod period) {
        List<Object[]> aggregates = journalLineRepository.summarizeByAccountType(
                company, period.getStartDate(), period.getEndDate());
        
        BigDecimal grossRevenue = BigDecimal.ZERO;
        BigDecimal otherIncome = BigDecimal.ZERO;
        BigDecimal totalCogs = BigDecimal.ZERO;
        BigDecimal totalExpenses = BigDecimal.ZERO;
        BigDecimal otherExpenses = BigDecimal.ZERO;
        
        for (Object[] row : aggregates) {
            if (row == null || row.length < 3) {
                continue;
            }
            AccountType type = (AccountType) row[0];
            BigDecimal debit = row[1] == null ? BigDecimal.ZERO : (BigDecimal) row[1];
            BigDecimal credit = row[2] == null ? BigDecimal.ZERO : (BigDecimal) row[2];
            
            switch (type) {
                case REVENUE -> {
                    // Revenue has credit balance; contra-revenue (returns) reduces it
                    grossRevenue = grossRevenue.add(credit.subtract(debit));
                }
                case OTHER_INCOME -> {
                    // Other income has credit balance (interest, gains)
                    otherIncome = otherIncome.add(credit.subtract(debit));
                }
                case COGS -> {
                    // COGS has debit balance
                    totalCogs = totalCogs.add(debit.subtract(credit));
                }
                case EXPENSE -> {
                    // Operating expenses have debit balance
                    totalExpenses = totalExpenses.add(debit.subtract(credit));
                }
                case OTHER_EXPENSE -> {
                    // Other expenses have debit balance (interest expense, losses)
                    otherExpenses = otherExpenses.add(debit.subtract(credit));
                }
                default -> {
                    // ASSET, LIABILITY, EQUITY don't affect net income
                    // Prior period adjustments go to EQUITY (Retained Earnings)
                }
            }
        }
        
        // Net Income = (Gross Revenue + Other Income) - COGS - Operating Expenses - Other Expenses
        BigDecimal totalIncome = grossRevenue.add(otherIncome);
        BigDecimal totalCosts = totalCogs.add(totalExpenses).add(otherExpenses);
        BigDecimal netIncome = totalIncome.subtract(totalCosts);
        return netIncome.setScale(2, RoundingMode.HALF_UP);
    }

    private JournalEntry postClosingJournal(Company company,
                                            AccountingPeriod period,
                                            BigDecimal netIncome,
                                            String note) {
        String reference = "PERIOD-CLOSE-" + period.getYear() + String.format("%02d", period.getMonth());
        return journalEntryRepository.findByCompanyAndReferenceNumber(company, reference)
                .orElseGet(() -> createSystemJournal(company, period, reference, note, netIncome));
    }

    private JournalEntry createSystemJournal(Company company,
                                             AccountingPeriod period,
                                             String reference,
                                             String note,
                                             BigDecimal netIncome) {
        Account retained = ensureEquityAccount(company, "RETAINED_EARNINGS", "Retained Earnings");
        Account periodResult = ensureEquityAccount(company, "PERIOD_RESULT", "Period Result");
        BigDecimal amount = netIncome.abs();
        String memo = note != null ? note : "Period close " + period.getLabel();
        boolean profit = netIncome.compareTo(BigDecimal.ZERO) > 0;
        Long debitAccountId = profit ? periodResult.getId() : retained.getId();
        Long creditAccountId = profit ? retained.getId() : periodResult.getId();
        LocalDate entryDate = period.getEndDate();
        LocalDate today = companyClock.today(company);
        if (entryDate.isAfter(today)) {
            entryDate = today;
        }
        AccountingFacade accountingFacade = accountingFacadeProvider.getObject();
        JournalEntryDto posted = accountingFacade.createStandardJournal(new JournalCreationRequest(
                amount,
                debitAccountId,
                creditAccountId,
                memo,
                "ACCOUNTING_PERIOD",
                reference,
                null,
                null,
                entryDate,
                null,
                null,
                Boolean.TRUE
        ));
        return journalEntryRepository.findByCompanyAndId(company, posted.id())
                .orElseThrow(() -> new ApplicationException(ErrorCode.SYSTEM_INTERNAL_ERROR,
                        "Closing journal entry not found after posting"));
    }

    private Account ensureEquityAccount(Company company, String code, String name) {
        return accountRepository.findByCompanyAndCodeIgnoreCase(company, code)
                .orElseGet(() -> {
                    Account acct = new Account();
                    acct.setCompany(company);
                    acct.setCode(code);
                    acct.setName(name);
                    acct.setType(AccountType.EQUITY);
                    return accountRepository.save(acct);
                });
    }

    private void reverseClosingJournalIfNeeded(JournalEntry closing,
                                               AccountingPeriod period,
                                               String reason) {
        if (closing == null) {
            return;
        }
        String status = closing.getStatus() != null ? closing.getStatus().toUpperCase() : null;
        if ("REVERSED".equals(status) || "VOIDED".equals(status) || closing.getReversalEntry() != null) {
            return;
        }
        AccountingFacade accountingFacade = accountingFacadeProvider.getObject();
        accountingFacade.reverseClosingEntryForPeriodReopen(closing, period, reason);
    }

    @Transactional
    public AccountingPeriod ensurePeriod(Company company, LocalDate referenceDate) {
        LocalDate baseDate = referenceDate == null ? resolveCurrentDate(company) : referenceDate;
        LocalDate safeDate = baseDate.withDayOfMonth(1);
        int year = safeDate.getYear();
        int month = safeDate.getMonthValue();
        return accountingPeriodRepository.findByCompanyAndYearAndMonth(company, year, month)
                .orElseGet(() -> {
                    AccountingPeriod period = new AccountingPeriod();
                    period.setCompany(company);
                    period.setYear(year);
                    period.setMonth(month);
                    period.setStartDate(safeDate);
                    period.setEndDate(safeDate.plusMonths(1).minusDays(1));
                    period.setStatus(AccountingPeriodStatus.OPEN);
                    period.setCostingMethod(CostingMethod.WEIGHTED_AVERAGE);
                    return accountingPeriodRepository.save(period);
                });
    }

    private AccountingPeriod resolvePeriod(Company company, Long periodId, LocalDate referenceDate) {
        if (periodId != null) {
            return companyEntityLookup.requireAccountingPeriod(company, periodId);
        }
        LocalDate effectiveDate = referenceDate == null ? resolveCurrentDate(company) : referenceDate;
        return ensurePeriod(company, effectiveDate);
    }

    private void ensureSurroundingPeriods(Company company) {
        LocalDate today = resolveCurrentDate(company);
        ensurePeriod(company, today);
        ensurePeriod(company, today.minusMonths(1));
        ensurePeriod(company, today.plusMonths(1));
    }

    private void assertChecklistComplete(Company company, AccountingPeriod period) {
        if (!period.isBankReconciled()) {
            throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidState("Bank reconciliation has not been confirmed for this period");
        }
        if (!period.isInventoryCounted()) {
            throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidState("Inventory count has not been confirmed for this period");
        }
        long drafts = journalEntryRepository.countByCompanyAndEntryDateBetweenAndStatusIn(
                company,
                period.getStartDate(),
                period.getEndDate(),
                List.of("DRAFT", "PENDING"));
        if (drafts > 0) {
            throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidState("There are " + drafts + " draft entries in this period");
        }
        ChecklistDiagnostics diagnostics = evaluateChecklistDiagnostics(company, period);
        List<String> unresolvedControls = diagnostics.unresolvedControlsInPolicyOrder();
        if (!unresolvedControls.isEmpty()) {
            throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidState(UNRESOLVED_CONTROLS_PREFIX + formatUnresolvedControls(unresolvedControls));
        }
        if (!diagnostics.trialBalanceBalanced()) {
            throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidState(
                    "Trial balance is not balanced (difference " + formatVariance(diagnostics.trialBalanceDifference()) + ")");
        }
        if (!diagnostics.inventoryReconciled()) {
            throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidState("Inventory reconciliation variance exceeds tolerance (" +
                    formatVariance(diagnostics.inventoryVariance()) + ")");
        }
        if (!diagnostics.arReconciled()) {
            throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidState("AR reconciliation variance exceeds tolerance (" +
                    formatVariance(diagnostics.arVariance()) + ")");
        }
        if (!diagnostics.apReconciled()) {
            throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidState("AP reconciliation variance exceeds tolerance (" +
                    formatVariance(diagnostics.apVariance()) + ")");
        }
        if (!diagnostics.gstReconciled()) {
            throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidState("GST reconciliation variance exceeds tolerance (" +
                    formatVariance(diagnostics.gstVariance()) + ")");
        }
        if (!diagnostics.reconciliationDiscrepanciesResolved()) {
            throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidState(
                    "Open reconciliation discrepancies exist in this period (" + diagnostics.openReconciliationDiscrepancies() + ")");
        }
        if (diagnostics.unbalancedJournals() > 0) {
            throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidState("Unbalanced journals present in this period (" +
                    diagnostics.unbalancedJournals() + ")");
        }
        if (diagnostics.unlinkedDocuments() > 0) {
            throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidState("Documents missing journal links in this period (" +
                    diagnostics.unlinkedDocuments() + ")");
        }
        if (diagnostics.unpostedDocuments() > 0) {
            throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidState("Unposted documents exist in this period (" +
                    diagnostics.unpostedDocuments() + ")");
        }
    }

    private void assertNoUninvoicedReceipts(Company company, AccountingPeriod period) {
        long uninvoicedReceipts = countUninvoicedReceipts(company, period);
        if (uninvoicedReceipts > 0) {
            throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidState("Un-invoiced goods receipts exist in this period (" +
                    uninvoicedReceipts + ")");
        }
    }

    private AccountingPeriodDto toDto(AccountingPeriod period) {
        return new AccountingPeriodDto(
                period.getId(),
                period.getYear(),
                period.getMonth(),
                period.getStartDate(),
                period.getEndDate(),
                period.getLabel(),
                period.getStatus().name(),
                period.isBankReconciled(),
                period.getBankReconciledAt(),
                period.getBankReconciledBy(),
                period.isInventoryCounted(),
                period.getInventoryCountedAt(),
                period.getInventoryCountedBy(),
                period.getClosedAt(),
                period.getClosedBy(),
                period.getChecklistNotes(),
                period.getLockedAt(),
                period.getLockedBy(),
                period.getLockReason(),
                period.getReopenedAt(),
                period.getReopenedBy(),
                period.getReopenReason(),
                period.getClosingJournalEntryId(),
                period.getChecklistNotes(),
                resolveCostingMethodOrDefault(period.getCostingMethod()).name()
        );
    }

    private CostingMethod resolveCostingMethodOrDefault(CostingMethod costingMethod) {
        return costingMethod != null ? costingMethod : CostingMethod.WEIGHTED_AVERAGE;
    }

    private LocalDate resolveCurrentDate(Company company) {
        return companyClock.today(company);
    }

    private AccountingPeriod resolvePeriod(Company company, Long periodId) {
        if (periodId != null) {
            return companyEntityLookup.requireAccountingPeriod(company, periodId);
        }
        return accountingPeriodRepository.findFirstByCompanyAndStatusOrderByStartDateDesc(company, AccountingPeriodStatus.OPEN)
                .orElseGet(() -> ensurePeriod(company, resolveCurrentDate(company)));
    }

    private MonthEndChecklistDto buildChecklist(Company company, AccountingPeriod period) {
        long draftEntries = journalEntryRepository.countByCompanyAndEntryDateBetweenAndStatusIn(
                company,
                period.getStartDate(),
                period.getEndDate(),
                List.of("DRAFT", "PENDING"));
        boolean draftsCleared = draftEntries == 0;
        ChecklistDiagnostics diagnostics = evaluateChecklistDiagnostics(company, period);
        boolean inventoryControlResolved = diagnostics.inventoryControlResolved();
        boolean arControlResolved = diagnostics.arControlResolved();
        boolean apControlResolved = diagnostics.apControlResolved();
        boolean trialBalanceBalanced = diagnostics.trialBalanceBalanced();
        boolean inventoryReconciled = diagnostics.inventoryReconciled();
        boolean arReconciled = diagnostics.arReconciled();
        boolean apReconciled = diagnostics.apReconciled();
        boolean gstControlResolved = diagnostics.gstControlResolved();
        boolean gstReconciled = diagnostics.gstReconciled();
        boolean reconciliationDiscrepanciesResolved = diagnostics.reconciliationDiscrepanciesResolved();
        boolean unbalancedCleared = diagnostics.unbalancedJournals() == 0;
        boolean unlinkedCleared = diagnostics.unlinkedDocuments() == 0;
        boolean unpostedCleared = diagnostics.unpostedDocuments() == 0;
        boolean receiptsCleared = diagnostics.uninvoicedReceipts() == 0;
        String inventoryDetail = !inventoryControlResolved
                ? "Control unresolved: inventory reconciliation result unavailable"
                : (inventoryReconciled
                ? "Variance " + formatVariance(diagnostics.inventoryVariance()) + " within tolerance"
                : "Variance " + formatVariance(diagnostics.inventoryVariance()) + " exceeds tolerance");
        String arDetail = !arControlResolved
                ? "Control unresolved: AR reconciliation result unavailable"
                : (arReconciled
                ? "Variance " + formatVariance(diagnostics.arVariance()) + " within tolerance"
                : "Variance " + formatVariance(diagnostics.arVariance()) + " exceeds tolerance");
        String apDetail = !apControlResolved
                ? "Control unresolved: AP reconciliation result unavailable"
                : (apReconciled
                ? "Variance " + formatVariance(diagnostics.apVariance()) + " within tolerance"
                : "Variance " + formatVariance(diagnostics.apVariance()) + " exceeds tolerance");
        String gstDetail = !gstControlResolved
                ? "Control unresolved: GST reconciliation result unavailable"
                : (gstReconciled
                ? "Variance " + formatVariance(diagnostics.gstVariance()) + " within tolerance"
                : "Variance " + formatVariance(diagnostics.gstVariance()) + " exceeds tolerance");
        String discrepancyDetail = reconciliationDiscrepanciesResolved
                ? "No open discrepancies"
                : diagnostics.openReconciliationDiscrepancies() + " open discrepancies require resolution";
        String trialBalanceDetail = trialBalanceBalanced
                ? "Debits " + formatVariance(diagnostics.trialBalanceTotalDebit())
                + " equal credits " + formatVariance(diagnostics.trialBalanceTotalCredit())
                : "Debits " + formatVariance(diagnostics.trialBalanceTotalDebit())
                + " and credits " + formatVariance(diagnostics.trialBalanceTotalCredit())
                + " differ by " + formatVariance(diagnostics.trialBalanceDifference());
        List<MonthEndChecklistItemDto> items = List.of(
                new MonthEndChecklistItemDto(
                        "bankReconciled",
                        "Bank accounts reconciled",
                        period.isBankReconciled(),
                        period.isBankReconciled() ? "Confirmed" : "Pending review"),
                new MonthEndChecklistItemDto(
                        "inventoryCounted",
                        "Inventory counted",
                        period.isInventoryCounted(),
                        period.isInventoryCounted() ? "Counts logged" : "Awaiting stock count"),
                new MonthEndChecklistItemDto(
                        "draftEntries",
                        "Draft entries cleared",
                        draftsCleared,
                        draftsCleared ? "All entries posted" : draftEntries + " draft entries remaining"),
                new MonthEndChecklistItemDto(
                        "trialBalanceBalanced",
                        "Trial balance verified",
                        trialBalanceBalanced,
                        trialBalanceDetail),
                new MonthEndChecklistItemDto(
                        "inventoryReconciled",
                        "Inventory reconciled to GL",
                        inventoryReconciled,
                        inventoryDetail),
                new MonthEndChecklistItemDto(
                        "arReconciled",
                        "AR reconciled to dealer ledger",
                        arReconciled,
                        arDetail),
                new MonthEndChecklistItemDto(
                        "apReconciled",
                        "AP reconciled to supplier ledger",
                        apReconciled,
                        apDetail),
                new MonthEndChecklistItemDto(
                        "gstReconciled",
                        "GST reconciled",
                        gstReconciled,
                        gstDetail),
                new MonthEndChecklistItemDto(
                        "reconciliationDiscrepanciesResolved",
                        "Reconciliation discrepancies resolved",
                        reconciliationDiscrepanciesResolved,
                        discrepancyDetail),
                new MonthEndChecklistItemDto(
                        "unbalancedJournals",
                        "Unbalanced journals cleared",
                        unbalancedCleared,
                        unbalancedCleared ? "All journals balanced" : diagnostics.unbalancedJournals() + " unbalanced journals"),
                new MonthEndChecklistItemDto(
                        "unlinkedDocuments",
                        "Documents linked to journals",
                        unlinkedCleared,
                        unlinkedCleared ? "All documents linked" : diagnostics.unlinkedDocuments() + " missing journal links"),
                new MonthEndChecklistItemDto(
                        "uninvoicedReceipts",
                        "Goods receipts invoiced",
                        receiptsCleared,
                        receiptsCleared ? "All receipts invoiced" : diagnostics.uninvoicedReceipts() + " receipts awaiting invoice"),
                new MonthEndChecklistItemDto(
                        "unpostedDocuments",
                        "Unposted documents cleared",
                        unpostedCleared,
                        unpostedCleared ? "All documents posted" : diagnostics.unpostedDocuments() + " unposted documents")
        );
        boolean ready = period.isBankReconciled()
                && period.isInventoryCounted()
                && draftsCleared
                && trialBalanceBalanced
                && inventoryReconciled
                && arReconciled
                && apReconciled
                && gstReconciled
                && reconciliationDiscrepanciesResolved
                && unbalancedCleared
                && unlinkedCleared
                && receiptsCleared
                && unpostedCleared;
        return new MonthEndChecklistDto(toDto(period), items, ready);
    }

    private ChecklistDiagnostics evaluateChecklistDiagnostics(Company company, AccountingPeriod period) {
        List<JournalEntry> periodEntries = journalEntryRepository
                .findByCompanyAndEntryDateBetweenOrderByEntryDateAsc(company, period.getStartDate(), period.getEndDate());
        ReconciliationSummaryDto inventory = reportService.inventoryReconciliation();
        ReconciliationServiceCore.PeriodReconciliationResult periodReconciliation = reconciliationService
                .reconcileSubledgersForPeriod(period.getStartDate(), period.getEndDate());
        GstReconciliationDto gstReconciliation = null;
        try {
            gstReconciliation = reconciliationService.generateGstReconciliation(YearMonth.from(period.getStartDate()));
        } catch (ApplicationException ex) {
            gstReconciliation = null;
        }
        long openReconciliationDiscrepancies = reconciliationDiscrepancyRepository
                .countByCompanyAndAccountingPeriodAndStatus(company, period, ReconciliationDiscrepancyStatus.OPEN);
        TrialBalanceDto trialBalance = resolveTrialBalanceForChecklist(period, periodEntries);
        long unbalancedJournals = countUnbalancedJournals(periodEntries);
        long unlinkedDocuments = countUnlinkedDocuments(company, period);
        long unpostedDocuments = countUnpostedDocuments(company, period);
        long uninvoicedReceipts = countUninvoicedReceipts(company, period);
        return new ChecklistDiagnostics(
                inventory,
                periodReconciliation,
                gstReconciliation,
                openReconciliationDiscrepancies,
                trialBalance,
                unpostedDocuments,
                unlinkedDocuments,
                unbalancedJournals,
                uninvoicedReceipts);
    }

    private long countUnbalancedJournals(List<JournalEntry> periodEntries) {
        if (periodEntries == null || periodEntries.isEmpty()) {
            return 0;
        }
        return periodEntries.stream()
                .filter(this::isUnbalanced)
                .count();
    }

    private TrialBalanceDto resolveTrialBalanceForChecklist(AccountingPeriod period,
                                                            List<JournalEntry> periodEntries) {
        TrialBalanceDto reported = reportService.trialBalance(period.getEndDate());
        if (reported != null) {
            return reported;
        }
        BigDecimal totalDebit = BigDecimal.ZERO;
        BigDecimal totalCredit = BigDecimal.ZERO;
        if (periodEntries != null) {
            for (JournalEntry entry : periodEntries) {
                if (entry == null || entry.getLines() == null) {
                    continue;
                }
                for (JournalLine line : entry.getLines()) {
                    totalDebit = totalDebit.add(safe(line != null ? line.getDebit() : null));
                    totalCredit = totalCredit.add(safe(line != null ? line.getCredit() : null));
                }
            }
        }
        boolean balanced = totalDebit.subtract(totalCredit).abs().compareTo(RECONCILIATION_TOLERANCE) <= 0;
        return new TrialBalanceDto(List.of(), totalDebit, totalCredit, balanced, null);
    }

    private boolean isUnbalanced(JournalEntry entry) {
        BigDecimal debits = entry.getLines().stream()
                .map(JournalLine::getDebit)
                .map(this::safe)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal credits = entry.getLines().stream()
                .map(JournalLine::getCredit)
                .map(this::safe)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return debits.subtract(credits).abs().compareTo(RECONCILIATION_TOLERANCE) > 0;
    }

    private long countUnpostedDocuments(Company company, AccountingPeriod period) {
        long invoiceDrafts = invoiceRepository.countByCompanyAndIssueDateBetweenAndStatusIn(
                company,
                period.getStartDate(),
                period.getEndDate(),
                List.of("DRAFT"));
        long purchaseUnposted = rawMaterialPurchaseRepository.countByCompanyAndInvoiceDateBetweenAndStatusNotIn(
                company,
                period.getStartDate(),
                period.getEndDate(),
                List.of("POSTED", "PARTIAL", "PAID"));
        long payrollUnposted = payrollRunRepository.countByCompanyAndPeriodBetweenAndStatusIn(
                company,
                period.getStartDate(),
                period.getEndDate(),
                List.of(PayrollRun.PayrollStatus.DRAFT,
                        PayrollRun.PayrollStatus.CALCULATED,
                        PayrollRun.PayrollStatus.APPROVED));
        return invoiceDrafts + purchaseUnposted + payrollUnposted;
    }

    private long countUninvoicedReceipts(Company company, AccountingPeriod period) {
        return goodsReceiptRepository.countByCompanyAndReceiptDateBetweenAndStatusNot(
                company,
                period.getStartDate(),
                period.getEndDate(),
                GoodsReceiptStatus.INVOICED);
    }

    private long countUnlinkedDocuments(Company company, AccountingPeriod period) {
        long invoiceUnlinked = invoiceRepository.countByCompanyAndIssueDateBetweenAndStatusNotAndJournalEntryIsNull(
                company,
                period.getStartDate(),
                period.getEndDate(),
                "DRAFT");
        long purchaseUnlinked = rawMaterialPurchaseRepository.countByCompanyAndInvoiceDateBetweenAndStatusInAndJournalEntryIsNull(
                company,
                period.getStartDate(),
                period.getEndDate(),
                List.of("POSTED", "PARTIAL", "PAID"));
        long payrollUnlinked = payrollRunRepository.countByCompanyAndPeriodBetweenAndStatusInAndJournalMissing(
                company,
                period.getStartDate(),
                period.getEndDate(),
                List.of(PayrollRun.PayrollStatus.POSTED, PayrollRun.PayrollStatus.PAID));
        return invoiceUnlinked + purchaseUnlinked + payrollUnlinked;
    }

    private PeriodCloseRequestDto toPeriodCloseRequestDto(PeriodCloseRequest request) {
        if (request == null) {
            return null;
        }
        AccountingPeriod period = request.getAccountingPeriod();
        String periodStatus = period != null && period.getStatus() != null ? period.getStatus().name() : null;
        return new PeriodCloseRequestDto(
                request.getId(),
                request.getPublicId(),
                period != null ? period.getId() : null,
                period != null ? period.getLabel() : null,
                periodStatus,
                request.getStatus() != null ? request.getStatus().name() : null,
                request.isForceRequested(),
                request.getRequestedBy(),
                request.getRequestNote(),
                request.getRequestedAt(),
                request.getReviewedBy(),
                request.getReviewedAt(),
                request.getReviewNote(),
                request.getApprovalNote()
        );
    }

    private String normalizeRequiredNote(String note, String message) {
        if (!StringUtils.hasText(note)) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT, message);
        }
        return note.trim();
    }

    private void assertMakerCheckerBoundary(PeriodCloseRequest request, String reviewer) {
        String requester = normalizeActor(request != null ? request.getRequestedBy() : null, "requestedBy");
        String normalizedReviewer = normalizeActor(reviewer, "reviewedBy");
        if (requester.equalsIgnoreCase(normalizedReviewer)) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                    "Maker-checker violation: requester and reviewer cannot be the same actor")
                    .withDetail("resourceType", "period_close_request")
                    .withDetail("requestedBy", requester)
                    .withDetail("reviewedBy", normalizedReviewer);
        }
    }

    private String normalizeActor(String actor, String fieldName) {
        if (!StringUtils.hasText(actor)) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                    fieldName + " is required");
        }
        return actor.trim();
    }

    private String formatVariance(BigDecimal variance) {
        return safe(variance).setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private String formatUnresolvedControls(List<String> unresolvedControls) {
        return unresolvedControls.stream()
                .map(control -> {
                    String guidance = UNRESOLVED_CONTROL_GUIDANCE.get(control);
                    if (!StringUtils.hasText(guidance)) {
                        return control;
                    }
                    return control + " [" + guidance + "]";
                })
                .reduce((left, right) -> left + ", " + right)
                .orElse("");
    }

    private BigDecimal safe(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private record ChecklistDiagnostics(
            ReconciliationSummaryDto inventory,
            ReconciliationServiceCore.PeriodReconciliationResult periodReconciliation,
            GstReconciliationDto gstReconciliation,
            long openReconciliationDiscrepancies,
            TrialBalanceDto trialBalance,
            long unpostedDocuments,
            long unlinkedDocuments,
            long unbalancedJournals,
            long uninvoicedReceipts
    ) {
        List<String> unresolvedControlsInPolicyOrder() {
            List<String> unresolved = new ArrayList<>(RECONCILIATION_CONTROL_ORDER.size());
            if (!inventoryControlResolved()) {
                unresolved.add(RECONCILIATION_CONTROL_ORDER.get(0));
            }
            if (!arControlResolved()) {
                unresolved.add(RECONCILIATION_CONTROL_ORDER.get(1));
            }
            if (!apControlResolved()) {
                unresolved.add(RECONCILIATION_CONTROL_ORDER.get(2));
            }
            if (!gstControlResolved()) {
                unresolved.add(RECONCILIATION_CONTROL_ORDER.get(3));
            }
            if (!reconciliationDiscrepanciesResolved()) {
                unresolved.add(RECONCILIATION_CONTROL_ORDER.get(4));
            }
            return List.copyOf(unresolved);
        }

        boolean inventoryControlResolved() {
            return inventory != null && inventory.variance() != null;
        }

        boolean inventoryReconciled() {
            return inventoryControlResolved() && varianceWithinTolerance(inventoryVariance());
        }

        BigDecimal inventoryVariance() {
            return inventory != null ? inventory.variance() : BigDecimal.ZERO;
        }

        boolean arControlResolved() {
            return periodReconciliation != null && periodReconciliation.arVariance() != null;
        }

        boolean arReconciled() {
            return arControlResolved() && periodReconciliation.arReconciled();
        }

        BigDecimal arVariance() {
            return periodReconciliation != null ? periodReconciliation.arVariance() : BigDecimal.ZERO;
        }

        boolean apControlResolved() {
            return periodReconciliation != null && periodReconciliation.apVariance() != null;
        }

        boolean apReconciled() {
            return apControlResolved() && periodReconciliation.apReconciled();
        }

        BigDecimal apVariance() {
            return periodReconciliation != null ? periodReconciliation.apVariance() : BigDecimal.ZERO;
        }

        boolean gstControlResolved() {
            return gstReconciliation != null
                    && gstReconciliation.getNetLiability() != null
                    && gstReconciliation.getNetLiability().getTotal() != null;
        }

        boolean gstReconciled() {
            return gstControlResolved() && varianceWithinTolerance(gstVariance());
        }

        BigDecimal gstVariance() {
            if (!gstControlResolved()) {
                return BigDecimal.ZERO;
            }
            return gstReconciliation.getNetLiability().getTotal();
        }

        boolean reconciliationDiscrepanciesResolved() {
            return openReconciliationDiscrepancies <= 0;
        }


        boolean trialBalanceBalanced() {
            return trialBalance != null && trialBalance.balanced();
        }

        BigDecimal trialBalanceTotalDebit() {
            return trialBalance == null ? BigDecimal.ZERO : safe(trialBalance.totalDebit());
        }

        BigDecimal trialBalanceTotalCredit() {
            return trialBalance == null ? BigDecimal.ZERO : safe(trialBalance.totalCredit());
        }

        BigDecimal trialBalanceDifference() {
            return trialBalanceTotalDebit().subtract(trialBalanceTotalCredit());
        }

        private boolean varianceWithinTolerance(BigDecimal variance) {
            return variance != null && variance.abs().compareTo(RECONCILIATION_TOLERANCE) <= 0;
        }

        private BigDecimal safe(BigDecimal value) {
            return value == null ? BigDecimal.ZERO : value;
        }
    }

    private String resolveCurrentUsername() {
        return SecurityActorResolver.resolveActorWithSystemProcessFallback();
    }
}
