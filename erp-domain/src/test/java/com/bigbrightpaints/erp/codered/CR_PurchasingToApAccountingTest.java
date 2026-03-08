package com.bigbrightpaints.erp.codered;

import com.bigbrightpaints.erp.codered.support.CoderedConcurrencyHarness;
import com.bigbrightpaints.erp.codered.support.CoderedDbAssertions;
import com.bigbrightpaints.erp.codered.support.CoderedRetry;
import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.core.security.CompanyContextHolder;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountType;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriod;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriodRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocation;
import com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocationRepository;
import com.bigbrightpaints.erp.modules.accounting.dto.AccountingPeriodCloseRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.PartnerSettlementResponse;
import com.bigbrightpaints.erp.modules.accounting.dto.MonthEndChecklistDto;
import com.bigbrightpaints.erp.modules.accounting.dto.MonthEndChecklistItemDto;
import com.bigbrightpaints.erp.modules.accounting.dto.PeriodCloseRequestActionRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.SettlementAllocationRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.SupplierPaymentRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.SupplierSettlementRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryDto;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingService;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingPeriodService;
import com.bigbrightpaints.erp.modules.accounting.service.ReconciliationService;
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
import com.bigbrightpaints.erp.test.AbstractIntegrationTest;
import com.bigbrightpaints.erp.test.support.TestDateUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
@Tag("critical")
@Tag("concurrency")
@Tag("reconciliation")

class CR_PurchasingToApAccountingTest extends AbstractIntegrationTest {

    @Autowired private CompanyRepository companyRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private AccountingPeriodService accountingPeriodService;
    @Autowired private AccountingPeriodRepository accountingPeriodRepository;
    @Autowired private AccountingService accountingService;
    @Autowired private PartnerSettlementAllocationRepository settlementAllocationRepository;
    @Autowired private SupplierRepository supplierRepository;
    @Autowired private RawMaterialRepository rawMaterialRepository;
    @Autowired private RawMaterialMovementRepository movementRepository;
    @Autowired private PurchaseOrderRepository purchaseOrderRepository;
    @Autowired private GoodsReceiptRepository goodsReceiptRepository;
    @Autowired private RawMaterialPurchaseRepository purchaseRepository;
    @Autowired private PurchasingService purchasingService;
    @Autowired private ProductionCatalogService productionCatalogService;
    @Autowired private JournalEntryRepository journalEntryRepository;
    @Autowired private ReconciliationService reconciliationService;
    @Autowired private JdbcTemplate jdbcTemplate;

    @AfterEach
    void clearCompanyContext() {
        CompanyContextHolder.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    void purchaseFlow_postsBalancedJournal_linksMovements_andReconcilesAp() {
        String companyCode = "CR-AP-" + shortId();
        Company company = bootstrapCompany(companyCode);
        Map<String, Account> accounts = ensurePurchasingAccounts(company);

        Supplier supplier = ensureSupplier(company, accounts.get("AP"));
        RawMaterial rm = ensureRawMaterial(company, accounts.get("RM_INV"));

        LocalDate today = TestDateUtils.safeDate(company);
        String orderNumber = "PO-" + shortId();
        String receiptNumber = "GRN-" + shortId();
        String invoiceNumber = "INV-" + shortId();
        String grnIdempotencyKey = "GRN-IDEMP-" + shortId();

        CompanyContextHolder.setCompanyId(companyCode);
        PurchaseOrderResponse po = createApprovedPurchaseOrder(
                supplier,
                rm,
                orderNumber,
                today,
                new BigDecimal("10"),
                new BigDecimal("12.50"));

        GoodsReceiptResponse grn = purchasingService.createGoodsReceipt(new GoodsReceiptRequest(
                po.id(),
                receiptNumber,
                today,
                "CODE-RED GRN",
                grnIdempotencyKey,
                List.of(new GoodsReceiptLineRequest(
                        rm.getId(),
                        "RM-BATCH-" + shortId(),
                        new BigDecimal("10"),
                        rm.getUnitType(),
                        new BigDecimal("12.50"),
                        "GRN line"))
        ));

        RawMaterialPurchaseResponse purchase = purchasingService.createPurchase(new RawMaterialPurchaseRequest(
                supplier.getId(),
                invoiceNumber,
                today,
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
                        "Invoice line"))
        ));
        CompanyContextHolder.clear();

        RawMaterialPurchase persisted = purchaseRepository.findById(purchase.id()).orElseThrow();
        assertThat(persisted.getJournalEntry()).as("purchase journal").isNotNull();

        Long journalId = persisted.getJournalEntry().getId();
        CoderedDbAssertions.assertBalancedJournal(journalEntryRepository, journalId);
        CoderedDbAssertions.assertSupplierLedgerEntriesLinkedToJournal(jdbcTemplate, company.getId(), journalId);
        CoderedDbAssertions.assertAuditLogRecordedForJournal(jdbcTemplate, journalId);

