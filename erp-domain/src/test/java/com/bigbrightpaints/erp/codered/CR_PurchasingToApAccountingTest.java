package com.bigbrightpaints.erp.codered;

import com.bigbrightpaints.erp.codered.support.CoderedConcurrencyHarness;
import com.bigbrightpaints.erp.codered.support.CoderedDbAssertions;
import com.bigbrightpaints.erp.core.security.CompanyContextHolder;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountType;
import com.bigbrightpaints.erp.modules.accounting.domain.GstRegistrationType;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryRepository;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryReference;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterial;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialMovementRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialRepository;
import com.bigbrightpaints.erp.modules.purchasing.domain.GoodsReceipt;
import com.bigbrightpaints.erp.modules.purchasing.domain.GoodsReceiptRepository;
import com.bigbrightpaints.erp.modules.purchasing.domain.PurchaseOrder;
import com.bigbrightpaints.erp.modules.purchasing.domain.PurchaseOrderRepository;
import com.bigbrightpaints.erp.modules.purchasing.domain.RawMaterialPurchase;
import com.bigbrightpaints.erp.modules.purchasing.domain.RawMaterialPurchaseRepository;
import com.bigbrightpaints.erp.modules.purchasing.domain.Supplier;
import com.bigbrightpaints.erp.modules.purchasing.domain.SupplierRepository;
import com.bigbrightpaints.erp.modules.purchasing.dto.GoodsReceiptLineRequest;
import com.bigbrightpaints.erp.modules.purchasing.dto.GoodsReceiptRequest;
import com.bigbrightpaints.erp.modules.purchasing.dto.GoodsReceiptResponse;
import com.bigbrightpaints.erp.modules.purchasing.dto.PurchaseOrderLineRequest;
import com.bigbrightpaints.erp.modules.purchasing.dto.PurchaseOrderRequest;
import com.bigbrightpaints.erp.modules.purchasing.dto.PurchaseOrderResponse;
import com.bigbrightpaints.erp.modules.purchasing.dto.RawMaterialPurchaseLineRequest;
import com.bigbrightpaints.erp.modules.purchasing.dto.RawMaterialPurchaseRequest;
import com.bigbrightpaints.erp.modules.purchasing.dto.RawMaterialPurchaseResponse;
import com.bigbrightpaints.erp.modules.purchasing.service.PurchasingService;
import com.bigbrightpaints.erp.modules.production.dto.CatalogImportResponse;
import com.bigbrightpaints.erp.modules.production.service.ProductionCatalogService;
import com.bigbrightpaints.erp.modules.accounting.service.ReconciliationService;
import com.bigbrightpaints.erp.test.AbstractIntegrationTest;
import com.bigbrightpaints.erp.test.support.TestDateUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("critical")
@Tag("concurrency")
@Tag("reconciliation")
class CR_PurchasingToApAccountingTest extends AbstractIntegrationTest {

    @Autowired private CompanyRepository companyRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private SupplierRepository supplierRepository;
    @Autowired private RawMaterialRepository rawMaterialRepository;
    @Autowired private RawMaterialMovementRepository movementRepository;
    @Autowired private PurchaseOrderRepository purchaseOrderRepository;
    @Autowired private GoodsReceiptRepository goodsReceiptRepository;
    @Autowired private RawMaterialPurchaseRepository purchaseRepository;
    @Autowired private PurchasingService purchasingService;
    @Autowired private ProductionCatalogService productionCatalogService;
    @Autowired private ReconciliationService reconciliationService;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private JournalEntryRepository journalEntryRepository;

