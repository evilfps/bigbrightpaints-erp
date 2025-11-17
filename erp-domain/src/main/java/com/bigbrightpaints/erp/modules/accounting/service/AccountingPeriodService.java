package com.bigbrightpaints.erp.modules.accounting.service;

import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriod;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriodRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriodStatus;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryRepository;
import com.bigbrightpaints.erp.modules.accounting.dto.AccountingPeriodCloseRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.AccountingPeriodDto;
import com.bigbrightpaints.erp.modules.accounting.dto.MonthEndChecklistDto;
import com.bigbrightpaints.erp.modules.accounting.dto.MonthEndChecklistItemDto;
import com.bigbrightpaints.erp.modules.accounting.dto.MonthEndChecklistUpdateRequest;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import jakarta.transaction.Transactional;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

@Service
public class AccountingPeriodService {

    private final AccountingPeriodRepository accountingPeriodRepository;
    private final CompanyContextService companyContextService;
    private final JournalEntryRepository journalEntryRepository;

    public AccountingPeriodService(AccountingPeriodRepository accountingPeriodRepository,
                                   CompanyContextService companyContextService,
                                   JournalEntryRepository journalEntryRepository) {
        this.accountingPeriodRepository = accountingPeriodRepository;
        this.companyContextService = companyContextService;
        this.journalEntryRepository = journalEntryRepository;
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
        AccountingPeriod period = accountingPeriodRepository.findByCompanyAndId(company, periodId)
                .orElseThrow(() -> new IllegalArgumentException("Accounting period not found"));
        return toDto(period);
    }

    @Transactional
    public AccountingPeriodDto closePeriod(Long periodId, AccountingPeriodCloseRequest request) {
        Company company = companyContextService.requireCurrentCompany();
        AccountingPeriod period = accountingPeriodRepository.findByCompanyAndId(company, periodId)
                .orElseThrow(() -> new IllegalArgumentException("Accounting period not found"));
        if (period.getStatus() == AccountingPeriodStatus.CLOSED) {
            return toDto(period);
        }
        boolean force = request != null && Boolean.TRUE.equals(request.force());
        if (!force) {
            assertChecklistComplete(company, period);
        }
        if (request != null && StringUtils.hasText(request.note())) {
            period.setChecklistNotes(request.note().trim());
        }
        period.setStatus(AccountingPeriodStatus.CLOSED);
        period.setClosedAt(Instant.now());
        period.setClosedBy(resolveCurrentUsername());
        AccountingPeriod saved = accountingPeriodRepository.save(period);
        ensurePeriod(company, period.getEndDate().plusDays(1));
        return toDto(saved);
    }

    @Transactional
    public AccountingPeriodDto confirmBankReconciliation(Long periodId, LocalDate referenceDate, String note) {
        Company company = companyContextService.requireCurrentCompany();
        AccountingPeriod period = resolvePeriod(company, periodId, referenceDate);
        period.setBankReconciled(true);
        period.setBankReconciledAt(Instant.now());
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
        period.setInventoryCounted(true);
        period.setInventoryCountedAt(Instant.now());
        period.setInventoryCountedBy(resolveCurrentUsername());
        if (StringUtils.hasText(note)) {
            period.setChecklistNotes(note.trim());
        }
        return toDto(accountingPeriodRepository.save(period));
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

    public AccountingPeriod requireOpenPeriod(Company company, LocalDate referenceDate, boolean overrideAuthorized) {
        AccountingPeriod period = ensurePeriod(company, referenceDate);
        if (period.getStatus() == AccountingPeriodStatus.CLOSED && !overrideAuthorized) {
            throw new IllegalArgumentException("Accounting period " + period.getLabel() + " is closed");
        }
        return period;
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
                    return accountingPeriodRepository.save(period);
                });
    }

    private AccountingPeriod resolvePeriod(Company company, Long periodId, LocalDate referenceDate) {
        if (periodId != null) {
            return accountingPeriodRepository.findByCompanyAndId(company, periodId)
                    .orElseThrow(() -> new IllegalArgumentException("Accounting period not found"));
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
            throw new IllegalStateException("Bank reconciliation has not been confirmed for this period");
        }
        if (!period.isInventoryCounted()) {
            throw new IllegalStateException("Inventory count has not been confirmed for this period");
        }
        long drafts = journalEntryRepository.countByCompanyAndEntryDateBetweenAndStatusIn(
                company,
                period.getStartDate(),
                period.getEndDate(),
                List.of("DRAFT", "PENDING"));
        if (drafts > 0) {
            throw new IllegalStateException("There are " + drafts + " draft entries in this period");
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
                period.getChecklistNotes()
        );
    }

    private LocalDate resolveCurrentDate(Company company) {
        String timezone = company.getTimezone() == null ? "UTC" : company.getTimezone();
        return LocalDate.now(ZoneId.of(timezone));
    }

    private AccountingPeriod resolvePeriod(Company company, Long periodId) {
        if (periodId != null) {
            return accountingPeriodRepository.findByCompanyAndId(company, periodId)
                    .orElseThrow(() -> new IllegalArgumentException("Accounting period not found"));
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
                        draftsCleared ? "All entries posted" : draftEntries + " draft entries remaining")
        );
        boolean ready = period.isBankReconciled() && period.isInventoryCounted() && draftsCleared;
        return new MonthEndChecklistDto(toDto(period), items, ready);
    }

    private String resolveCurrentUsername() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            return "system";
        }
        return authentication.getName();
    }
}
