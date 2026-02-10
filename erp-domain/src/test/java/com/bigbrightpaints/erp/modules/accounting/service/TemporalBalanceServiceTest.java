package com.bigbrightpaints.erp.modules.accounting.service;

import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountType;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriod;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriodRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriodSnapshot;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriodSnapshotRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriodStatus;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriodTrialBalanceLine;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriodTrialBalanceLineRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalLineRepository;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TemporalBalanceServiceTest {

    @Mock
    private AccountRepository accountRepository;
    @Mock
    private CompanyContextService companyContextService;
    @Mock
    private AccountingPeriodRepository accountingPeriodRepository;
    @Mock
    private AccountingPeriodSnapshotRepository snapshotRepository;
    @Mock
    private AccountingPeriodTrialBalanceLineRepository snapshotLineRepository;
    @Mock
    private JournalLineRepository journalLineRepository;
    @Mock
    private CompanyClock companyClock;

    private TemporalBalanceService temporalBalanceService;
    private Company company;

    @BeforeEach
    void setUp() {
        temporalBalanceService = new TemporalBalanceService(
                accountRepository,
                companyContextService,
                accountingPeriodRepository,
                snapshotRepository,
                snapshotLineRepository,
                journalLineRepository,
                companyClock
        );
        company = new Company();
        ReflectionTestUtils.setField(company, "id", 1L);
        when(companyContextService.requireCurrentCompany()).thenReturn(company);
    }

    @Test
    void getBalanceAsOfDate_normalizesCreditNormalBalanceForOpenPeriods() {
        LocalDate asOfDate = LocalDate.of(2026, 2, 10);
        Long accountId = 100L;

        Account liabilityAccount = new Account();
        liabilityAccount.setType(AccountType.LIABILITY);

        when(accountingPeriodRepository.findByCompanyAndYearAndMonth(eq(company), eq(2026), eq(2)))
                .thenReturn(Optional.empty());
        when(journalLineRepository.netBalanceUpTo(eq(company), eq(accountId), eq(asOfDate)))
                .thenReturn(new BigDecimal("-100.00"));
        when(accountRepository.findByCompanyAndId(eq(company), eq(accountId)))
                .thenReturn(Optional.of(liabilityAccount));

        BigDecimal balance = temporalBalanceService.getBalanceAsOfDate(accountId, asOfDate);

        assertThat(balance).isEqualByComparingTo("100.00");
    }

    @Test
    void getBalanceAsOfDate_normalizesCreditNormalBalanceForClosedPeriods() {
        LocalDate asOfDate = LocalDate.of(2026, 2, 9);
        Long accountId = 100L;

        AccountingPeriod closedPeriod = new AccountingPeriod();
        closedPeriod.setStatus(AccountingPeriodStatus.CLOSED);
        ReflectionTestUtils.setField(closedPeriod, "id", 11L);

        AccountingPeriodSnapshot snapshot = new AccountingPeriodSnapshot();
        ReflectionTestUtils.setField(snapshot, "id", 99L);

        AccountingPeriodTrialBalanceLine line = new AccountingPeriodTrialBalanceLine();
        line.setAccountId(accountId);
        line.setAccountType(AccountType.LIABILITY);
        line.setDebit(BigDecimal.ZERO);
        line.setCredit(new BigDecimal("100.00"));

        when(accountingPeriodRepository.findByCompanyAndYearAndMonth(eq(company), eq(2026), eq(2)))
                .thenReturn(Optional.of(closedPeriod));
        when(snapshotRepository.findByCompanyAndPeriod(eq(company), eq(closedPeriod)))
                .thenReturn(Optional.of(snapshot));
        when(snapshotLineRepository.findBySnapshotAndAccountId(eq(snapshot), eq(accountId)))
                .thenReturn(Optional.of(line));

        BigDecimal balance = temporalBalanceService.getBalanceAsOfDate(accountId, asOfDate);

        assertThat(balance).isEqualByComparingTo("100.00");
        verify(journalLineRepository, never()).netBalanceUpTo(eq(company), eq(accountId), eq(asOfDate));
    }
}
