package com.bigbrightpaints.erp.modules.accounting.service;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.core.util.MoneyUtils;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalLine;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalLineRepository;
import com.bigbrightpaints.erp.modules.accounting.dto.GstReturnDto;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

@Service
@Transactional(Transactional.TxType.SUPPORTS)
public class TaxService {

    private final CompanyContextService companyContextService;
    private final CompanyAccountingSettingsService companyAccountingSettingsService;
    private final CompanyClock companyClock;
    private final JournalLineRepository journalLineRepository;

    public TaxService(CompanyContextService companyContextService,
                      CompanyAccountingSettingsService companyAccountingSettingsService,
                      CompanyClock companyClock,
                      JournalLineRepository journalLineRepository) {
        this.companyContextService = companyContextService;
        this.companyAccountingSettingsService = companyAccountingSettingsService;
        this.companyClock = companyClock;
        this.journalLineRepository = journalLineRepository;
    }

    public GstReturnDto generateGstReturn(YearMonth period) {
        Company company = companyContextService.requireCurrentCompany();
        YearMonth target = period != null ? period : YearMonth.from(companyClock.today(company));
        if (period != null) {
            YearMonth currentPeriod = YearMonth.from(companyClock.today(company));
            if (target.isAfter(currentPeriod)) {
                throw new ApplicationException(
                        ErrorCode.VALIDATION_INVALID_DATE,
                        "GST return period cannot be in the future")
                        .withDetail("requestedPeriod", target.toString())
                        .withDetail("currentPeriod", currentPeriod.toString());
            }
        }
        LocalDate start = target.atDay(1);
        LocalDate end = target.atEndOfMonth();

        if (isNonGstMode(company)) {
            ensureNonGstCompanyDoesNotCarryGstAccounts(company);
            return buildGstReturn(target, start, end, BigDecimal.ZERO, BigDecimal.ZERO);
        }

        var taxConfig = companyAccountingSettingsService.requireTaxAccounts();

        BigDecimal outputTaxBalance = sumTax(company, taxConfig.outputTaxAccountId(), start, end, true);
        BigDecimal inputTaxBalance = sumTax(company, taxConfig.inputTaxAccountId(), start, end, false);

        BigDecimal outputTax = MoneyUtils.roundCurrency(
                positivePortion(outputTaxBalance).add(positivePortion(inputTaxBalance.negate())));
        BigDecimal inputTax = MoneyUtils.roundCurrency(
                positivePortion(inputTaxBalance).add(positivePortion(outputTaxBalance.negate())));

        return buildGstReturn(target, start, end, outputTax, inputTax);
    }

    private BigDecimal sumTax(Company company, Long accountId, LocalDate start, LocalDate end, boolean outputTax) {
        if (accountId == null) {
            return BigDecimal.ZERO;
        }
        List<JournalLine> lines = journalLineRepository.findLinesForAccountBetween(company, accountId, start, end);
        if (lines == null || lines.isEmpty()) {
            return BigDecimal.ZERO;
        }
        BigDecimal total = BigDecimal.ZERO;
        for (JournalLine line : lines) {
            BigDecimal debit = safe(line.getDebit());
            BigDecimal credit = safe(line.getCredit());
            BigDecimal delta = outputTax ? credit.subtract(debit) : debit.subtract(credit);
            total = total.add(delta);
        }
        return total;
    }

    private BigDecimal safe(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private BigDecimal positivePortion(BigDecimal value) {
        return value.compareTo(BigDecimal.ZERO) > 0 ? value : BigDecimal.ZERO;
    }

    private boolean isNonGstMode(Company company) {
        BigDecimal defaultGstRate = company.getDefaultGstRate();
        return defaultGstRate != null && defaultGstRate.compareTo(BigDecimal.ZERO) == 0;
    }

    private void ensureNonGstCompanyDoesNotCarryGstAccounts(Company company) {
        List<String> configured = new ArrayList<>();
        if (company.getGstInputTaxAccountId() != null) {
            configured.add("gstInputTaxAccountId");
        }
        if (company.getGstOutputTaxAccountId() != null) {
            configured.add("gstOutputTaxAccountId");
        }
        if (company.getGstPayableAccountId() != null) {
            configured.add("gstPayableAccountId");
        }
        if (configured.isEmpty()) {
            return;
        }
        throw new ApplicationException(
                ErrorCode.VALIDATION_INVALID_INPUT,
                "Non-GST mode company cannot have GST tax accounts configured")
                .withDetail("configured", configured);
    }

    private GstReturnDto buildGstReturn(YearMonth period,
                                        LocalDate periodStart,
                                        LocalDate periodEnd,
                                        BigDecimal outputTax,
                                        BigDecimal inputTax) {
        GstReturnDto dto = new GstReturnDto();
        dto.setPeriod(period);
        dto.setPeriodStart(periodStart);
        dto.setPeriodEnd(periodEnd);
        dto.setOutputTax(outputTax);
        dto.setInputTax(inputTax);
        dto.setNetPayable(MoneyUtils.roundCurrency(outputTax.subtract(inputTax)));
        return dto;
    }

}
