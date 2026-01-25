package com.bigbrightpaints.erp.modules.accounting.service;

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
        LocalDate start = target.atDay(1);
        LocalDate end = target.atEndOfMonth();

        var taxConfig = companyAccountingSettingsService.requireTaxAccounts();

        BigDecimal outputTax = MoneyUtils.roundCurrency(sumTax(company, taxConfig.outputTaxAccountId(), start, end, true));
        BigDecimal inputTax = MoneyUtils.roundCurrency(sumTax(company, taxConfig.inputTaxAccountId(), start, end, false));

        GstReturnDto dto = new GstReturnDto();
        dto.setPeriod(target);
        dto.setPeriodStart(start);
        dto.setPeriodEnd(end);
        dto.setOutputTax(outputTax);
        dto.setInputTax(inputTax);
        dto.setNetPayable(MoneyUtils.roundCurrency(outputTax.subtract(inputTax)));
        return dto;
    }

    private BigDecimal sumTax(Company company, Long accountId, LocalDate start, LocalDate end, boolean outputTax) {
        if (accountId == null) {
            return BigDecimal.ZERO;
        }
        List<JournalLine> lines = journalLineRepository.findLinesForAccountBetween(company, accountId, start, end);
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

}
