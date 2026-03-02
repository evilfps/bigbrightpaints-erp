package com.bigbrightpaints.erp.modules.reports.service;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.core.validation.ValidationUtils;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriod;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriodRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriodSnapshot;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriodSnapshotRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriodStatus;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.reports.dto.ReportMetadata;
import com.bigbrightpaints.erp.modules.reports.dto.ReportSource;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Optional;

@Component
public class ReportQuerySupport {

    private final CompanyContextService companyContextService;
    private final AccountingPeriodRepository accountingPeriodRepository;
    private final AccountingPeriodSnapshotRepository snapshotRepository;
    private final CompanyClock companyClock;

    public ReportQuerySupport(CompanyContextService companyContextService,
                              AccountingPeriodRepository accountingPeriodRepository,
                              AccountingPeriodSnapshotRepository snapshotRepository,
                              CompanyClock companyClock) {
        this.companyContextService = companyContextService;
        this.accountingPeriodRepository = accountingPeriodRepository;
        this.snapshotRepository = snapshotRepository;
        this.companyClock = companyClock;
    }

    public FinancialQueryWindow resolveWindow(FinancialReportQueryRequest request) {
        Company company = resolveCompany(request.companyId());
        ExportOptions exportOptions = resolveExportOptions(request.exportFormat());

        LocalDate effectiveAsOf = request.asOfDate() != null ? request.asOfDate() : companyClock.today(company);
        LocalDate startDate = request.startDate();
        LocalDate endDate = request.endDate();

        AccountingPeriod explicitPeriod = resolvePeriod(company, request.periodId());
        if (explicitPeriod != null && startDate == null && endDate == null) {
            startDate = explicitPeriod.getStartDate();
            endDate = explicitPeriod.getEndDate();
            if (request.asOfDate() == null) {
                effectiveAsOf = explicitPeriod.getEndDate();
            }
        }

        if ((startDate == null) != (endDate == null)) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_DATE,
                    "Both startDate and endDate must be provided together");
        }

        ReportSource source = request.asOfDate() != null ? ReportSource.AS_OF : ReportSource.LIVE;
        AccountingPeriod periodForWindow = explicitPeriod;
        AccountingPeriodSnapshot snapshot = null;

        if (startDate != null && endDate != null) {
            ValidationUtils.validateDateRange(startDate, endDate, "startDate", "endDate");
            if (periodForWindow == null && sameMonth(startDate, endDate)) {
                periodForWindow = accountingPeriodRepository
                        .findByCompanyAndYearAndMonth(company, startDate.getYear(), startDate.getMonthValue())
                        .orElse(null);
            }
        } else if (periodForWindow == null) {
            periodForWindow = accountingPeriodRepository
                    .findByCompanyAndYearAndMonth(company, effectiveAsOf.getYear(), effectiveAsOf.getMonthValue())
                    .orElse(null);
        }

        if (periodForWindow != null && periodForWindow.getStatus() == AccountingPeriodStatus.CLOSED) {
            Long snapshotPeriodId = periodForWindow.getId();
            Optional<AccountingPeriodSnapshot> snapshotCandidate = snapshotRepository.findByCompanyAndPeriod(company, periodForWindow);
            if (snapshotCandidate.isEmpty()) {
                throw new ApplicationException(ErrorCode.BUSINESS_CONSTRAINT_VIOLATION,
                        "Closed period snapshot is required for reports")
                        .withDetail("companyId", company.getId())
                        .withDetail("periodId", snapshotPeriodId)
                        .withDetail("asOfDate", effectiveAsOf);
            }
            snapshot = snapshotCandidate.get();
            source = ReportSource.SNAPSHOT;
            if (startDate == null && endDate == null) {
                startDate = periodForWindow.getStartDate();
                endDate = periodForWindow.getEndDate();
            }
        }

        if (startDate == null && endDate == null) {
            if (periodForWindow != null) {
                startDate = periodForWindow.getStartDate();
                endDate = periodForWindow.getEndDate();
            } else {
                startDate = effectiveAsOf.withDayOfMonth(1);
                endDate = effectiveAsOf;
            }
        }

        return new FinancialQueryWindow(
                company,
                startDate,
                endDate,
                effectiveAsOf,
                periodForWindow,
                snapshot,
                source,
                exportOptions
        );
    }

    public FinancialComparisonWindow resolveComparison(FinancialReportQueryRequest request) {
        if ((request.comparativeStartDate() == null) != (request.comparativeEndDate() == null)) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_DATE,
                    "Both comparativeStartDate and comparativeEndDate must be provided together");
        }
        if (request.comparativeStartDate() == null && request.comparativePeriodId() == null) {
            return null;
        }

        FinancialReportQueryRequest comparisonRequest = new FinancialReportQueryRequest(
                request.comparativePeriodId(),
                request.comparativeStartDate(),
                request.comparativeEndDate(),
                null,
                request.companyId(),
                null,
                null,
                null,
                request.exportFormat()
        );

        FinancialQueryWindow window = resolveWindow(comparisonRequest);
        return new FinancialComparisonWindow(window);
    }

    public ReportMetadata metadata(FinancialQueryWindow window) {
        return new ReportMetadata(
                window.asOfDate(),
                window.startDate(),
                window.endDate(),
                window.source(),
                window.period() != null ? window.period().getId() : null,
                window.period() != null && window.period().getStatus() != null
                        ? window.period().getStatus().name() : null,
                window.snapshot() != null ? window.snapshot().getId() : null,
                window.exportOptions().pdfReady(),
                window.exportOptions().csvReady(),
                window.exportOptions().requestedFormat()
        );
    }

    public Company resolveCompany(Long requestedCompanyId) {
        Company company = companyContextService.requireCurrentCompany();
        if (requestedCompanyId != null && !requestedCompanyId.equals(company.getId())) {
            throw new ApplicationException(ErrorCode.AUTH_INSUFFICIENT_PERMISSIONS,
                    "Requested company does not match authenticated company context")
                    .withDetail("requestedCompanyId", requestedCompanyId)
                    .withDetail("activeCompanyId", company.getId());
        }
        return company;
    }

    private AccountingPeriod resolvePeriod(Company company, Long periodId) {
        if (periodId == null) {
            return null;
        }
        return accountingPeriodRepository.findByCompanyAndId(company, periodId)
                .orElseThrow(() -> new ApplicationException(ErrorCode.BUSINESS_ENTITY_NOT_FOUND,
                        "Accounting period not found: " + periodId));
    }

    private ExportOptions resolveExportOptions(String exportFormat) {
        if (exportFormat == null || exportFormat.isBlank()) {
            return new ExportOptions(true, true, null);
        }
        String normalized = exportFormat.trim().toUpperCase();
        return switch (normalized) {
            case "PDF" -> new ExportOptions(true, true, "PDF");
            case "CSV" -> new ExportOptions(true, true, "CSV");
            default -> throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                    "Unsupported exportFormat: " + exportFormat);
        };
    }

    private boolean sameMonth(LocalDate first, LocalDate second) {
        return first != null && second != null
                && first.getYear() == second.getYear()
                && first.getMonthValue() == second.getMonthValue();
    }

    public record ExportOptions(
            boolean pdfReady,
            boolean csvReady,
            String requestedFormat
    ) {
    }

    public record FinancialQueryWindow(
            Company company,
            LocalDate startDate,
            LocalDate endDate,
            LocalDate asOfDate,
            AccountingPeriod period,
            AccountingPeriodSnapshot snapshot,
            ReportSource source,
            ExportOptions exportOptions
    ) {
    }

    public record FinancialComparisonWindow(
            FinancialQueryWindow window
    ) {
    }
}
