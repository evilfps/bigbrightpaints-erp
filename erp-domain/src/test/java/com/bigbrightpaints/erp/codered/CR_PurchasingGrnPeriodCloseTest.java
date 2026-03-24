package com.bigbrightpaints.erp.codered;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.security.CompanyContextHolder;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountType;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriod;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriodRepository;
import com.bigbrightpaints.erp.modules.accounting.dto.AccountingPeriodCloseRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.PeriodCloseRequestActionRequest;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingPeriodService;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingService;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.modules.purchasing.domain.GoodsReceipt;
import com.bigbrightpaints.erp.modules.purchasing.domain.GoodsReceiptRepository;
import com.bigbrightpaints.erp.modules.purchasing.domain.PurchaseOrder;
import com.bigbrightpaints.erp.modules.purchasing.domain.PurchaseOrderRepository;
import com.bigbrightpaints.erp.modules.purchasing.domain.Supplier;
import com.bigbrightpaints.erp.modules.purchasing.domain.SupplierRepository;
import com.bigbrightpaints.erp.test.AbstractIntegrationTest;
import com.bigbrightpaints.erp.test.support.TestDateUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
@Tag("critical")
@Tag("reconciliation")

class CR_PurchasingGrnPeriodCloseTest extends AbstractIntegrationTest {

    @Autowired private CompanyRepository companyRepository;
    @Autowired private SupplierRepository supplierRepository;
    @Autowired private PurchaseOrderRepository purchaseOrderRepository;
    @Autowired private GoodsReceiptRepository goodsReceiptRepository;
    @Autowired private AccountingPeriodService accountingPeriodService;
    @Autowired private AccountingPeriodRepository accountingPeriodRepository;
    @Autowired private AccountingService accountingService;
    @Autowired private AccountRepository accountRepository;

