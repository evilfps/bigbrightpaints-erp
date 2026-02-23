package com.bigbrightpaints.erp.truthsuite.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalLine;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalLineRepository;
import com.bigbrightpaints.erp.modules.accounting.dto.GstReturnDto;
import com.bigbrightpaints.erp.modules.accounting.service.CompanyAccountingSettingsService;
import com.bigbrightpaints.erp.modules.accounting.service.TaxService;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@Tag("critical")
@Tag("reconciliation")
class TS_RuntimeTaxServiceExecutableCoverageTest {

    @Mock
    private CompanyContextService companyContextService;
    @Mock
    private CompanyAccountingSettingsService companyAccountingSettingsService;
    @Mock
    private CompanyClock companyClock;
    @Mock
    private JournalLineRepository journalLineRepository;

    private TaxService taxService;
    private Company company;

    @BeforeEach
    void setUp() {
        taxService = new TaxService(companyContextService, companyAccountingSettingsService, companyClock, journalLineRepository);
        company = new Company();
        company.setCode("TRUTH");
        when(companyContextService.requireCurrentCompany()).thenReturn(company);
        when(companyClock.today(company)).thenReturn(LocalDate.of(2026, 2, 15));
    }

    @Test
    void generateGstReturn_computesOutputInputAndNetDeterministically() {
        YearMonth period = YearMonth.of(2026, 1);
        LocalDate start = period.atDay(1);
        LocalDate end = period.atEndOfMonth();

        when(companyAccountingSettingsService.requireTaxAccounts())
                .thenReturn(new CompanyAccountingSettingsService.TaxAccountConfiguration(10L, 20L, 30L));
        when(journalLineRepository.findLinesForAccountBetween(company, 20L, start, end))
                .thenReturn(List.of(line(BigDecimal.ZERO, new BigDecimal("100.00"))));
        when(journalLineRepository.findLinesForAccountBetween(company, 10L, start, end))
                .thenReturn(List.of(line(new BigDecimal("60.00"), BigDecimal.ZERO)));

        GstReturnDto dto = taxService.generateGstReturn(period);

        assertThat(dto.getOutputTax()).isEqualByComparingTo("100.00");
        assertThat(dto.getInputTax()).isEqualByComparingTo("60.00");
        assertThat(dto.getNetPayable()).isEqualByComparingTo("40.00");
    }

    @Test
    void generateGstReturn_nonGstModeFailsClosedWhenGstAccountsConfigured() {
        company.setDefaultGstRate(BigDecimal.ZERO);
        company.setGstInputTaxAccountId(999L);

        assertThatThrownBy(() -> taxService.generateGstReturn(YearMonth.of(2026, 2)))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("Non-GST mode company cannot have GST tax accounts configured");

        verifyNoInteractions(companyAccountingSettingsService, journalLineRepository);
    }

    private static JournalLine line(BigDecimal debit, BigDecimal credit) {
        JournalLine line = new JournalLine();
        line.setDebit(debit);
        line.setCredit(credit);
        return line;
    }
}
