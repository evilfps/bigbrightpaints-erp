package com.bigbrightpaints.erp.truthsuite.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bigbrightpaints.erp.core.audit.AuditService;
import com.bigbrightpaints.erp.core.service.NumberSequenceService;
import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.core.util.CompanyEntityLookup;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountType;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingFacade;
import com.bigbrightpaints.erp.modules.accounting.service.CompanyDefaultAccountsService;
import com.bigbrightpaints.erp.modules.accounting.service.ReferenceNumberService;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGood;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterial;
import com.bigbrightpaints.erp.modules.inventory.service.BatchNumberService;
import com.bigbrightpaints.erp.modules.invoice.domain.Invoice;
import com.bigbrightpaints.erp.modules.invoice.service.InvoiceSettlementPolicy;
import com.bigbrightpaints.erp.modules.purchasing.domain.Supplier;
import com.bigbrightpaints.erp.modules.sales.domain.Dealer;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

@Tag("concurrency")
@Tag("reconciliation")
class TS_RuntimeModuleExecutableCoverageTest {

    @Test
    void referenceNumbers_cover_all_reference_categories_and_length_guards() {
        NumberSequenceService numberSequenceService = mock(NumberSequenceService.class);
        AuditService auditService = mock(AuditService.class);
        CompanyClock companyClock = mock(CompanyClock.class);
        when(numberSequenceService.nextValue(any(Company.class), any(String.class)))
                .thenReturn(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L, 11L, 12L);
        when(companyClock.today(any(Company.class))).thenReturn(LocalDate.of(2026, 2, 16));

        ReferenceNumberService service = new ReferenceNumberService(numberSequenceService, auditService, companyClock);
        Company company = company("TRUTH", "Asia/Kolkata");

        Dealer dealer = new Dealer();
        dealer.setCode("dealer-001");

        Supplier supplier = new Supplier();
        supplier.setCode("supplier-001");

        assertThat(service.nextJournalReference(company)).startsWith("JRN-TRUTH-");
        assertThat(service.dealerReceiptReference(company, dealer)).startsWith("RCPT-DEALER-001-");
        assertThat(service.salesOrderReference(company, "so/2026/001")).startsWith("SALE-SO2026001-");
        assertThat(service.supplierPaymentReference(company, supplier)).startsWith("SUP-SUPPLIER-001-");
        assertThat(service.payrollPaymentReference(company)).startsWith("PAYROLL-");
        assertThat(service.rawMaterialReceiptReference(company, "rm:batch/1")).startsWith("RM-RMBATCH1-");
        assertThat(service.costAllocationReference(company)).startsWith("COST-ALLOC-");
        assertThat(service.invoiceJournalReference(company)).startsWith("INVJ-TRUTH-");

        String key = service.purchaseReferenceKey(company, supplier,
                "INV-THIS-IS-A-VERY-LONG-INVOICE-NUMBER-TO-TRIGGER-COMPACTION-AND-HASH");
        assertThat(key.length()).isLessThanOrEqualTo(59);
        assertThat(service.purchaseReference(company, supplier, "INV-001")).startsWith("RMP-");
        assertThat(service.purchaseReturnReference(company, supplier)).startsWith("PRN-TRUTH-");
        assertThat(service.inventoryAdjustmentReference(company, "damaged-return")).startsWith("ADJ-TRUTH-");
        assertThat(service.openingStockReference(company)).startsWith("OPEN-STOCK-TRUTH-");
        assertThat(service.reversalReference("INV-ABC-0001")).isEqualTo("INV-ABC-0001-REV");

        verify(numberSequenceService, atLeast(10)).nextValue(any(Company.class), any(String.class));
    }