    @AfterEach
    void clearCompanyContext() {
        CompanyContextHolder.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    void grnWithoutInvoice_blocksPeriodClose_andPeriodRemainsOpen() {
        String companyCode = "CR-GRN-" + shortId();
        Company company = bootstrapCompany(companyCode);
        Supplier supplier = ensureSupplier(company);
        PurchaseOrder po = ensurePurchaseOrder(company, supplier);

        LocalDate receiptDate = TestDateUtils.safeDate(company).withDayOfMonth(1);
        GoodsReceipt grn = new GoodsReceipt();
        grn.setCompany(company);
        grn.setSupplier(supplier);
        grn.setPurchaseOrder(po);
        grn.setReceiptNumber("GRN-" + shortId());
        grn.setReceiptDate(receiptDate);
        grn.setStatus("RECEIVED");
        goodsReceiptRepository.save(grn);

        CompanyContextHolder.setCompanyId(companyCode);
        accountingPeriodService.listPeriods(); // ensure periods exist
        AccountingPeriod period = accountingPeriodRepository.findByCompanyAndYearAndMonth(
                company, receiptDate.getYear(), receiptDate.getMonthValue()).orElseThrow();

        authenticate("maker.user", "ROLE_ACCOUNTING");
        accountingPeriodService.requestPeriodClose(
                period.getId(),
                new PeriodCloseRequestActionRequest("CODE-RED force close request", true));
        authenticate("checker.user", "ROLE_ADMIN");

        assertThatThrownBy(() -> accountingPeriodService.approvePeriodClose(
                period.getId(),
                new PeriodCloseRequestActionRequest("CODE-RED force close approval", true)))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("Un-invoiced goods receipts exist in this period");

        AccountingPeriod reloaded = accountingPeriodRepository.findByCompanyAndId(company, period.getId()).orElseThrow();
        assertThat(reloaded.getStatus().name()).as("Period close fails closed").isEqualTo("OPEN");
    }

    @Test
    void closedPeriod_blocksNewJournalPosting() {
        String companyCode = "CR-PERIOD-CLOSE-" + shortId();
        Company company = bootstrapCompany(companyCode);

        Account bank = ensureAccount(company, "BANK", "Bank", AccountType.ASSET);
        Account expense = ensureAccount(company, "MISC-EXP", "Misc Expense", AccountType.EXPENSE);

        LocalDate today = TestDateUtils.safeDate(company);

        CompanyContextHolder.setCompanyId(companyCode);
        accountingPeriodService.listPeriods();
        AccountingPeriod period = accountingPeriodRepository.findByCompanyAndYearAndMonth(
                company, today.getYear(), today.getMonthValue()).orElseThrow();
        forceClosePeriod(period.getId(), "CODE-RED close request", "CODE-RED close approval");

        JournalEntryRequest sanitized = new JournalEntryRequest(
                null,
                today,
                "CODE-RED posting into closed period",
                null,
                null,
                false,
                List.of(
                        new JournalEntryRequest.JournalLineRequest(bank.getId(), "Dr", new BigDecimal("100.00"), BigDecimal.ZERO),
                        new JournalEntryRequest.JournalLineRequest(expense.getId(), "Cr", BigDecimal.ZERO, new BigDecimal("100.00"))
                )
        );

        assertThatThrownBy(() -> accountingService.createManualJournalEntry(sanitized, "MANUAL-" + UUID.randomUUID()))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("locked/closed");
    }

    private Company bootstrapCompany(String companyCode) {
        dataSeeder.ensureCompany(companyCode, companyCode + " Ltd");
        CompanyContextHolder.setCompanyId(companyCode);
        Company company = companyRepository.findByCodeIgnoreCase(companyCode).orElseThrow();
        company.setTimezone("UTC");
        company.setBaseCurrency("INR");
        return companyRepository.save(company);
    }

    private Supplier ensureSupplier(Company company) {
        return supplierRepository.findByCompanyAndCodeIgnoreCase(company, "CR-SUP")
                .orElseGet(() -> {
                    Supplier supplier = new Supplier();
                    supplier.setCompany(company);
                    supplier.setCode("CR-SUP");
                    supplier.setName("Code-Red Supplier");
                    supplier.setStatus("ACTIVE");
                    return supplierRepository.save(supplier);
                });
    }

    private PurchaseOrder ensurePurchaseOrder(Company company, Supplier supplier) {
        return purchaseOrderRepository.findByCompanyAndOrderNumberIgnoreCase(company, "CR-PO")
                .orElseGet(() -> {
                    PurchaseOrder po = new PurchaseOrder();
                    po.setCompany(company);
                    po.setSupplier(supplier);
                    po.setOrderNumber("CR-PO");
                    po.setOrderDate(TestDateUtils.safeDate(company).withDayOfMonth(1));
                    po.setStatus("OPEN");
                    return purchaseOrderRepository.save(po);
                });
    }

    private Account ensureAccount(Company company, String code, String name, AccountType type) {
        return accountRepository.findByCompanyAndCodeIgnoreCase(company, code)
                .orElseGet(() -> {
                    Account account = new Account();
                    account.setCompany(company);
                    account.setCode(code);
                    account.setName(name);
                    account.setType(type);
                    account.setActive(true);
                    account.setBalance(BigDecimal.ZERO);
                    return accountRepository.save(account);
                });
    }

    private static String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private void forceClosePeriod(Long periodId, String requestNote, String approvalNote) {
        authenticate("maker.user", "ROLE_ACCOUNTING");
        accountingPeriodService.requestPeriodClose(periodId, new PeriodCloseRequestActionRequest(requestNote, true));
        authenticate("checker.user", "ROLE_ADMIN");
        accountingPeriodService.approvePeriodClose(periodId, new PeriodCloseRequestActionRequest(approvalNote, true));
    }

    private void authenticate(String username, String... roles) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        username,
                        "N/A",
                        java.util.Arrays.stream(roles)
                                .map(SimpleGrantedAuthority::new)
                                .toList()
                )
        );
    }
}