    @AfterEach
    void clearCompanyContext() {
        CompanyContextHolder.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    void purchaseFlow_postsBalancedJournal_andReconcilesAp() {
        String companyCode = "CR-AP-" + shortId();
        Company company = bootstrapCompany(companyCode);
        Map<String, Account> accounts = ensurePurchasingAccounts(company);
        Supplier supplier = ensureSupplier(company, accounts.get("AP"));
        RawMaterial rm = ensureRawMaterial(company, accounts.get("RM_INV"), "CR-RM");

        CompanyContextHolder.setCompanyId(companyCode);
        RawMaterialPurchaseResponse purchase = createPurchaseFlow(supplier, rm, TestDateUtils.safeDate(company));
        CompanyContextHolder.clear();

        RawMaterialPurchase persisted = purchaseRepository.findById(purchase.id()).orElseThrow();
        assertThat(persisted.getJournalEntry()).isNotNull();
        CoderedDbAssertions.assertBalancedJournal(journalEntryRepository, persisted.getJournalEntry().getId());
        CoderedDbAssertions.assertSupplierLedgerEntriesLinkedToJournal(
                jdbcTemplate, company.getId(), persisted.getJournalEntry().getId());
        CoderedDbAssertions.assertAuditLogRecordedForJournal(jdbcTemplate, persisted.getJournalEntry().getId());

        GoodsReceipt grn = goodsReceiptRepository.findById(purchase.goodsReceiptId()).orElseThrow();
        PurchaseOrder po = purchaseOrderRepository.findById(purchase.purchaseOrderId()).orElseThrow();
        assertThat(grn.getStatus()).isEqualTo("INVOICED");
        assertThat(po.getStatus()).isEqualTo("CLOSED");
        CoderedDbAssertions.assertNoNegativeInventory(jdbcTemplate, company.getId());

        CompanyContextHolder.setCompanyId(companyCode);
        ReconciliationService.SupplierReconciliationResult reconciliation = reconciliationService.reconcileApWithSupplierLedger();
        CompanyContextHolder.clear();
        assertThat(reconciliation.isReconciled()).isTrue();
    }

    @Test
    void importedCatalogRawMaterial_purchaseFlowPreservesMaterialIdentity() {
        String companyCode = "CR-AP-CAT-" + shortId();
        Company company = bootstrapCompany(companyCode);
        Map<String, Account> accounts = ensurePurchasingAccounts(company);
        Supplier supplier = ensureSupplier(company, accounts.get("AP"));
        String importedSku = ("CR-RM-IMP-" + shortId()).toUpperCase(Locale.ROOT);

        CompanyContextHolder.setCompanyId(companyCode);
        CatalogImportResponse importResponse = productionCatalogService.importCatalog(
                rawMaterialCatalogCsv(importedSku), "CR-RM-IMP-IDEMP-" + shortId());
        assertThat(importResponse.errors()).isEmpty();
        RawMaterial importedRawMaterial = rawMaterialRepository.findByCompanyAndSku(company, importedSku).orElseThrow();
        RawMaterialPurchaseResponse purchase = createPurchaseFlow(supplier, importedRawMaterial, TestDateUtils.safeDate(company));
        CompanyContextHolder.clear();

        assertThat(purchase.lines()).extracting(line -> line.rawMaterialId()).containsOnly(importedRawMaterial.getId());
        assertThat(purchase.journalEntryId()).isNotNull();
    }

    @Test
    void grnIdempotency_replayReturnsSameReceipt_andMovementsRemainStable() {
        String companyCode = "CR-GRN-IDEMP-" + shortId();
        Company company = bootstrapCompany(companyCode);
        Map<String, Account> accounts = ensurePurchasingAccounts(company);
        Supplier supplier = ensureSupplier(company, accounts.get("AP"));
        RawMaterial rm = ensureRawMaterial(company, accounts.get("RM_INV"), "CR-RM");
        LocalDate today = TestDateUtils.safeDate(company);

        CompanyContextHolder.setCompanyId(companyCode);
        PurchaseOrderResponse po = createApprovedPurchaseOrder(supplier, rm, today, new BigDecimal("10"), new BigDecimal("12.50"));
        String receiptNumber = "GRN-" + shortId();
        String idempotencyKey = "GRN-IDEMP-" + shortId();
        GoodsReceiptRequest request = new GoodsReceiptRequest(
                po.id(),
                receiptNumber,
                today,
                "CODE-RED GRN",
                idempotencyKey,
                List.of(new GoodsReceiptLineRequest(
                        rm.getId(),
                        "RM-BATCH-" + shortId(),
                        new BigDecimal("10"),
                        rm.getUnitType(),
                        new BigDecimal("12.50"),
                        "GRN line")));
        GoodsReceiptResponse first = purchasingService.createGoodsReceipt(request);
        int movementCount = movementRepository.findByRawMaterialCompanyAndReferenceTypeAndReferenceId(
                company, InventoryReference.GOODS_RECEIPT, receiptNumber).size();
        GoodsReceiptResponse second = purchasingService.createGoodsReceipt(request);
        int movementCountAfter = movementRepository.findByRawMaterialCompanyAndReferenceTypeAndReferenceId(
                company, InventoryReference.GOODS_RECEIPT, receiptNumber).size();
        CompanyContextHolder.clear();

        assertThat(second.id()).isEqualTo(first.id());
        assertThat(movementCountAfter).isEqualTo(movementCount);
    }

    @Test
    void concurrentPurchaseInvoice_createsSinglePurchase_andSingleJournal() {
        String companyCode = "CR-AP-CONC-" + shortId();
        Company company = bootstrapCompany(companyCode);
        Map<String, Account> accounts = ensurePurchasingAccounts(company);
        Supplier supplier = ensureSupplier(company, accounts.get("AP"));
        RawMaterial rm = ensureRawMaterial(company, accounts.get("RM_INV"), "CR-RM");
        LocalDate today = TestDateUtils.safeDate(company);

        CompanyContextHolder.setCompanyId(companyCode);
        PurchaseOrderResponse po = createApprovedPurchaseOrder(supplier, rm, today, new BigDecimal("5"), new BigDecimal("8.75"));
        GoodsReceiptResponse grn = purchasingService.createGoodsReceipt(new GoodsReceiptRequest(
                po.id(),
                "GRN-" + shortId(),
                today,
                "CODE-RED GRN",
                "GRN-IDEMP-" + shortId(),
                List.of(new GoodsReceiptLineRequest(
                        rm.getId(),
                        "RM-BATCH-" + shortId(),
                        new BigDecimal("5"),
                        rm.getUnitType(),
                        new BigDecimal("8.75"),
                        "GRN line"))));
        CompanyContextHolder.clear();

        RawMaterialPurchaseRequest request = new RawMaterialPurchaseRequest(
                supplier.getId(),
                "INV-" + shortId(),
                today,
                "CODE-RED Purchase",
                po.id(),
                grn.id(),
                null,
                List.of(new RawMaterialPurchaseLineRequest(
                        rm.getId(),
                        null,
                        new BigDecimal("5"),
                        rm.getUnitType(),
                        new BigDecimal("8.75"),
                        null,
                        null,
                        "Invoice line")));

        CoderedConcurrencyHarness.RunResult<RawMaterialPurchaseResponse> result = CoderedConcurrencyHarness.run(
                2,
                0,
                Duration.ofSeconds(20),
                ignored -> () -> {
                    CompanyContextHolder.setCompanyId(companyCode);
                    try {
                        return purchasingService.createPurchase(request);
                    } finally {
                        CompanyContextHolder.clear();
                    }
                },
                error -> false);

        assertThat(result.outcomes())
                .filteredOn(outcome -> outcome instanceof CoderedConcurrencyHarness.Outcome.Success<?> )
                .hasSize(1);
        RawMaterialPurchase persisted = purchaseRepository
                .findByCompanyAndInvoiceNumberIgnoreCase(company, request.invoiceNumber())
                .orElseThrow();
        assertThat(persisted.getJournalEntry()).isNotNull();
        CoderedDbAssertions.assertBalancedJournal(journalEntryRepository, persisted.getJournalEntry().getId());
    }

    private Company bootstrapCompany(String companyCode) {
        dataSeeder.ensureCompany(companyCode, companyCode + " Ltd");
        CompanyContextHolder.setCompanyId(companyCode);
        Company company = companyRepository.findByCodeIgnoreCase(companyCode).orElseThrow();
        company.setTimezone("UTC");
        company.setBaseCurrency("INR");
        company.setStateCode("MH");
        return companyRepository.saveAndFlush(company);
    }

    private Map<String, Account> ensurePurchasingAccounts(Company company) {
        Account ap = ensureAccount(company, "AP", "Accounts Payable", AccountType.LIABILITY);
        Account inv = ensureAccount(company, "RM_INV", "Raw Material Inventory", AccountType.ASSET);
        Account gstIn = ensureAccount(company, "GST_IN", "GST Input", AccountType.ASSET);
        Company fresh = companyRepository.findById(company.getId()).orElseThrow();
        fresh.setDefaultInventoryAccountId(inv.getId());
        fresh.setGstInputTaxAccountId(gstIn.getId());
        companyRepository.saveAndFlush(fresh);
        return Map.of("AP", ap, "RM_INV", inv, "GST_IN", gstIn);
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

    private Supplier ensureSupplier(Company company, Account apAccount) {
        return supplierRepository.findByCompanyAndCodeIgnoreCase(company, "CR-SUP")
                .map(existing -> {
                    existing.setPayableAccount(apAccount);
                    existing.setStateCode(company.getStateCode());
                    existing.setGstRegistrationType(GstRegistrationType.REGULAR);
                    return supplierRepository.saveAndFlush(existing);
                })
                .orElseGet(() -> {
                    Supplier supplier = new Supplier();
                    supplier.setCompany(company);
                    supplier.setCode("CR-SUP");
                    supplier.setName("Code-Red Supplier");
                    supplier.setStatus("ACTIVE");
                    supplier.setPayableAccount(apAccount);
                    supplier.setStateCode(company.getStateCode());
                    supplier.setGstRegistrationType(GstRegistrationType.REGULAR);
                    return supplierRepository.saveAndFlush(supplier);
                });
    }

    private RawMaterial ensureRawMaterial(Company company, Account inventoryAccount, String sku) {
        return rawMaterialRepository.findByCompanyAndSku(company, sku)
                .orElseGet(() -> {
                    RawMaterial rm = new RawMaterial();
                    rm.setCompany(company);
                    rm.setSku(sku);
                    rm.setName("Code-Red Raw Material");
                    rm.setUnitType("KG");
                    rm.setInventoryAccountId(inventoryAccount.getId());
                    rm.setCurrentStock(BigDecimal.ZERO);
                    return rawMaterialRepository.saveAndFlush(rm);
                });
    }

    private PurchaseOrderResponse createApprovedPurchaseOrder(Supplier supplier,
                                                              RawMaterial rm,
                                                              LocalDate entryDate,
                                                              BigDecimal quantity,
                                                              BigDecimal unitPrice) {
        PurchaseOrderResponse po = purchasingService.createPurchaseOrder(new PurchaseOrderRequest(
                supplier.getId(),
                "PO-" + shortId(),
                entryDate,
                "CODE-RED PO",
                List.of(new PurchaseOrderLineRequest(
                        rm.getId(),
                        quantity,
                        rm.getUnitType(),
                        unitPrice,
                        "RM line"))));
        return purchasingService.approvePurchaseOrder(po.id());
    }

    private RawMaterialPurchaseResponse createPurchaseFlow(Supplier supplier, RawMaterial rm, LocalDate entryDate) {
        PurchaseOrderResponse po = createApprovedPurchaseOrder(supplier, rm, entryDate, new BigDecimal("10"), new BigDecimal("12.50"));
        GoodsReceiptResponse grn = purchasingService.createGoodsReceipt(new GoodsReceiptRequest(
                po.id(),
                "GRN-" + shortId(),
                entryDate,
                "CODE-RED GRN",
                "GRN-IDEMP-" + shortId(),
                List.of(new GoodsReceiptLineRequest(
                        rm.getId(),
                        "RM-BATCH-" + shortId(),
                        new BigDecimal("10"),
                        rm.getUnitType(),
                        new BigDecimal("12.50"),
                        "GRN line"))));
        return purchasingService.createPurchase(new RawMaterialPurchaseRequest(
                supplier.getId(),
                "INV-" + shortId(),
                entryDate,
                "CODE-RED Purchase",
                po.id(),
                grn.id(),
                null,
                List.of(new RawMaterialPurchaseLineRequest(
                        rm.getId(),
                        null,
                        new BigDecimal("10"),
                        rm.getUnitType(),
                        new BigDecimal("12.50"),
                        null,
                        null,
                        "Invoice line"))));
    }

    private MockMultipartFile rawMaterialCatalogCsv(String skuCode) {
        String csv = String.join("\n",
                "brand,product_name,sku_code,category,unit_of_measure,gst_rate",
                "CRIMPORT,Imported Material," + skuCode + ",RAW_MATERIAL,KG,18.00");
        return new MockMultipartFile(
                "file",
                "catalog-import.csv",
                "text/csv",
                csv.getBytes(StandardCharsets.UTF_8));
    }

    private PurchaseOrderResponse createApprovedPurchaseOrder(Supplier supplier,
                                                              RawMaterial rawMaterial,
                                                              String orderNumber,
                                                              LocalDate orderDate,
                                                              BigDecimal quantity,
                                                              BigDecimal costPerUnit) {
        PurchaseOrderResponse draft = purchasingService.createPurchaseOrder(new PurchaseOrderRequest(
                supplier.getId(),
                orderNumber,
                orderDate,
                "CODE-RED PO",
                List.of(new PurchaseOrderLineRequest(
                        rawMaterial.getId(),
                        quantity,
                        rawMaterial.getUnitType(),
                        costPerUnit,
                        "RM line"))
        ));
        return purchasingService.approvePurchaseOrder(draft.id());
    }

    private static String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
