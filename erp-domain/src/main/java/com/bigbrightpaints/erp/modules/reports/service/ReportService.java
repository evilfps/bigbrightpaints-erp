package com.bigbrightpaints.erp.modules.reports.service;

import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialRepository;
import com.bigbrightpaints.erp.modules.reports.dto.*;
import com.bigbrightpaints.erp.modules.sales.domain.DealerRepository;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrderRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Service
public class ReportService {

    private final CompanyContextService companyContextService;
    private final AccountRepository accountRepository;
    private final RawMaterialRepository rawMaterialRepository;
    private final DealerRepository dealerRepository;
    private final SalesOrderRepository salesOrderRepository;

    public ReportService(CompanyContextService companyContextService,
                         AccountRepository accountRepository,
                         RawMaterialRepository rawMaterialRepository,
                         DealerRepository dealerRepository,
                         SalesOrderRepository salesOrderRepository) {
        this.companyContextService = companyContextService;
        this.accountRepository = accountRepository;
        this.rawMaterialRepository = rawMaterialRepository;
        this.dealerRepository = dealerRepository;
        this.salesOrderRepository = salesOrderRepository;
    }

    public BalanceSheetDto balanceSheet() {
        Company company = companyContextService.requireCurrentCompany();
        BigDecimal assets = aggregateAccountType(company, "ASSET");
        BigDecimal liabilities = aggregateAccountType(company, "LIABILITY");
        BigDecimal equity = assets.subtract(liabilities);
        return new BalanceSheetDto(assets, liabilities, equity);
    }

    public ProfitLossDto profitLoss() {
        Company company = companyContextService.requireCurrentCompany();
        BigDecimal revenue = aggregateAccountType(company, "REVENUE");
        BigDecimal cogs = aggregateAccountType(company, "COGS");
        BigDecimal grossProfit = revenue.subtract(cogs);
        BigDecimal expenses = aggregateAccountType(company, "EXPENSE");
        BigDecimal netIncome = grossProfit.subtract(expenses);
        return new ProfitLossDto(revenue, cogs, grossProfit, expenses, netIncome);
    }

    public CashFlowDto cashFlow() {
        BigDecimal operating = new BigDecimal("250000");
        BigDecimal investing = new BigDecimal("-50000");
        BigDecimal financing = new BigDecimal("-20000");
        return new CashFlowDto(operating, investing, financing, operating.add(investing).add(financing));
    }

    public InventoryValuationDto inventoryValuation() {
        Company company = companyContextService.requireCurrentCompany();
        var materials = rawMaterialRepository.findByCompanyOrderByNameAsc(company);
        BigDecimal totalValue = materials.stream()
                .map(m -> m.getCurrentStock())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        long lowStock = materials.stream().filter(m -> m.getCurrentStock().compareTo(m.getReorderLevel()) < 0).count();
        return new InventoryValuationDto(totalValue, lowStock);
    }

    public List<AccountStatementEntryDto> accountStatement() {
        Company company = companyContextService.requireCurrentCompany();
        return dealerRepository.findByCompanyOrderByNameAsc(company).stream()
                .map(dealer -> new AccountStatementEntryDto(dealer.getName(), LocalDate.now(),
                        "SO-" + dealer.getCode(), dealer.getOutstandingBalance(), BigDecimal.ZERO,
                        dealer.getOutstandingBalance()))
                .toList();
    }

    public List<AgedDebtorDto> agedDebtors() {
        Company company = companyContextService.requireCurrentCompany();
        return dealerRepository.findByCompanyOrderByNameAsc(company).stream()
                .map(dealer -> new AgedDebtorDto(dealer.getName(),
                        dealer.getOutstandingBalance().multiply(new BigDecimal("0.6")),
                        dealer.getOutstandingBalance().multiply(new BigDecimal("0.2")),
                        dealer.getOutstandingBalance().multiply(new BigDecimal("0.15")),
                        dealer.getOutstandingBalance().multiply(new BigDecimal("0.05"))))
                .toList();
    }

    private BigDecimal aggregateAccountType(Company company, String type) {
        return accountRepository.findByCompanyOrderByCodeAsc(company).stream()
                .filter(acc -> type.equalsIgnoreCase(acc.getType()))
                .map(acc -> acc.getBalance())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