        GoodsReceipt grnEntity = goodsReceiptRepository.findById(grn.id()).orElseThrow();
        assertThat(grnEntity.getStatus()).as("grn invoiced").isEqualTo("INVOICED");

        PurchaseOrder poEntity = purchaseOrderRepository.findById(po.id()).orElseThrow();
        assertThat(poEntity.getStatus()).as("po closed").isEqualTo("CLOSED");

        CoderedDbAssertions.assertRawMaterialMovementsLinkedToJournal(
                jdbcTemplate,
                company.getId(),
                InventoryReference.GOODS_RECEIPT,
                grn.receiptNumber(),
                journalId
        );
        CoderedDbAssertions.assertNoNegativeInventory(jdbcTemplate, company.getId());

        CompanyContextHolder.setCompanyId(companyCode);
        ReconciliationService.SupplierReconciliationResult reconciliation = reconciliationService.reconcileApWithSupplierLedger();
        CompanyContextHolder.clear();
        assertThat(reconciliation.isReconciled())
                .as("ap reconciled (gl=%s ledger=%s variance=%s)",
                        reconciliation.glApBalance(),
                        reconciliation.supplierLedgerTotal(),
                        reconciliation.variance())
                .isTrue();
    }

    @Test
    void importedCatalogRawMaterial_purchaseFlowPreservesReferenceCarryChain() {
        String companyCode = "CR-AP-CAT-" + shortId();
        Company company = bootstrapCompany(companyCode);
        Map<String, Account> accounts = ensurePurchasingAccounts(company);
        Supplier supplier = ensureSupplier(company, accounts.get("AP"));
        String importedSku = ("CR-RM-IMP-" + shortId()).toUpperCase(Locale.ROOT);
        RawMaterial importedRawMaterial;
        RawMaterialPurchaseResponse purchase;

        CompanyContextHolder.setCompanyId(companyCode);
        try {
            CatalogImportResponse importResponse = productionCatalogService.importCatalog(
                    rawMaterialCatalogCsv(importedSku),
                    "CR-RM-IMP-IDEMP-" + shortId());
            assertThat(importResponse.errors()).isEmpty();

            importedRawMaterial = rawMaterialRepository.findByCompanyAndSku(company, importedSku).orElseThrow();
            purchase = createPurchaseFlow(supplier, importedRawMaterial, TestDateUtils.safeDate(company));
        } finally {
            CompanyContextHolder.clear();
        }

        assertThat(purchase.journalEntryId()).as("purchase journal").isNotNull();
        assertThat(purchase.lines())
                .extracting(line -> line.rawMaterialId())
                .containsOnly(importedRawMaterial.getId());

        Long journalId = purchase.journalEntryId();
        CoderedDbAssertions.assertRawMaterialMovementsLinkedToJournal(
                jdbcTemplate,
                company.getId(),
                InventoryReference.GOODS_RECEIPT,
                purchase.goodsReceiptNumber(),
                journalId
        );

        List<Long> movementRawMaterialIds = movementRepository
                .findByRawMaterialCompanyAndReferenceTypeAndReferenceId(
                        company,
                        InventoryReference.GOODS_RECEIPT,
                        purchase.goodsReceiptNumber())
                .stream()
                .map(movement -> movement.getRawMaterial().getId())
                .distinct()
                .toList();
        assertThat(movementRawMaterialIds).containsExactly(importedRawMaterial.getId());
    }

    @Test
    void importedCatalogRawMaterial_purchaseRejectsDriftedMovementJournalRelink() {
        String companyCode = "CR-AP-CAT-DRIFT-" + shortId();
        Company company = bootstrapCompany(companyCode);
        Map<String, Account> accounts = ensurePurchasingAccounts(company);
        Supplier supplier = ensureSupplier(company, accounts.get("AP"));
        String importedSku = ("CR-RM-DRIFT-" + shortId()).toUpperCase(Locale.ROOT);
        LocalDate today = TestDateUtils.safeDate(company);

        CompanyContextHolder.setCompanyId(companyCode);
        try {
            CatalogImportResponse importResponse = productionCatalogService.importCatalog(
                    rawMaterialCatalogCsv(importedSku),
                    "CR-RM-DRIFT-IDEMP-" + shortId());
            assertThat(importResponse.errors()).isEmpty();

            RawMaterial importedRawMaterial = rawMaterialRepository.findByCompanyAndSku(company, importedSku).orElseThrow();
            RawMaterialPurchaseResponse baselinePurchase = createPurchaseFlow(supplier, importedRawMaterial, today);
            Long baselineJournalId = purchaseRepository.findById(baselinePurchase.id()).orElseThrow()
                    .getJournalEntry()
                    .getId();

            String orderNumber = "PO-" + shortId();
            String receiptNumber = "GRN-" + shortId();
            String invoiceNumber = "INV-" + shortId();
            String grnIdempotencyKey = "GRN-IDEMP-" + shortId();

            PurchaseOrderResponse po = createApprovedPurchaseOrder(
                    supplier,
                    importedRawMaterial,
                    orderNumber,
                    today,
                    new BigDecimal("6"),
                    new BigDecimal("11.75"));

            GoodsReceiptResponse grn = purchasingService.createGoodsReceipt(new GoodsReceiptRequest(
                    po.id(),
                    receiptNumber,
                    today,
                    "CODE-RED Drift GRN",
                    grnIdempotencyKey,
                    List.of(new GoodsReceiptLineRequest(
                            importedRawMaterial.getId(),
                            "RM-BATCH-" + shortId(),
                            new BigDecimal("6"),
                            importedRawMaterial.getUnitType(),
                            new BigDecimal("11.75"),
                            "GRN line"))
            ));

            var driftedMovements = movementRepository.findByRawMaterialCompanyAndReferenceTypeAndReferenceId(
                    company,
                    InventoryReference.GOODS_RECEIPT,
                    grn.receiptNumber());
            assertThat(driftedMovements).isNotEmpty();
            driftedMovements.forEach(movement -> movement.setJournalEntryId(baselineJournalId));
            movementRepository.saveAll(driftedMovements);

            assertThatThrownBy(() -> purchasingService.createPurchase(new RawMaterialPurchaseRequest(
                    supplier.getId(),
                    invoiceNumber,
                    today,
                    "CODE-RED Drift Purchase",
                    po.id(),
                    grn.id(),
                    null,
                    List.of(new RawMaterialPurchaseLineRequest(
                            importedRawMaterial.getId(),
                            null,
                            new BigDecimal("6"),
                            importedRawMaterial.getUnitType(),
                            new BigDecimal("11.75"),
                            null,
                            null,
                            "Invoice line"))
            )))
                    .isInstanceOf(ApplicationException.class)
                    .hasMessageContaining("already linked to journal")
                    .satisfies(error -> assertThat(((ApplicationException) error).getErrorCode())
                            .isEqualTo(ErrorCode.BUSINESS_CONSTRAINT_VIOLATION));

            List<Long> movementJournalIds = movementRepository.findByRawMaterialCompanyAndReferenceTypeAndReferenceId(
                            company,
                            InventoryReference.GOODS_RECEIPT,
                            grn.receiptNumber())
                    .stream()
                    .map(movement -> movement.getJournalEntryId())
                    .distinct()
                    .toList();
            assertThat(movementJournalIds).containsExactly(baselineJournalId);

            GoodsReceipt refreshedGrn = goodsReceiptRepository.findById(grn.id()).orElseThrow();
            assertThat(refreshedGrn.getStatus()).isEqualTo("RECEIVED");
            assertThat(purchaseRepository.findByCompanyAndInvoiceNumberIgnoreCase(company, invoiceNumber))
                    .as("failed invoice is not persisted")
                    .isEmpty();
        } finally {
            CompanyContextHolder.clear();
        }
    }

    @Test
    void concurrentPurchaseInvoice_createsSinglePurchase_andSingleJournal() {
        String companyCode = "CR-AP-CONC-" + shortId();
        Company company = bootstrapCompany(companyCode);
        Map<String, Account> accounts = ensurePurchasingAccounts(company);

        Supplier supplier = ensureSupplier(company, accounts.get("AP"));
        RawMaterial rm = ensureRawMaterial(company, accounts.get("RM_INV"));

        LocalDate today = TestDateUtils.safeDate(company);
        String orderNumber = "PO-" + shortId();
        String receiptNumber = "GRN-" + shortId();
        String invoiceNumber = "INV-" + shortId();
        String grnIdempotencyKey = "GRN-IDEMP-" + shortId();

        CompanyContextHolder.setCompanyId(companyCode);
        PurchaseOrderResponse po = createApprovedPurchaseOrder(
                supplier,
                rm,
                orderNumber,
                today,
                new BigDecimal("5"),
                new BigDecimal("8.75"));

        GoodsReceiptResponse grn = purchasingService.createGoodsReceipt(new GoodsReceiptRequest(
                po.id(),
                receiptNumber,
                today,
                "CODE-RED GRN",
                grnIdempotencyKey,
                List.of(new GoodsReceiptLineRequest(
                        rm.getId(),
                        "RM-BATCH-" + shortId(),
                        new BigDecimal("5"),
                        rm.getUnitType(),
                        new BigDecimal("8.75"),
                        "GRN line"))
        ));
        CompanyContextHolder.clear();

        RawMaterialPurchaseRequest request = new RawMaterialPurchaseRequest(
                supplier.getId(),
                invoiceNumber,
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
                        "Invoice line"))
        );

        CoderedConcurrencyHarness.RunResult<RawMaterialPurchaseResponse> result = CoderedConcurrencyHarness.run(
                3,
                3,
                Duration.ofSeconds(45),
                threadIndex -> () -> {
                    CompanyContextHolder.setCompanyId(companyCode);
                    try {
                        return purchasingService.createPurchase(request);
                    } finally {
                        CompanyContextHolder.clear();
                    }
                },
                CoderedRetry::isRetryable
        );

        List<RawMaterialPurchaseResponse> successes = result.outcomes().stream()
                .filter(outcome -> outcome instanceof CoderedConcurrencyHarness.Outcome.Success<?>)
                .map(outcome -> ((CoderedConcurrencyHarness.Outcome.Success<RawMaterialPurchaseResponse>) outcome).value())
                .toList();
        assertThat(successes).as("single purchase wins").hasSize(1);

        Integer purchaseCount = jdbcTemplate.queryForObject(
                """
                select count(*)
                from raw_material_purchases
                where company_id = ?
                  and lower(invoice_number) = lower(?)
                """,
                Integer.class,
                company.getId(),
                invoiceNumber
        );
        assertThat(purchaseCount).as("single purchase row").isEqualTo(1);

        RawMaterialPurchase saved = purchaseRepository.findByCompanyAndInvoiceNumberIgnoreCase(company, invoiceNumber)
                .orElseThrow();
        assertThat(saved.getJournalEntry()).as("journal posted once").isNotNull();

        Long journalId = saved.getJournalEntry().getId();
        CoderedDbAssertions.assertBalancedJournal(journalEntryRepository, journalId);
        CoderedDbAssertions.assertSupplierLedgerEntriesLinkedToJournal(jdbcTemplate, company.getId(), journalId);

        CoderedDbAssertions.assertRawMaterialMovementsLinkedToJournal(
                jdbcTemplate,
                company.getId(),
                InventoryReference.GOODS_RECEIPT,
                receiptNumber,
                journalId
        );
    }

    @Test
    void grnIdempotency_replayReturnsSameReceipt_andMovementsNotDuplicated() {
        String companyCode = "CR-GRN-IDEMP-" + shortId();
        Company company = bootstrapCompany(companyCode);
        Map<String, Account> accounts = ensurePurchasingAccounts(company);

        Supplier supplier = ensureSupplier(company, accounts.get("AP"));
        RawMaterial rm = ensureRawMaterial(company, accounts.get("RM_INV"));

        LocalDate today = TestDateUtils.safeDate(company);
        String orderNumber = "PO-" + shortId();
        String receiptNumber = "GRN-" + shortId();
        String idempotencyKey = "GRN-IDEMP-" + shortId();

        CompanyContextHolder.setCompanyId(companyCode);
        PurchaseOrderResponse po = createApprovedPurchaseOrder(
                supplier,
                rm,
                orderNumber,
                today,
                new BigDecimal("10"),
                new BigDecimal("12.50"));

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
                        "GRN line"))
        );

        GoodsReceiptResponse first = purchasingService.createGoodsReceipt(request);
        int movementCount = movementRepository.findByRawMaterialCompanyAndReferenceTypeAndReferenceId(
                company,
                InventoryReference.GOODS_RECEIPT,
                receiptNumber
        ).size();

        GoodsReceiptResponse second = purchasingService.createGoodsReceipt(request);
        int movementCountAfter = movementRepository.findByRawMaterialCompanyAndReferenceTypeAndReferenceId(
                company,
                InventoryReference.GOODS_RECEIPT,
                receiptNumber
        ).size();

        assertThat(second.id()).as("idempotent grn").isEqualTo(first.id());
        assertThat(movementCountAfter).as("movements stable on replay").isEqualTo(movementCount);
    }

    @Test
    void grnIdempotency_mismatchReturnsConflict() {
        String companyCode = "CR-GRN-MISMATCH-" + shortId();
        Company company = bootstrapCompany(companyCode);
        Map<String, Account> accounts = ensurePurchasingAccounts(company);

        Supplier supplier = ensureSupplier(company, accounts.get("AP"));
        RawMaterial rm = ensureRawMaterial(company, accounts.get("RM_INV"));

        LocalDate today = TestDateUtils.safeDate(company);
        String orderNumber = "PO-" + shortId();
        String receiptNumber = "GRN-" + shortId();
        String idempotencyKey = "GRN-IDEMP-" + shortId();

        CompanyContextHolder.setCompanyId(companyCode);
        PurchaseOrderResponse po = createApprovedPurchaseOrder(
                supplier,
                rm,
                orderNumber,
                today,
                new BigDecimal("5"),
                new BigDecimal("8.75"));

        GoodsReceiptRequest request = new GoodsReceiptRequest(
                po.id(),
                receiptNumber,
                today,
                "CODE-RED GRN",
                idempotencyKey,
                List.of(new GoodsReceiptLineRequest(
                        rm.getId(),
                        "RM-BATCH-" + shortId(),
                        new BigDecimal("5"),
                        rm.getUnitType(),
                        new BigDecimal("8.75"),
                        "GRN line"))
        );
        purchasingService.createGoodsReceipt(request);

        GoodsReceiptRequest mismatch = new GoodsReceiptRequest(
                po.id(),
                receiptNumber,
                today,
                "CODE-RED GRN",
                idempotencyKey,
                List.of(new GoodsReceiptLineRequest(
                        rm.getId(),
                        "RM-BATCH-" + shortId(),
                        new BigDecimal("4"),
                        rm.getUnitType(),
                        new BigDecimal("8.75"),
                        "GRN line"))
        );

        assertThatThrownBy(() -> purchasingService.createGoodsReceipt(mismatch))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("Idempotency key already used")
                .satisfies(error -> assertThat(((ApplicationException) error).getErrorCode())
                        .isEqualTo(ErrorCode.CONCURRENCY_CONFLICT));
    }

    @Test
    void grnClosedPeriodRejected() {
        String companyCode = "CR-GRN-CLOSED-" + shortId();
        Company company = bootstrapCompany(companyCode);
        Map<String, Account> accounts = ensurePurchasingAccounts(company);

        Supplier supplier = ensureSupplier(company, accounts.get("AP"));
        RawMaterial rm = ensureRawMaterial(company, accounts.get("RM_INV"));

        LocalDate receiptDate = TestDateUtils.safeDate(company).withDayOfMonth(1);
        String orderNumber = "PO-" + shortId();
        String receiptNumber = "GRN-" + shortId();
        String idempotencyKey = "GRN-IDEMP-" + shortId();

        CompanyContextHolder.setCompanyId(companyCode);
        accountingPeriodService.listPeriods();
        AccountingPeriod period = accountingPeriodRepository.findByCompanyAndYearAndMonth(
                company, receiptDate.getYear(), receiptDate.getMonthValue()).orElseThrow();
        forceClosePeriod(period.getId(), "CODE-RED close request", "CODE-RED close approval");

        PurchaseOrderResponse po = createApprovedPurchaseOrder(
                supplier,
                rm,
                orderNumber,
                receiptDate,
                new BigDecimal("3"),
                new BigDecimal("9.50"));

        GoodsReceiptRequest request = new GoodsReceiptRequest(
                po.id(),
                receiptNumber,
                receiptDate,
                "CODE-RED GRN",
                idempotencyKey,
                List.of(new GoodsReceiptLineRequest(
                        rm.getId(),
                        "RM-BATCH-" + shortId(),
                        new BigDecimal("3"),
                        rm.getUnitType(),
                        new BigDecimal("9.50"),
                        "GRN line"))
        );

        assertThatThrownBy(() -> purchasingService.createGoodsReceipt(request))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("locked/closed");
    }

    @Test
    void supplierPayment_idempotencyConcurrent_singleJournalAndAllocations() {
        String companyCode = "CR-SUP-PAY-IDEMP-" + shortId();
        Company company = bootstrapCompany(companyCode);
        Map<String, Account> accounts = ensurePurchasingAccounts(company);
        Account cash = ensureAccount(company, "CASH", "Cash", AccountType.ASSET);

        Supplier supplier = ensureSupplier(company, accounts.get("AP"));
        RawMaterial rm = ensureRawMaterial(company, accounts.get("RM_INV"));
        LocalDate today = TestDateUtils.safeDate(company);

        CompanyContextHolder.setCompanyId(companyCode);
        RawMaterialPurchaseResponse purchase = createPurchaseFlow(supplier, rm, today);
        CompanyContextHolder.clear();

        BigDecimal amount = purchase.totalAmount();
        SettlementAllocationRequest allocation = new SettlementAllocationRequest(
                null,
                purchase.id(),
                amount,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                "supplier payment");
        String idempotencyKey = "SUP-PAY-IDEMP-" + shortId();
        SupplierPaymentRequest request = new SupplierPaymentRequest(
                supplier.getId(),
                cash.getId(),
                amount,
                "PAY-" + shortId(),
                "Supplier payment",
                idempotencyKey,
                List.of(allocation)
        );

        CoderedConcurrencyHarness.RunResult<JournalEntryDto> result = CoderedConcurrencyHarness.run(
                3,
                3,
                Duration.ofSeconds(45),
                threadIndex -> () -> {
                    CompanyContextHolder.setCompanyId(companyCode);
                    try {
                        return accountingService.recordSupplierPayment(request);
                    } finally {
                        CompanyContextHolder.clear();
                    }
                },
                CoderedRetry::isRetryable
        );

        List<JournalEntryDto> successes = result.outcomes().stream()
                .filter(outcome -> outcome instanceof CoderedConcurrencyHarness.Outcome.Success<?>)
                .map(outcome -> ((CoderedConcurrencyHarness.Outcome.Success<JournalEntryDto>) outcome).value())
                .toList();
        assertThat(successes).as("payment succeeds").isNotEmpty();
        Long journalId = successes.getFirst().id();
        assertThat(successes)
                .as("idempotent supplier payment returns the same journal")
                .allMatch(dto -> dto.id() != null && dto.id().equals(journalId));

        List<PartnerSettlementAllocation> allocations =
                settlementAllocationRepository.findByCompanyAndIdempotencyKey(company, idempotencyKey);
        assertThat(allocations).as("single allocation row").hasSize(1);
        RawMaterialPurchase saved = purchaseRepository.findById(purchase.id()).orElseThrow();
        assertThat(saved.getOutstandingAmount()).as("outstanding reduced once").isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void supplierPayment_idempotencyReplay_returnsOriginalJournal_afterSupplierSuspends() {
        String companyCode = "CR-SUP-PAY-REPLAY-SUSP-" + shortId();
        Company company = bootstrapCompany(companyCode);
        Map<String, Account> accounts = ensurePurchasingAccounts(company);
        Account cash = ensureAccount(company, "CASH", "Cash", AccountType.ASSET);

        Supplier supplier = ensureSupplier(company, accounts.get("AP"));
        RawMaterial rm = ensureRawMaterial(company, accounts.get("RM_INV"));
        LocalDate today = TestDateUtils.safeDate(company);

        CompanyContextHolder.setCompanyId(companyCode);
        RawMaterialPurchaseResponse purchase = createPurchaseFlow(supplier, rm, today);

        BigDecimal amount = purchase.totalAmount();
        SettlementAllocationRequest allocation = new SettlementAllocationRequest(
                null,
                purchase.id(),
                amount,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                "supplier payment");
        String idempotencyKey = "SUP-PAY-REPLAY-SUSP-" + shortId();
        SupplierPaymentRequest request = new SupplierPaymentRequest(
                supplier.getId(),
                cash.getId(),
                amount,
                "PAY-" + shortId(),
                "Supplier payment",
                idempotencyKey,
                List.of(allocation)
        );

        JournalEntryDto first = accountingService.recordSupplierPayment(request);
        Supplier suspendedSupplier = supplierRepository.findById(supplier.getId()).orElseThrow();
        suspendedSupplier.setStatus("SUSPENDED");
        supplierRepository.save(suspendedSupplier);

        JournalEntryDto replay = accountingService.recordSupplierPayment(request);

        assertThat(replay.id()).as("idempotent replay returns same journal").isEqualTo(first.id());
        assertThat(settlementAllocationRepository.findByCompanyAndIdempotencyKey(company, idempotencyKey))
                .as("single allocation row")
                .hasSize(1);

        RawMaterialPurchase saved = purchaseRepository.findById(purchase.id()).orElseThrow();
        assertThat(saved.getOutstandingAmount()).as("outstanding reduced once").isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void supplierSettlement_idempotencyReplay_doesNotDoubleReduceOutstanding() {
        String companyCode = "CR-SUP-SETTLE-IDEMP-" + shortId();
        Company company = bootstrapCompany(companyCode);
        Map<String, Account> accounts = ensurePurchasingAccounts(company);
        Account cash = ensureAccount(company, "CASH", "Cash", AccountType.ASSET);

        Supplier supplier = ensureSupplier(company, accounts.get("AP"));
        RawMaterial rm = ensureRawMaterial(company, accounts.get("RM_INV"));
        LocalDate today = TestDateUtils.safeDate(company);

        CompanyContextHolder.setCompanyId(companyCode);
        RawMaterialPurchaseResponse purchase = createPurchaseFlow(supplier, rm, today);

        BigDecimal amount = purchase.totalAmount();
        SettlementAllocationRequest allocation = new SettlementAllocationRequest(
                null,
                purchase.id(),
                amount,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                "supplier settlement");
        String idempotencyKey = "SUP-SETTLE-IDEMP-" + shortId();
        SupplierSettlementRequest request = new SupplierSettlementRequest(
                supplier.getId(),
                cash.getId(),
                null,
                null,
                null,
                null,
                today,
                "SET-" + shortId(),
                "Supplier settlement",
                idempotencyKey,
                Boolean.FALSE,
                List.of(allocation)
        );

        PartnerSettlementResponse first = accountingService.settleSupplierInvoices(request);
        Supplier suspendedSupplier = supplierRepository.findById(supplier.getId()).orElseThrow();
        suspendedSupplier.setStatus("SUSPENDED");
        supplierRepository.save(suspendedSupplier);
        PartnerSettlementResponse replay = accountingService.settleSupplierInvoices(request);
        assertThat(replay.journalEntry().id()).as("idempotent replay returns same journal")
                .isEqualTo(first.journalEntry().id());

        RawMaterialPurchase saved = purchaseRepository.findById(purchase.id()).orElseThrow();
        assertThat(saved.getOutstandingAmount()).as("outstanding reduced once").isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(settlementAllocationRepository.findByCompanyAndIdempotencyKey(company, idempotencyKey))
                .as("single allocation row")
                .hasSize(1);
    }

    @Test
    void supplierSettlement_idempotencyMismatch_conflicts() {
        String companyCode = "CR-SUP-SETTLE-MISMATCH-" + shortId();
        Company company = bootstrapCompany(companyCode);
        Map<String, Account> accounts = ensurePurchasingAccounts(company);
        Account cash = ensureAccount(company, "CASH", "Cash", AccountType.ASSET);

        Supplier supplier = ensureSupplier(company, accounts.get("AP"));
        RawMaterial rm = ensureRawMaterial(company, accounts.get("RM_INV"));
        LocalDate today = TestDateUtils.safeDate(company);

        CompanyContextHolder.setCompanyId(companyCode);
        RawMaterialPurchaseResponse purchase = createPurchaseFlow(supplier, rm, today);

        SettlementAllocationRequest allocation = new SettlementAllocationRequest(
                null,
                purchase.id(),
                purchase.totalAmount(),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                "supplier settlement");
        String idempotencyKey = "SUP-SETTLE-IDEMP-" + shortId();
        SupplierSettlementRequest request = new SupplierSettlementRequest(
                supplier.getId(),
                cash.getId(),
                null,
                null,
                null,
                null,
                today,
                "SET-" + shortId(),
                "Supplier settlement",
                idempotencyKey,
                Boolean.FALSE,
                List.of(allocation)
        );
        accountingService.settleSupplierInvoices(request);

        SettlementAllocationRequest mismatchAllocation = new SettlementAllocationRequest(
                null,
                purchase.id(),
                purchase.totalAmount().subtract(new BigDecimal("1.00")),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                "supplier settlement");
        SupplierSettlementRequest mismatch = new SupplierSettlementRequest(
                supplier.getId(),
                cash.getId(),
                null,
                null,
                null,
                null,
                today,
                "SET-" + shortId(),
                "Supplier settlement",
                idempotencyKey,
                Boolean.FALSE,
                List.of(mismatchAllocation)
        );

        assertThatThrownBy(() -> accountingService.settleSupplierInvoices(mismatch))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("Idempotency key already used")
                .satisfies(error -> assertThat(((ApplicationException) error).getErrorCode())
                        .isEqualTo(ErrorCode.CONCURRENCY_CONFLICT));
    }

    @Test
    void periodChecklist_treatsPartialAndPaidPurchasesAsPosted() {
        String companyCode = "CR-PERIOD-P2P-PARTIAL-" + shortId();
        Company company = bootstrapCompany(companyCode);
        Map<String, Account> accounts = ensurePurchasingAccounts(company);

        Supplier supplier = ensureSupplier(company, accounts.get("AP"));
        RawMaterial rm = ensureRawMaterial(company, accounts.get("RM_INV"));
        LocalDate today = TestDateUtils.safeDate(company);

        CompanyContextHolder.setCompanyId(companyCode);
        RawMaterialPurchaseResponse purchase = createPurchaseFlow(supplier, rm, today);
        RawMaterialPurchase persisted = purchaseRepository.findById(purchase.id()).orElseThrow();
        persisted.setStatus("PARTIAL");
        purchaseRepository.save(persisted);

        accountingPeriodService.listPeriods();
        AccountingPeriod period = accountingPeriodRepository.findByCompanyAndYearAndMonth(
                company, today.getYear(), today.getMonthValue()).orElseThrow();
        MonthEndChecklistDto checklist = accountingPeriodService.getMonthEndChecklist(period.getId());

        MonthEndChecklistItemDto unposted = findChecklistItem(checklist, "unpostedDocuments");
        assertThat(unposted.completed()).as("partial purchases are treated as posted").isTrue();
        assertThat(unposted.detail()).contains("All documents posted");

        MonthEndChecklistItemDto unlinked = findChecklistItem(checklist, "unlinkedDocuments");
        assertThat(unlinked.completed()).as("journal link still required").isTrue();
    }

    @Test
    void periodChecklist_flagsPostedishPurchaseMissingJournalLink() {
        String companyCode = "CR-PERIOD-P2P-UNLINKED-" + shortId();
        Company company = bootstrapCompany(companyCode);
        Map<String, Account> accounts = ensurePurchasingAccounts(company);

        Supplier supplier = ensureSupplier(company, accounts.get("AP"));
        RawMaterial rm = ensureRawMaterial(company, accounts.get("RM_INV"));
        LocalDate today = TestDateUtils.safeDate(company);

        CompanyContextHolder.setCompanyId(companyCode);
        RawMaterialPurchaseResponse purchase = createPurchaseFlow(supplier, rm, today);
        RawMaterialPurchase persisted = purchaseRepository.findById(purchase.id()).orElseThrow();
        persisted.setStatus("PAID");
        persisted.setJournalEntry(null);
        purchaseRepository.save(persisted);

        accountingPeriodService.listPeriods();
        AccountingPeriod period = accountingPeriodRepository.findByCompanyAndYearAndMonth(
                company, today.getYear(), today.getMonthValue()).orElseThrow();
        MonthEndChecklistDto checklist = accountingPeriodService.getMonthEndChecklist(period.getId());

        MonthEndChecklistItemDto unposted = findChecklistItem(checklist, "unpostedDocuments");
        assertThat(unposted.completed()).as("paid purchases are treated as posted").isTrue();

        MonthEndChecklistItemDto unlinked = findChecklistItem(checklist, "unlinkedDocuments");
        assertThat(unlinked.completed()).as("missing journal link is a blocker").isFalse();
        assertThat(unlinked.detail()).contains("missing journal links");
    }

    private Company bootstrapCompany(String companyCode) {
        dataSeeder.ensureCompany(companyCode, companyCode + " Ltd");
        CompanyContextHolder.setCompanyId(companyCode);
        Company company = companyRepository.findByCodeIgnoreCase(companyCode).orElseThrow();
        company.setTimezone("UTC");
        company.setBaseCurrency("INR");
        company.setStateCode("KA");
        return companyRepository.save(company);
    }

    private Map<String, Account> ensurePurchasingAccounts(Company company) {
        Account ap = ensureAccount(company, "AP", "Accounts Payable", AccountType.LIABILITY);
        Account inv = ensureAccount(company, "RM_INV", "Raw Material Inventory", AccountType.ASSET);
        Account gstIn = ensureAccount(company, "GST_IN", "GST Input", AccountType.ASSET);

        Company fresh = companyRepository.findById(company.getId()).orElseThrow();
        if (fresh.getDefaultInventoryAccountId() == null) {
            fresh.setDefaultInventoryAccountId(inv.getId());
        }
        if (fresh.getGstInputTaxAccountId() == null) {
            fresh.setGstInputTaxAccountId(gstIn.getId());
        }
        companyRepository.save(fresh);

        return Map.of(
                "AP", ap,
                "RM_INV", inv,
                "GST_IN", gstIn
        );
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
                    if (existing.getPayableAccount() == null) {
                        existing.setPayableAccount(apAccount);
                    }
                    if (existing.getStateCode() == null) {
                        existing.setStateCode("KA");
                    }
                    return supplierRepository.save(existing);
                })
                .orElseGet(() -> {
                    Supplier supplier = new Supplier();
                    supplier.setCompany(company);
                    supplier.setCode("CR-SUP");
                    supplier.setName("Code-Red Supplier");
                    supplier.setStatus("ACTIVE");
                    supplier.setPayableAccount(apAccount);
                    supplier.setStateCode("KA");
                    return supplierRepository.save(supplier);
                });
    }

    private RawMaterial ensureRawMaterial(Company company, Account inventoryAccount) {
        return rawMaterialRepository.findByCompanyAndSku(company, "CR-RM")
                .orElseGet(() -> {
                    RawMaterial rm = new RawMaterial();
                    rm.setCompany(company);
                    rm.setSku("CR-RM");
                    rm.setName("Code-Red Raw Material");
                    rm.setUnitType("KG");
                    rm.setInventoryAccountId(inventoryAccount.getId());
                    rm.setCurrentStock(BigDecimal.ZERO);
                    return rawMaterialRepository.save(rm);
                });
    }

    private RawMaterialPurchaseResponse createPurchaseFlow(Supplier supplier, RawMaterial rm, LocalDate entryDate) {
        String orderNumber = "PO-" + shortId();
        String receiptNumber = "GRN-" + shortId();
        String invoiceNumber = "INV-" + shortId();
        String grnIdempotencyKey = "GRN-IDEMP-" + shortId();

        PurchaseOrderResponse po = createApprovedPurchaseOrder(
                supplier,
                rm,
                orderNumber,
                entryDate,
                new BigDecimal("10"),
                new BigDecimal("12.50"));

        GoodsReceiptResponse grn = purchasingService.createGoodsReceipt(new GoodsReceiptRequest(
                po.id(),
                receiptNumber,
                entryDate,
                "CODE-RED GRN",
                grnIdempotencyKey,
                List.of(new GoodsReceiptLineRequest(
                        rm.getId(),
                        "RM-BATCH-" + shortId(),
                        new BigDecimal("10"),
                        rm.getUnitType(),
                        new BigDecimal("12.50"),
                        "GRN line"))
        ));

        return purchasingService.createPurchase(new RawMaterialPurchaseRequest(
                supplier.getId(),
                invoiceNumber,
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
                "Invoice line"))
        ));
    }

    private MockMultipartFile rawMaterialCatalogCsv(String skuCode) {
        String csv = String.join("\n",
                "brand,product_name,sku_code,category,unit_of_measure,gst_rate",
                "CRIMPORT,Imported Material," + skuCode + ",RAW_MATERIAL,KG,18.00"
        );
        return new MockMultipartFile(
                "file",
                "catalog-import.csv",
                "text/csv",
                csv.getBytes(StandardCharsets.UTF_8)
        );
    }

    private MonthEndChecklistItemDto findChecklistItem(MonthEndChecklistDto checklist, String key) {
        return checklist.items().stream()
                .filter(item -> key.equals(item.key()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Checklist item missing: " + key));
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

    private void forceClosePeriod(Long periodId, String requestNote, String approvalNote) {
        authenticate("maker.user", "ROLE_ACCOUNTING");
        accountingPeriodService.requestPeriodClose(periodId, new PeriodCloseRequestActionRequest(requestNote, true));
        authenticate("checker.user", "ROLE_ACCOUNTING");
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

    private static String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
