package com.bigbrightpaints.erp.regression;

import com.bigbrightpaints.erp.core.security.CompanyContextHolder;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountType;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryRequest;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingService;
import com.bigbrightpaints.erp.modules.accounting.service.ReconciliationService;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.purchasing.domain.Supplier;
import com.bigbrightpaints.erp.modules.purchasing.domain.SupplierRepository;
import com.bigbrightpaints.erp.modules.reports.dto.BalanceSheetDto;
import com.bigbrightpaints.erp.modules.reports.dto.ProfitLossDto;
import com.bigbrightpaints.erp.modules.reports.service.ReportService;
import com.bigbrightpaints.erp.test.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Regression: Report sign conventions + AP reconciliation")
class AccountingReportSignConventionRegressionIT extends AbstractIntegrationTest {

    @Autowired private AccountRepository accountRepository;
    @Autowired private SupplierRepository supplierRepository;
    @Autowired private AccountingService accountingService;
    @Autowired private ReportService reportService;
    @Autowired private ReconciliationService reconciliationService;

    @AfterEach
    void clearContext() {
        CompanyContextHolder.clear();
    }

    @Test
    void balanceSheetProfitLossAndApReconciliationNormalizeSigns() {
        String companyCode = uniqueCompanyCode("LF-001");
        Fixture fixture = prepareCompany(companyCode);

        BigDecimal saleAmount = new BigDecimal("100.00");
        postJournalEntry("LF001-SALE-" + shortId(), null,
                line(fixture.cashAccount, saleAmount, BigDecimal.ZERO),
                line(fixture.revenueAccount, BigDecimal.ZERO, saleAmount));

        BigDecimal purchaseAmount = new BigDecimal("60.00");
        postJournalEntry("LF001-AP-" + shortId(), fixture.supplier.getId(),
                line(fixture.inventoryAccount, purchaseAmount, BigDecimal.ZERO),
                line(fixture.apAccount, BigDecimal.ZERO, purchaseAmount));

        BalanceSheetDto balanceSheet = reportService.balanceSheet();
        ProfitLossDto profitLoss = reportService.profitLoss();

        assertThat(balanceSheet.totalAssets()).isEqualByComparingTo(new BigDecimal("160.00"));
        assertThat(balanceSheet.totalLiabilities()).isEqualByComparingTo(new BigDecimal("60.00"));
        assertThat(balanceSheet.totalEquity()).isEqualByComparingTo(new BigDecimal("100.00"));

        assertThat(profitLoss.revenue()).isEqualByComparingTo(new BigDecimal("100.00"));
        assertThat(profitLoss.costOfGoodsSold()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(profitLoss.operatingExpenses()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(profitLoss.netIncome()).isEqualByComparingTo(new BigDecimal("100.00"));

        ReconciliationService.SupplierReconciliationResult apReconciliation =
                reconciliationService.reconcileApWithSupplierLedger();
        assertThat(apReconciliation.glApBalance()).isEqualByComparingTo(purchaseAmount);
        assertThat(apReconciliation.supplierLedgerTotal()).isEqualByComparingTo(purchaseAmount);
        assertThat(apReconciliation.variance()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(apReconciliation.isReconciled()).isTrue();
    }

    private Fixture prepareCompany(String companyCode) {
        Company company = dataSeeder.ensureCompany(companyCode, companyCode + " Ltd");
        CompanyContextHolder.setCompanyId(companyCode);

        Account cashAccount = ensureAccount(company, "CASH-" + companyCode, "Cash", AccountType.ASSET);
        Account inventoryAccount = ensureAccount(company, "INV-" + companyCode, "Inventory", AccountType.ASSET);
        Account revenueAccount = ensureAccount(company, "REV-" + companyCode, "Revenue", AccountType.REVENUE);
        Account apAccount = ensureAccount(company, "AP-" + companyCode, "Accounts Payable", AccountType.LIABILITY);

        Supplier supplier = new Supplier();
        supplier.setCompany(company);
        supplier.setCode("SUP-" + companyCode);
        supplier.setName("Supplier " + companyCode);
        supplier.setPayableAccount(apAccount);
        supplier = supplierRepository.save(supplier);

        return new Fixture(company, cashAccount, inventoryAccount, revenueAccount, apAccount, supplier);
    }

    private Account ensureAccount(Company company, String code, String name, AccountType type) {
        return accountRepository.findByCompanyAndCodeIgnoreCase(company, code)
                .orElseGet(() -> {
                    Account account = new Account();
                    account.setCompany(company);
                    account.setCode(code);
                    account.setName(name);
                    account.setType(type);
                    return accountRepository.save(account);
                });
    }

    private void postJournalEntry(String reference, Long supplierId,
                                  JournalEntryRequest.JournalLineRequest... lines) {
        accountingService.createJournalEntry(new JournalEntryRequest(
                reference,
                LocalDate.now(),
                "LF-001 regression",
                null,
                supplierId,
                false,
                List.of(lines)
        ));
    }

    private JournalEntryRequest.JournalLineRequest line(Account account, BigDecimal debit, BigDecimal credit) {
        return new JournalEntryRequest.JournalLineRequest(
                account.getId(),
                account.getCode(),
                debit,
                credit
        );
    }

    private String uniqueCompanyCode(String prefix) {
        return prefix + "-" + shortId();
    }

    private String shortId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    private record Fixture(Company company,
                           Account cashAccount,
                           Account inventoryAccount,
                           Account revenueAccount,
                           Account apAccount,
                           Supplier supplier) {}
}