    @Test
    void companyDefaultAccountsService_validates_account_types_and_requires_defaults() {
        Company company = company("DEF", "UTC");
        CompanyContextService companyContextService = mock(CompanyContextService.class);
        CompanyEntityLookup companyEntityLookup = mock(CompanyEntityLookup.class);
        CompanyRepository companyRepository = mock(CompanyRepository.class);
        when(companyContextService.requireCurrentCompany()).thenReturn(company);
        when(companyRepository.save(any(Company.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Account inventory = account(11L, company, "INV", AccountType.ASSET);
        Account cogs = account(12L, company, "COGS", AccountType.COGS);
        Account revenue = account(13L, company, "REV", AccountType.REVENUE);
        Account discountExpense = account(14L, company, "DISC", AccountType.EXPENSE);
        Account tax = account(15L, company, "GST-OUT", AccountType.LIABILITY);

        when(companyEntityLookup.requireAccount(company, 11L)).thenReturn(inventory);
        when(companyEntityLookup.requireAccount(company, 12L)).thenReturn(cogs);
        when(companyEntityLookup.requireAccount(company, 13L)).thenReturn(revenue);
        when(companyEntityLookup.requireAccount(company, 14L)).thenReturn(discountExpense);
        when(companyEntityLookup.requireAccount(company, 15L)).thenReturn(tax);

        CompanyDefaultAccountsService service =
                new CompanyDefaultAccountsService(companyContextService, companyEntityLookup, companyRepository);

        CompanyDefaultAccountsService.DefaultAccounts updated =
                service.updateDefaults(11L, 12L, 13L, 14L, 15L);
        assertThat(updated.inventoryAccountId()).isEqualTo(11L);
        assertThat(updated.cogsAccountId()).isEqualTo(12L);
        assertThat(updated.revenueAccountId()).isEqualTo(13L);
        assertThat(updated.discountAccountId()).isEqualTo(14L);
        assertThat(updated.taxAccountId()).isEqualTo(15L);

        CompanyDefaultAccountsService.DefaultAccounts required = service.requireDefaults();
        assertThat(required.inventoryAccountId()).isEqualTo(11L);

        company.setDefaultTaxAccountId(null);
        assertThatThrownBy(service::requireDefaults)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("default accounts are not configured");

        Account invalidTax = account(99L, company, "BAD-TAX", AccountType.ASSET);
        when(companyEntityLookup.requireAccount(company, 99L)).thenReturn(invalidTax);
        assertThatThrownBy(() -> service.updateDefaults(null, null, null, null, 99L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("expected type LIABILITY");
    }

    @Test
    void batchNumberService_generates_rm_fg_and_packing_identifiers() {
        NumberSequenceService numberSequenceService = mock(NumberSequenceService.class);
        when(numberSequenceService.nextValue(any(Company.class), any(String.class)))
                .thenReturn(1L, 2L, 3L, 4L, 5L, 6L);

        BatchNumberService service = new BatchNumberService(numberSequenceService);
        Company company = company("BT", "Asia/Kolkata");

        RawMaterial material = new RawMaterial();
        material.setCompany(company);
        material.setSku("RM-01");

        FinishedGood finishedGood = new FinishedGood();
        finishedGood.setCompany(company);
        finishedGood.setProductCode("FG-01");

        String rmBatch = service.nextRawMaterialBatchCode(material);
        String fgBatch = service.nextFinishedGoodBatchCode(finishedGood, LocalDate.of(2026, 2, 1));
        String slip = service.nextPackagingSlipNumber(company);

        assertThat(rmBatch).startsWith("RM-RM01-");
        assertThat(fgBatch).startsWith("BT-FG-FG01-202602-");
        assertThat(slip).startsWith("BT-PS-");

        RawMaterial materialWithoutSku = new RawMaterial();
        materialWithoutSku.setCompany(company);
        ReflectionTestUtils.setField(materialWithoutSku, "id", 77L);

        Company utcFallbackCompany = company("BTZ", null);
        FinishedGood finishedGoodWithoutSku = new FinishedGood();
        finishedGoodWithoutSku.setCompany(utcFallbackCompany);
        finishedGoodWithoutSku.setProductCode("   ");

        String rmFallback = service.nextRawMaterialBatchCode(materialWithoutSku);
        String fgFallback = service.nextFinishedGoodBatchCode(finishedGoodWithoutSku, null);
        String utcSlip = service.nextPackagingSlipNumber(utcFallbackCompany);

        assertThat(rmFallback).contains("RM-77-");
        assertThat(fgFallback).contains("-FG-ITEM-");
        assertThat(utcSlip).startsWith("BTZ-PS-");
    }

    @Test
    void invoiceSettlementPolicy_enforces_status_transitions_and_idempotent_replay() {
        InvoiceSettlementPolicy policy = new InvoiceSettlementPolicy();
        Invoice invoice = new Invoice();
        invoice.setStatus("DRAFT");
        invoice.setTotalAmount(new BigDecimal("100.00"));
        invoice.setOutstandingAmount(new BigDecimal("100.00"));
        invoice.setDueDate(LocalDate.of(2026, 2, 1));

        policy.ensureIssuable(invoice);
        assertThat(invoice.getStatus()).isEqualTo("ISSUED");

        policy.applyPayment(invoice, new BigDecimal("40.00"), "PAY-001");
        assertThat(invoice.getOutstandingAmount()).isEqualTo(new BigDecimal("60.00"));
        assertThat(invoice.getStatus()).isEqualTo("PARTIAL");

        policy.applyPayment(invoice, new BigDecimal("40.00"), "PAY-001");
        assertThat(invoice.getOutstandingAmount()).isEqualTo(new BigDecimal("60.00"));

        policy.applySettlement(invoice, new BigDecimal("10.00"), "SET-001");
        assertThat(invoice.getOutstandingAmount()).isEqualTo(new BigDecimal("50.00"));

        policy.reversePayment(invoice, new BigDecimal("10.00"), "SET-001");
        assertThat(invoice.getOutstandingAmount()).isEqualTo(new BigDecimal("60.00"));

        assertThat(policy.isPastDue(invoice, LocalDate.of(2026, 2, 2))).isTrue();

        policy.voidInvoice(invoice);
        assertThat(invoice.getStatus()).isEqualTo("VOID");

        assertThatThrownBy(() -> policy.applyPayment(invoice, new BigDecimal("1.00"), "X"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot pay a void invoice");

        Invoice bad = new Invoice();
        bad.setStatus("ISSUED");
        bad.setTotalAmount(new BigDecimal("10.00"));
        bad.setOutstandingAmount(new BigDecimal("10.00"));
        assertThatThrownBy(() -> policy.applyPayment(bad, BigDecimal.ZERO, "P"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid payment amount");
        assertThatThrownBy(() -> policy.applyPayment(bad, new BigDecimal("1.00"), " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Payment reference is required");
    }

    @Test
    void accountingFacade_reserved_namespace_rule_is_mismatch_safe() {
        assertThat(AccountingFacade.isReservedReferenceNamespace("INV-123")).isTrue();
        assertThat(AccountingFacade.isReservedReferenceNamespace("RMP-123")).isTrue();
        assertThat(AccountingFacade.isReservedReferenceNamespace("MANUAL-INV-123")).isFalse();
        assertThat(AccountingFacade.isReservedReferenceNamespace("custom-ref")).isFalse();
    }

    private Company company(String code, String timezone) {
        Company company = new Company();
        company.setCode(code);
        company.setName(code + " Ltd");
        company.setTimezone(timezone);
        ReflectionTestUtils.setField(company, "id", 1L);
        return company;
    }

    private Account account(Long id, Company company, String code, AccountType type) {
        Account account = new Account();
        ReflectionTestUtils.setField(account, "id", id);
        account.setCompany(company);
        account.setCode(code);
        account.setName(code + " account");
        account.setType(type);
        return account;
    }
}
