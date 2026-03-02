package com.bigbrightpaints.erp.modules.reports.service;

import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountType;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriodTrialBalanceLineRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalLineRepository;
import com.bigbrightpaints.erp.modules.reports.dto.BalanceSheetDto;
import com.bigbrightpaints.erp.modules.reports.dto.ReportMetadata;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BalanceSheetReportQueryServiceTest {

    @Mock
    private ReportQuerySupport reportQuerySupport;
    @Mock
    private AccountingPeriodTrialBalanceLineRepository snapshotLineRepository;
    @Mock
    private AccountRepository accountRepository;
    @Mock
    private JournalLineRepository journalLineRepository;

    @Test
    void generate_groupsCurrentAndLongTermSectionsAndValidatesEquation() {
        BalanceSheetReportQueryService service = new BalanceSheetReportQueryService(
                reportQuerySupport,
                snapshotLineRepository,
                accountRepository,
                journalLineRepository
        );

        ReportQuerySupport.FinancialQueryWindow primary = ReportFixtures.window(
                LocalDate.of(2026, 3, 1),
                LocalDate.of(2026, 3, 31),
                LocalDate.of(2026, 3, 31)
        );

        FinancialReportQueryRequest request = new FinancialReportQueryRequest(
                null,
                primary.startDate(),
                primary.endDate(),
                null,
                null,
                null,
                null,
                null,
                "CSV"
        );

        when(reportQuerySupport.resolveWindow(request)).thenReturn(primary);
        when(reportQuerySupport.resolveComparison(request)).thenReturn(null);
        when(reportQuerySupport.metadata(primary)).thenReturn(new ReportMetadata(
                primary.asOfDate(),
                primary.startDate(),
                primary.endDate(),
                primary.source(),
                null,
                null,
                null,
                true,
                true,
                "CSV"
        ));

        Account cash = account(1L, "CASH", "Cash", AccountType.ASSET);
        Account machinery = account(2L, "FIXED-ASSETS", "Fixed Assets", AccountType.ASSET);
        Account payable = account(3L, "AP", "Accounts Payable", AccountType.LIABILITY);
        Account loan = account(4L, "LONG-TERM-BORROWINGS", "Long-Term Borrowings", AccountType.LIABILITY);
        Account equity = account(5L, "RETAINED-EARNINGS", "Retained Earnings", AccountType.EQUITY);
        when(accountRepository.findByCompanyOrderByCodeAsc(primary.company())).thenReturn(List.of(cash, machinery, payable, loan, equity));

        when(journalLineRepository.summarizeByAccountWithin(primary.company(), primary.startDate(), primary.endDate()))
                .thenReturn(List.of(
                        row(1L, "1200.00", "0.00"),
                        row(2L, "800.00", "0.00"),
                        row(3L, "0.00", "300.00"),
                        row(4L, "0.00", "500.00"),
                        row(5L, "0.00", "1200.00")
                ));

        BalanceSheetDto dto = service.generate(request);

        assertThat(dto.totalAssets()).isEqualByComparingTo("2000.00");
        assertThat(dto.totalLiabilities()).isEqualByComparingTo("800.00");
        assertThat(dto.totalEquity()).isEqualByComparingTo("1200.00");
        assertThat(dto.balanced()).isTrue();

        assertThat(dto.currentAssets()).extracting(BalanceSheetDto.SectionLine::accountCode)
                .containsExactly("CASH");
        assertThat(dto.fixedAssets()).extracting(BalanceSheetDto.SectionLine::accountCode)
                .containsExactly("FIXED-ASSETS");
        assertThat(dto.currentLiabilities()).extracting(BalanceSheetDto.SectionLine::accountCode)
                .containsExactly("AP");
        assertThat(dto.longTermLiabilities()).extracting(BalanceSheetDto.SectionLine::accountCode)
                .containsExactly("LONG-TERM-BORROWINGS");
        assertThat(dto.equityLines()).extracting(BalanceSheetDto.SectionLine::accountCode)
                .containsExactly("RETAINED-EARNINGS");
    }

    private Account account(Long id, String code, String name, AccountType type) {
        Account account = new Account();
        ReflectionTestUtils.setField(account, "id", id);
        account.setCode(code);
        account.setName(name);
        account.setType(type);
        return account;
    }

    private Object[] row(Long accountId, String debit, String credit) {
        return new Object[]{accountId, new BigDecimal(debit), new BigDecimal(credit)};
    }
}
