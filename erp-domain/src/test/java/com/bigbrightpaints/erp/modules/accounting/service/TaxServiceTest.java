package com.bigbrightpaints.erp.modules.accounting.service;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalLine;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalLineRepository;
import com.bigbrightpaints.erp.modules.accounting.dto.GstReturnDto;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class TaxServiceTest {

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
    void setup() {
        taxService = new TaxService(companyContextService, companyAccountingSettingsService, companyClock, journalLineRepository);
        company = new Company();
        company.setCode("BBP");
        when(companyContextService.requireCurrentCompany()).thenReturn(company);
    }

    @Test
    void generateGstReturn_sumsOutputAndInputTax() {
        YearMonth period = YearMonth.of(2024, 1);
        LocalDate start = period.atDay(1);
        LocalDate end = period.atEndOfMonth();

        when(companyAccountingSettingsService.requireTaxAccounts())
                .thenReturn(new CompanyAccountingSettingsService.TaxAccountConfiguration(1L, 2L, 3L));

        when(journalLineRepository.findLinesForAccountBetween(company, 2L, start, end))
                .thenReturn(List.of(line(null, new BigDecimal("100.00")))); // output tax: credit - debit

        when(journalLineRepository.findLinesForAccountBetween(company, 1L, start, end))
                .thenReturn(List.of(line(new BigDecimal("60.00"), BigDecimal.ZERO))); // input tax: debit - credit

        GstReturnDto dto = taxService.generateGstReturn(period);

        assertThat(dto.getPeriod()).isEqualTo(period);
        assertThat(dto.getPeriodStart()).isEqualTo(start);
        assertThat(dto.getPeriodEnd()).isEqualTo(end);
        assertThat(dto.getOutputTax()).isEqualByComparingTo("100.00");
        assertThat(dto.getInputTax()).isEqualByComparingTo("60.00");
        assertThat(dto.getNetPayable()).isEqualByComparingTo("40.00");
    }

    @Test
    void generateGstReturn_roundsToCurrencyScale() {
        YearMonth period = YearMonth.of(2024, 2);
        LocalDate start = period.atDay(1);
        LocalDate end = period.atEndOfMonth();

        when(companyAccountingSettingsService.requireTaxAccounts())
                .thenReturn(new CompanyAccountingSettingsService.TaxAccountConfiguration(1L, 2L, 3L));

        when(journalLineRepository.findLinesForAccountBetween(company, 2L, start, end))
                .thenReturn(List.of(line(BigDecimal.ZERO, new BigDecimal("10.005"))));
        when(journalLineRepository.findLinesForAccountBetween(company, 1L, start, end))
                .thenReturn(List.of(line(new BigDecimal("2.005"), BigDecimal.ZERO)));

        GstReturnDto dto = taxService.generateGstReturn(period);

        assertThat(dto.getOutputTax()).isEqualByComparingTo("10.01");
        assertThat(dto.getInputTax()).isEqualByComparingTo("2.01");
        assertThat(dto.getNetPayable()).isEqualByComparingTo("8.00");
    }

    @Test
    void generateGstReturn_nonGstModeWithNoGstAccounts_returnsZeroes() {
        YearMonth period = YearMonth.of(2024, 3);
        company.setDefaultGstRate(BigDecimal.ZERO);
        company.setGstInputTaxAccountId(null);
        company.setGstOutputTaxAccountId(null);
        company.setGstPayableAccountId(null);

        GstReturnDto dto = taxService.generateGstReturn(period);

        assertThat(dto.getOutputTax()).isEqualByComparingTo("0.00");
        assertThat(dto.getInputTax()).isEqualByComparingTo("0.00");
        assertThat(dto.getNetPayable()).isEqualByComparingTo("0.00");
        verifyNoInteractions(companyAccountingSettingsService, journalLineRepository);
    }

    @Test
    void generateGstReturn_nonGstModeWithConfiguredGstAccounts_failsClosed() {
        YearMonth period = YearMonth.of(2024, 3);
        company.setDefaultGstRate(BigDecimal.ZERO);
        company.setGstInputTaxAccountId(1L);

        assertThatThrownBy(() -> taxService.generateGstReturn(period))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("Non-GST mode company cannot have GST tax accounts configured");
    }

    private JournalLine line(BigDecimal debit, BigDecimal credit) {
        JournalLine jl = new JournalLine();
        jl.setDebit(debit == null ? BigDecimal.ZERO : debit);
        jl.setCredit(credit == null ? BigDecimal.ZERO : credit);
        return jl;
    }
}
