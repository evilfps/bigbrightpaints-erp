package com.bigbrightpaints.erp.e2e.accounting;

import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountType;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalLine;
import com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocationRepository;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryReference;
import com.bigbrightpaints.erp.modules.inventory.domain.MaterialType;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterial;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialBatch;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialBatchRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialMovement;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialMovementRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialRepository;
import com.bigbrightpaints.erp.modules.purchasing.domain.GoodsReceipt;
import com.bigbrightpaints.erp.modules.purchasing.domain.GoodsReceiptRepository;
import com.bigbrightpaints.erp.modules.purchasing.domain.RawMaterialPurchase;
import com.bigbrightpaints.erp.modules.purchasing.domain.RawMaterialPurchaseRepository;
import com.bigbrightpaints.erp.test.AbstractIntegrationTest;
import com.bigbrightpaints.erp.test.support.TestDateUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("E2E: Procure-to-Pay")
class ProcureToPayE2ETest extends AbstractIntegrationTest {

    private static final String COMPANY_CODE = "P2P-E2E";
    private static final String ADMIN_EMAIL = "p2p-e2e@bbp.com";
    private static final String ADMIN_PASSWORD = "p2p123";

    @Autowired private TestRestTemplate rest;
    @Autowired private CompanyRepository companyRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private RawMaterialRepository rawMaterialRepository;
    @Autowired private RawMaterialBatchRepository rawMaterialBatchRepository;
    @Autowired private RawMaterialMovementRepository rawMaterialMovementRepository;
    @Autowired private GoodsReceiptRepository goodsReceiptRepository;
    @Autowired private JournalEntryRepository journalEntryRepository;
    @Autowired private RawMaterialPurchaseRepository purchaseRepository;
    @Autowired private PartnerSettlementAllocationRepository settlementAllocationRepository;

    private HttpHeaders headers;
    private Company company;
    private Account inventory;
    private Account cash;

    @BeforeEach
    void setup() {
        dataSeeder.ensureUser(ADMIN_EMAIL, ADMIN_PASSWORD, "P2P Admin", COMPANY_CODE,
                List.of("ROLE_ADMIN", "ROLE_ACCOUNTING", "ROLE_FACTORY"));
        company = companyRepository.findByCodeIgnoreCase(COMPANY_CODE).orElseThrow();
        headers = authHeaders();
        inventory = ensureAccount("INV-P2P-E2E", "P2P Inventory", AccountType.ASSET);
        cash = ensureAccount("CASH-P2P-E2E", "P2P Cash", AccountType.ASSET);
        Account gstInput = ensureAccount("GST-IN-P2P-E2E", "GST Input Tax", AccountType.ASSET);
        Account gstOutput = ensureAccount("GST-OUT-P2P-E2E", "GST Output Tax", AccountType.LIABILITY);
        if (company.getStateCode() == null
                || company.getGstInputTaxAccountId() == null
                || company.getGstOutputTaxAccountId() == null) {
            company.setStateCode("27");
            company.setGstInputTaxAccountId(gstInput.getId());
            company.setGstOutputTaxAccountId(gstOutput.getId());
            companyRepository.save(company);
        }
    }

    @Test
    @DisplayName("Purchase -> receipt -> settlement updates stock and clears outstanding")
    void purchaseToPayHappyPath() {
        LocalDate entryDate = TestDateUtils.safeDate(company);
        Long supplierId = createSupplier("P2P Supplier", "P2P-" + shortSuffix());
        Long rawMaterialId = createRawMaterial("P2P Raw Material", "RM-" + shortSuffix(), inventory.getId());

        BigDecimal quantity = new BigDecimal("10");
        BigDecimal costPerUnit = new BigDecimal("12.50");
        BigDecimal totalAmount = quantity.multiply(costPerUnit);
        PurchaseWorkflowIds workflow = createPurchaseOrderAndReceipt(supplierId, rawMaterialId, quantity, costPerUnit, entryDate);
        GoodsReceipt goodsReceipt = goodsReceiptRepository.findById(workflow.goodsReceiptId()).orElseThrow();
        int receiptMovementsBefore = rawMaterialMovementRepository
                .findByRawMaterialCompanyAndReferenceTypeAndReferenceId(
                        company,
                        InventoryReference.GOODS_RECEIPT,
                        goodsReceipt.getReceiptNumber())
                .size();

        Map<String, Object> line = new HashMap<>();
        line.put("rawMaterialId", rawMaterialId);
        line.put("quantity", quantity);
        line.put("costPerUnit", costPerUnit);

        Map<String, Object> purchaseReq = new HashMap<>();
        purchaseReq.put("supplierId", supplierId);
        purchaseReq.put("invoiceNumber", "INV-" + shortSuffix());
        purchaseReq.put("invoiceDate", entryDate);
        purchaseReq.put("purchaseOrderId", workflow.purchaseOrderId());
        purchaseReq.put("goodsReceiptId", workflow.goodsReceiptId());
        purchaseReq.put("lines", List.of(line));

        ResponseEntity<Map> purchaseResp = rest.exchange(
                "/api/v1/purchasing/raw-material-purchases",
                HttpMethod.POST,
                new HttpEntity<>(purchaseReq, headers),
                Map.class);
        assertThat(purchaseResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> purchaseData = (Map<String, Object>) purchaseResp.getBody().get("data");
        Long purchaseId = ((Number) purchaseData.get("id")).longValue();

        RawMaterial material = rawMaterialRepository.findById(rawMaterialId).orElseThrow();
        assertThat(material.getCurrentStock()).isEqualByComparingTo(quantity);

        List<RawMaterialBatch> batches = rawMaterialBatchRepository.findByRawMaterial(material);
        assertThat(batches).hasSize(1);
        RawMaterialBatch batch = batches.get(0);
        List<RawMaterialMovement> movements = rawMaterialMovementRepository.findByRawMaterialBatch(batch);
        assertThat(movements).hasSize(1);
        RawMaterialMovement movement = movements.get(0);
        assertThat(movement.getReferenceType()).isEqualTo(InventoryReference.GOODS_RECEIPT);
        assertThat(movement.getMovementType()).isEqualTo("RECEIPT");
        assertThat(movement.getQuantity()).isEqualByComparingTo(quantity);
        assertThat(movement.getJournalEntryId()).isNotNull();
        int receiptMovementsAfter = rawMaterialMovementRepository
                .findByRawMaterialCompanyAndReferenceTypeAndReferenceId(
                        company,
                        InventoryReference.GOODS_RECEIPT,
                        goodsReceipt.getReceiptNumber())
                .size();
        assertThat(receiptMovementsAfter).isEqualTo(receiptMovementsBefore);

        Map<String, Object> allocation = Map.of(
                "purchaseId", purchaseId,
                "appliedAmount", totalAmount
        );
        String settlementRef = "SET-" + shortSuffix();
        Map<String, Object> settlementReq = new HashMap<>();
        settlementReq.put("supplierId", supplierId);
        settlementReq.put("cashAccountId", cash.getId());
        settlementReq.put("settlementDate", entryDate);
        settlementReq.put("referenceNumber", settlementRef);
        settlementReq.put("idempotencyKey", settlementRef);
        settlementReq.put("allocations", List.of(allocation));

        ResponseEntity<Map> settleResp = rest.exchange(
                "/api/v1/accounting/settlements/suppliers",
                HttpMethod.POST,
                new HttpEntity<>(settlementReq, headers),
                Map.class);
        assertThat(settleResp.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<Map> settleRepeat = rest.exchange(
                "/api/v1/accounting/settlements/suppliers",
                HttpMethod.POST,
                new HttpEntity<>(settlementReq, headers),
                Map.class);
        assertThat(settleRepeat.getStatusCode()).isEqualTo(HttpStatus.OK);

        RawMaterialPurchase purchase = purchaseRepository.findById(purchaseId).orElseThrow();
        assertThat(purchase.getOutstandingAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(purchase.getStatus()).isEqualTo("PAID");
        assertThat(settlementAllocationRepository.findByCompanyAndIdempotencyKey(company, settlementRef))
                .hasSize(1);
    }

    @Test
    @DisplayName("Purchase tax computed balances inventory + tax to payable")
    void purchaseComputedTax_BalancesJournalTotals() {
        LocalDate entryDate = TestDateUtils.safeDate(company);
        Long supplierId = createSupplier("P2P Tax Computed Supplier", "P2P-TAX-C-" + shortSuffix());
        Long rawMaterialId = createRawMaterial("P2P Tax Material", "RM-TAX-C-" + shortSuffix(), inventory.getId());

        BigDecimal quantity = new BigDecimal("5");
        BigDecimal costPerUnit = new BigDecimal("100.00");
        BigDecimal taxRate = new BigDecimal("18.00");
        BigDecimal expectedInventory = costPerUnit.multiply(quantity);
        BigDecimal expectedTax = expectedInventory.multiply(taxRate)
                .divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
        BigDecimal expectedTotal = expectedInventory.add(expectedTax);

        PurchaseWorkflowIds workflow = createPurchaseOrderAndReceipt(supplierId, rawMaterialId, quantity, costPerUnit, entryDate);

        Map<String, Object> line = new HashMap<>();
        line.put("rawMaterialId", rawMaterialId);
        line.put("quantity", quantity);
        line.put("costPerUnit", costPerUnit);
        line.put("taxRate", taxRate);
        line.put("taxInclusive", false);

        Map<String, Object> purchaseReq = new HashMap<>();
        purchaseReq.put("supplierId", supplierId);
        purchaseReq.put("invoiceNumber", "INV-TAX-C-" + shortSuffix());
        purchaseReq.put("invoiceDate", entryDate);
        purchaseReq.put("purchaseOrderId", workflow.purchaseOrderId());
        purchaseReq.put("goodsReceiptId", workflow.goodsReceiptId());
        purchaseReq.put("lines", List.of(line));

        ResponseEntity<Map> purchaseResp = rest.exchange(
                "/api/v1/purchasing/raw-material-purchases",
                HttpMethod.POST,
                new HttpEntity<>(purchaseReq, headers),
                Map.class);
        assertThat(purchaseResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> purchaseData = (Map<String, Object>) purchaseResp.getBody().get("data");
        Long purchaseId = ((Number) purchaseData.get("id")).longValue();

        RawMaterialPurchase purchase = purchaseRepository.findById(purchaseId).orElseThrow();
        assertThat(purchase.getTaxAmount()).isEqualByComparingTo(expectedTax);
        assertThat(purchase.getTotalAmount()).isEqualByComparingTo(expectedTotal);
        assertPurchaseJournalTotals(purchase.getJournalEntry().getId(), expectedInventory, expectedTax, expectedTotal);
    }

    @Test
    @DisplayName("Purchase tax provided balances inventory + tax to payable")
    void purchaseProvidedTax_BalancesJournalTotals() {
        LocalDate entryDate = TestDateUtils.safeDate(company);
        Long supplierId = createSupplier("P2P Tax Provided Supplier", "P2P-TAX-P-" + shortSuffix());
        Long rawMaterialId = createRawMaterial("P2P Tax Provided Material", "RM-TAX-P-" + shortSuffix(), inventory.getId());

        BigDecimal quantity = new BigDecimal("4");
        BigDecimal costPerUnit = new BigDecimal("125.00");
        BigDecimal expectedInventory = costPerUnit.multiply(quantity);
        BigDecimal providedTax = new BigDecimal("91.11");
        BigDecimal expectedTotal = expectedInventory.add(providedTax);

        PurchaseWorkflowIds workflow = createPurchaseOrderAndReceipt(supplierId, rawMaterialId, quantity, costPerUnit, entryDate);

        Map<String, Object> line = new HashMap<>();
        line.put("rawMaterialId", rawMaterialId);
        line.put("quantity", quantity);
        line.put("costPerUnit", costPerUnit);

        Map<String, Object> purchaseReq = new HashMap<>();
        purchaseReq.put("supplierId", supplierId);
        purchaseReq.put("invoiceNumber", "INV-TAX-P-" + shortSuffix());
        purchaseReq.put("invoiceDate", entryDate);
        purchaseReq.put("purchaseOrderId", workflow.purchaseOrderId());
        purchaseReq.put("goodsReceiptId", workflow.goodsReceiptId());
        purchaseReq.put("taxAmount", providedTax);
        purchaseReq.put("lines", List.of(line));

        ResponseEntity<Map> purchaseResp = rest.exchange(
                "/api/v1/purchasing/raw-material-purchases",
                HttpMethod.POST,
                new HttpEntity<>(purchaseReq, headers),
                Map.class);
        assertThat(purchaseResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> purchaseData = (Map<String, Object>) purchaseResp.getBody().get("data");
        Long purchaseId = ((Number) purchaseData.get("id")).longValue();

        RawMaterialPurchase purchase = purchaseRepository.findById(purchaseId).orElseThrow();
        assertThat(purchase.getTaxAmount()).isEqualByComparingTo(providedTax);
        assertThat(purchase.getTotalAmount()).isEqualByComparingTo(expectedTotal);
        assertPurchaseJournalTotals(purchase.getJournalEntry().getId(), expectedInventory, providedTax, expectedTotal);
    }

    @Test
    @DisplayName("Goods receipt quantity cannot exceed purchase order quantity")
    void goodsReceiptCannotExceedPurchaseOrder() {
        LocalDate entryDate = TestDateUtils.safeDate(company);
        Long supplierId = createSupplier("P2P Receipt Supplier", "P2P-GRN-" + shortSuffix());
        Long rawMaterialId = createRawMaterial("P2P Receipt Material", "RM-GRN-" + shortSuffix(), inventory.getId());

        Long purchaseOrderId = createPurchaseOrder(supplierId, rawMaterialId, new BigDecimal("5"),
                new BigDecimal("10.00"), entryDate);

        Map<String, Object> grLine = new HashMap<>();
        grLine.put("rawMaterialId", rawMaterialId);
        grLine.put("quantity", new BigDecimal("6"));
        grLine.put("costPerUnit", new BigDecimal("10.00"));
        grLine.put("unit", "KG");
        grLine.put("batchCode", "GRN-" + shortSuffix());

        Map<String, Object> grReq = new HashMap<>();
        grReq.put("purchaseOrderId", purchaseOrderId);
        grReq.put("receiptNumber", "GRN-OVER-" + shortSuffix());
        grReq.put("receiptDate", entryDate);
        grReq.put("idempotencyKey", "GRN-IDEMP-" + shortSuffix());
        grReq.put("lines", List.of(grLine));

        ResponseEntity<Map> grResp = rest.exchange(
                "/api/v1/purchasing/goods-receipts",
                HttpMethod.POST,
                new HttpEntity<>(grReq, headers),
                Map.class);
        assertThat(grResp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("Purchase invoice quantity must match goods receipt quantity")
    void purchaseInvoiceRejectsQuantityMismatch() {
        LocalDate entryDate = TestDateUtils.safeDate(company);
        Long supplierId = createSupplier("P2P Invoice Supplier", "P2P-INV-" + shortSuffix());
        Long rawMaterialId = createRawMaterial("P2P Invoice Material", "RM-INV-" + shortSuffix(), inventory.getId());

        PurchaseWorkflowIds workflow = createPurchaseOrderAndReceipt(supplierId, rawMaterialId,
                new BigDecimal("5"), new BigDecimal("10.00"), entryDate);

        Map<String, Object> line = new HashMap<>();
        line.put("rawMaterialId", rawMaterialId);
        line.put("quantity", new BigDecimal("6"));
        line.put("costPerUnit", new BigDecimal("10.00"));

        Map<String, Object> purchaseReq = new HashMap<>();
        purchaseReq.put("supplierId", supplierId);
        purchaseReq.put("invoiceNumber", "INV-OVER-" + shortSuffix());
        purchaseReq.put("invoiceDate", entryDate);
        purchaseReq.put("purchaseOrderId", workflow.purchaseOrderId());
        purchaseReq.put("goodsReceiptId", workflow.goodsReceiptId());
        purchaseReq.put("lines", List.of(line));

        ResponseEntity<Map> purchaseResp = rest.exchange(
                "/api/v1/purchasing/raw-material-purchases",
                HttpMethod.POST,
                new HttpEntity<>(purchaseReq, headers),
                Map.class);
        assertThat(purchaseResp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("Raw material intake is disabled by default")
    void rawMaterialIntakeDisabledByDefault() {
        Long supplierId = createSupplier("Intake Supplier", "INTAKE-" + shortSuffix());
        Long rawMaterialId = createRawMaterial("Intake Raw Material", "RM-INTAKE-" + shortSuffix(), inventory.getId());

        Map<String, Object> intakeReq = new HashMap<>();
        intakeReq.put("rawMaterialId", rawMaterialId);
        intakeReq.put("batchCode", "INTAKE-" + shortSuffix());
        intakeReq.put("quantity", new BigDecimal("5"));
        intakeReq.put("unit", "KG");
        intakeReq.put("costPerUnit", new BigDecimal("10.00"));
        intakeReq.put("supplierId", supplierId);
        intakeReq.put("notes", "Adjustment-only intake");

        ResponseEntity<Map> resp = rest.exchange(
                "/api/v1/raw-materials/intake",
                HttpMethod.POST,
                new HttpEntity<>(intakeReq, headers),
                Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("Supplier payment with allocations updates purchase outstanding")
    void supplierPaymentAllocatesToPurchase() {
        LocalDate entryDate = TestDateUtils.safeDate(company);
        Long supplierId = createSupplier("P2P Payment Supplier", "PAY-" + shortSuffix());
        Long rawMaterialId = createRawMaterial("Payment Material", "RM-PAY-" + shortSuffix(), inventory.getId());

        BigDecimal quantity = new BigDecimal("5");
        BigDecimal costPerUnit = new BigDecimal("10.00");
        BigDecimal totalAmount = quantity.multiply(costPerUnit);
        PurchaseWorkflowIds workflow = createPurchaseOrderAndReceipt(supplierId, rawMaterialId, quantity, costPerUnit, entryDate);

        Map<String, Object> line = new HashMap<>();
        line.put("rawMaterialId", rawMaterialId);
        line.put("quantity", quantity);
        line.put("costPerUnit", costPerUnit);

        Map<String, Object> purchaseReq = new HashMap<>();
        purchaseReq.put("supplierId", supplierId);
        purchaseReq.put("invoiceNumber", "INV-PAY-" + shortSuffix());
        purchaseReq.put("invoiceDate", entryDate);
        purchaseReq.put("purchaseOrderId", workflow.purchaseOrderId());
        purchaseReq.put("goodsReceiptId", workflow.goodsReceiptId());
        purchaseReq.put("lines", List.of(line));

        ResponseEntity<Map> purchaseResp = rest.exchange(
                "/api/v1/purchasing/raw-material-purchases",
                HttpMethod.POST,
                new HttpEntity<>(purchaseReq, headers),
                Map.class);
        assertThat(purchaseResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> purchaseData = (Map<String, Object>) purchaseResp.getBody().get("data");
        Long purchaseId = ((Number) purchaseData.get("id")).longValue();

        Map<String, Object> allocation = Map.of(
                "purchaseId", purchaseId,
                "appliedAmount", totalAmount
        );
        Map<String, Object> paymentReq = new HashMap<>();
        paymentReq.put("supplierId", supplierId);
        paymentReq.put("cashAccountId", cash.getId());
        paymentReq.put("amount", totalAmount);
        String paymentRef = "PAY-" + shortSuffix();
        paymentReq.put("referenceNumber", paymentRef);
        paymentReq.put("idempotencyKey", paymentRef);
        paymentReq.put("memo", "Supplier payment allocation");
        paymentReq.put("allocations", List.of(allocation));

        ResponseEntity<Map> paymentResp = rest.exchange(
                "/api/v1/accounting/suppliers/payments",
                HttpMethod.POST,
                new HttpEntity<>(paymentReq, headers),
                Map.class);
        assertThat(paymentResp.getStatusCode()).isEqualTo(HttpStatus.OK);

        RawMaterialPurchase purchase = purchaseRepository.findById(purchaseId).orElseThrow();
        assertThat(purchase.getOutstandingAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(purchase.getStatus()).isEqualTo("PAID");
    }

    @Test
    @DisplayName("Debit note clears purchase outstanding and sets status to VOID")
    void purchaseDebitNoteClearsOutstanding() {
        LocalDate entryDate = TestDateUtils.safeDate(company);
        Long supplierId = createSupplier("P2P Debit Supplier", "DN-" + shortSuffix());
        Long rawMaterialId = createRawMaterial("Debit Note Material", "RM-DN-" + shortSuffix(), inventory.getId());

        BigDecimal quantity = new BigDecimal("5");
        BigDecimal costPerUnit = new BigDecimal("20.00");
        PurchaseWorkflowIds workflow = createPurchaseOrderAndReceipt(supplierId, rawMaterialId, quantity, costPerUnit, entryDate);

        Map<String, Object> line = new HashMap<>();
        line.put("rawMaterialId", rawMaterialId);
        line.put("quantity", quantity);
        line.put("costPerUnit", costPerUnit);

        String invoiceNumber = "INV-DN-" + shortSuffix();
        Map<String, Object> purchaseReq = new HashMap<>();
        purchaseReq.put("supplierId", supplierId);
        purchaseReq.put("invoiceNumber", invoiceNumber);
        purchaseReq.put("invoiceDate", entryDate);
        purchaseReq.put("purchaseOrderId", workflow.purchaseOrderId());
        purchaseReq.put("goodsReceiptId", workflow.goodsReceiptId());
        purchaseReq.put("lines", List.of(line));

        ResponseEntity<Map> purchaseResp = rest.exchange(
                "/api/v1/purchasing/raw-material-purchases",
                HttpMethod.POST,
                new HttpEntity<>(purchaseReq, headers),
                Map.class);
        assertThat(purchaseResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> purchaseData = (Map<String, Object>) purchaseResp.getBody().get("data");
        Long purchaseId = ((Number) purchaseData.get("id")).longValue();

        Map<String, Object> debitNoteReq = new HashMap<>();
        debitNoteReq.put("purchaseId", purchaseId);
        debitNoteReq.put("referenceNumber", "DN-" + shortSuffix());
        debitNoteReq.put("memo", "Debit note test");

        ResponseEntity<Map> debitResp = rest.exchange(
                "/api/v1/accounting/debit-notes",
                HttpMethod.POST,
                new HttpEntity<>(debitNoteReq, headers),
                Map.class);
        assertThat(debitResp.getStatusCode()).isEqualTo(HttpStatus.OK);

        RawMaterialPurchase purchase = purchaseRepository.findById(purchaseId).orElseThrow();
        assertThat(purchase.getOutstandingAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(purchase.getStatus()).isEqualTo("VOID");

        Map<String, Object> duplicateReq = new HashMap<>();
        duplicateReq.put("purchaseId", purchaseId);
        duplicateReq.put("referenceNumber", "DN-DUP-" + shortSuffix());
        duplicateReq.put("memo", "Second debit note should fail");

        ResponseEntity<Map> duplicateResp = rest.exchange(
                "/api/v1/accounting/debit-notes",
                HttpMethod.POST,
                new HttpEntity<>(duplicateReq, headers),
                Map.class);
        assertThat(duplicateResp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("Partial debit note reduces outstanding and sets status PARTIAL")
    void purchasePartialDebitNoteReducesOutstanding() {
        LocalDate entryDate = TestDateUtils.safeDate(company);
        Long supplierId = createSupplier("P2P Partial Debit Supplier", "DN-PART-" + shortSuffix());
        Long rawMaterialId = createRawMaterial("Partial Debit Material", "RM-DN-PART-" + shortSuffix(), inventory.getId());

        BigDecimal quantity = new BigDecimal("5");
        BigDecimal costPerUnit = new BigDecimal("20.00");
        BigDecimal totalAmount = quantity.multiply(costPerUnit);
        PurchaseWorkflowIds workflow = createPurchaseOrderAndReceipt(supplierId, rawMaterialId, quantity, costPerUnit, entryDate);

        Map<String, Object> line = new HashMap<>();
        line.put("rawMaterialId", rawMaterialId);
        line.put("quantity", quantity);
        line.put("costPerUnit", costPerUnit);

        String invoiceNumber = "INV-DN-PART-" + shortSuffix();
        Map<String, Object> purchaseReq = new HashMap<>();
        purchaseReq.put("supplierId", supplierId);
        purchaseReq.put("invoiceNumber", invoiceNumber);
        purchaseReq.put("invoiceDate", entryDate);
        purchaseReq.put("purchaseOrderId", workflow.purchaseOrderId());
        purchaseReq.put("goodsReceiptId", workflow.goodsReceiptId());
        purchaseReq.put("lines", List.of(line));

        ResponseEntity<Map> purchaseResp = rest.exchange(
                "/api/v1/purchasing/raw-material-purchases",
                HttpMethod.POST,
                new HttpEntity<>(purchaseReq, headers),
                Map.class);
        assertThat(purchaseResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> purchaseData = (Map<String, Object>) purchaseResp.getBody().get("data");
        Long purchaseId = ((Number) purchaseData.get("id")).longValue();

        BigDecimal debitAmount = new BigDecimal("40.00");
        Map<String, Object> debitNoteReq = new HashMap<>();
        debitNoteReq.put("purchaseId", purchaseId);
        debitNoteReq.put("amount", debitAmount);
        debitNoteReq.put("referenceNumber", "DN-PART-" + shortSuffix());
        debitNoteReq.put("memo", "Partial debit note");

        ResponseEntity<Map> debitResp = rest.exchange(
                "/api/v1/accounting/debit-notes",
                HttpMethod.POST,
                new HttpEntity<>(debitNoteReq, headers),
                Map.class);
        assertThat(debitResp.getStatusCode()).isEqualTo(HttpStatus.OK);

        RawMaterialPurchase purchase = purchaseRepository.findById(purchaseId).orElseThrow();
        assertThat(purchase.getOutstandingAmount()).isEqualByComparingTo(totalAmount.subtract(debitAmount));
        assertThat(purchase.getStatus()).isEqualTo("PARTIAL");
    }

    @Test
    @DisplayName("Debit note is idempotent by reference for the same purchase")
    void purchaseDebitNoteIdempotentByReference() {
        LocalDate entryDate = TestDateUtils.safeDate(company);
        Long supplierId = createSupplier("P2P Idempotent Supplier", "DN-IDEMP-" + shortSuffix());
        Long rawMaterialId = createRawMaterial("Idempotent Debit Material", "RM-DN-IDEMP-" + shortSuffix(), inventory.getId());

        BigDecimal quantity = new BigDecimal("3");
        BigDecimal costPerUnit = new BigDecimal("30.00");
        BigDecimal totalAmount = quantity.multiply(costPerUnit);
        PurchaseWorkflowIds workflow = createPurchaseOrderAndReceipt(supplierId, rawMaterialId, quantity, costPerUnit, entryDate);

        Map<String, Object> line = new HashMap<>();
        line.put("rawMaterialId", rawMaterialId);
        line.put("quantity", quantity);
        line.put("costPerUnit", costPerUnit);

        String invoiceNumber = "INV-IDEMP-" + shortSuffix();
        Map<String, Object> purchaseReq = new HashMap<>();
        purchaseReq.put("supplierId", supplierId);
        purchaseReq.put("invoiceNumber", invoiceNumber);
        purchaseReq.put("invoiceDate", entryDate);
        purchaseReq.put("purchaseOrderId", workflow.purchaseOrderId());
        purchaseReq.put("goodsReceiptId", workflow.goodsReceiptId());
        purchaseReq.put("lines", List.of(line));

        ResponseEntity<Map> purchaseResp = rest.exchange(
                "/api/v1/purchasing/raw-material-purchases",
                HttpMethod.POST,
                new HttpEntity<>(purchaseReq, headers),
                Map.class);
        assertThat(purchaseResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> purchaseData = (Map<String, Object>) purchaseResp.getBody().get("data");
        Long purchaseId = ((Number) purchaseData.get("id")).longValue();

        String reference = "DN-IDEMP-" + shortSuffix();
        Map<String, Object> debitNoteReq = new HashMap<>();
        debitNoteReq.put("purchaseId", purchaseId);
        debitNoteReq.put("referenceNumber", reference);
        debitNoteReq.put("memo", "Debit note idempotency test");

        ResponseEntity<Map> first = rest.exchange(
                "/api/v1/accounting/debit-notes",
                HttpMethod.POST,
                new HttpEntity<>(debitNoteReq, headers),
                Map.class);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<Map> second = rest.exchange(
                "/api/v1/accounting/debit-notes",
                HttpMethod.POST,
                new HttpEntity<>(debitNoteReq, headers),
                Map.class);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);

        List<JournalEntry> entries = journalEntryRepository.findAll().stream()
                .filter(entry -> reference.equals(entry.getReferenceNumber()))
                .toList();
        assertThat(entries).hasSize(1);

        RawMaterialPurchase purchase = purchaseRepository.findById(purchaseId).orElseThrow();
        assertThat(purchase.getOutstandingAmount()).isEqualByComparingTo(totalAmount.negate());
        assertThat(purchase.getStatus()).isEqualTo("VOID");
    }

    @Test
    @DisplayName("Debit note allowed after purchase is fully settled")
    void purchaseDebitNoteAllowedAfterSettlement() {
        LocalDate entryDate = TestDateUtils.safeDate(company);
        Long supplierId = createSupplier("P2P Paid Supplier", "DN-PAID-" + shortSuffix());
        Long rawMaterialId = createRawMaterial("Paid Debit Material", "RM-DN-PAID-" + shortSuffix(), inventory.getId());

        BigDecimal quantity = new BigDecimal("4");
        BigDecimal costPerUnit = new BigDecimal("25.00");
        BigDecimal totalAmount = quantity.multiply(costPerUnit);
        PurchaseWorkflowIds workflow = createPurchaseOrderAndReceipt(supplierId, rawMaterialId, quantity, costPerUnit, entryDate);

        Map<String, Object> line = new HashMap<>();
        line.put("rawMaterialId", rawMaterialId);
        line.put("quantity", quantity);
        line.put("costPerUnit", costPerUnit);

        String invoiceNumber = "INV-PAID-" + shortSuffix();
        Map<String, Object> purchaseReq = new HashMap<>();
        purchaseReq.put("supplierId", supplierId);
        purchaseReq.put("invoiceNumber", invoiceNumber);
        purchaseReq.put("invoiceDate", entryDate);
        purchaseReq.put("purchaseOrderId", workflow.purchaseOrderId());
        purchaseReq.put("goodsReceiptId", workflow.goodsReceiptId());
        purchaseReq.put("lines", List.of(line));

        ResponseEntity<Map> purchaseResp = rest.exchange(
                "/api/v1/purchasing/raw-material-purchases",
                HttpMethod.POST,
                new HttpEntity<>(purchaseReq, headers),
                Map.class);
        assertThat(purchaseResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> purchaseData = (Map<String, Object>) purchaseResp.getBody().get("data");
        Long purchaseId = ((Number) purchaseData.get("id")).longValue();

        Map<String, Object> allocation = Map.of(
                "purchaseId", purchaseId,
                "appliedAmount", totalAmount
        );
        String settlementRef = "SET-PAID-" + shortSuffix();
        Map<String, Object> settlementReq = new HashMap<>();
        settlementReq.put("supplierId", supplierId);
        settlementReq.put("cashAccountId", cash.getId());
        settlementReq.put("settlementDate", entryDate);
        settlementReq.put("referenceNumber", settlementRef);
        settlementReq.put("idempotencyKey", settlementRef);
        settlementReq.put("allocations", List.of(allocation));

        ResponseEntity<Map> settleResp = rest.exchange(
                "/api/v1/accounting/settlements/suppliers",
                HttpMethod.POST,
                new HttpEntity<>(settlementReq, headers),
                Map.class);
        assertThat(settleResp.getStatusCode()).isEqualTo(HttpStatus.OK);

        RawMaterialPurchase paidPurchase = purchaseRepository.findById(purchaseId).orElseThrow();
        assertThat(paidPurchase.getOutstandingAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(paidPurchase.getStatus()).isEqualTo("PAID");

        Map<String, Object> debitNoteReq = new HashMap<>();
        debitNoteReq.put("purchaseId", purchaseId);
        debitNoteReq.put("referenceNumber", "DN-PAID-" + shortSuffix());
        debitNoteReq.put("memo", "Debit note after settlement");

        ResponseEntity<Map> debitResp = rest.exchange(
                "/api/v1/accounting/debit-notes",
                HttpMethod.POST,
                new HttpEntity<>(debitNoteReq, headers),
                Map.class);
        assertThat(debitResp.getStatusCode()).isEqualTo(HttpStatus.OK);

        RawMaterialPurchase refreshed = purchaseRepository.findById(purchaseId).orElseThrow();
        assertThat(refreshed.getOutstandingAmount()).isEqualByComparingTo(totalAmount.negate());
        assertThat(refreshed.getStatus()).isEqualTo("VOID");
    }

    @Test
    @DisplayName("Purchase return reduces stock and records movement")
    void purchaseReturnReducesStock() {
        LocalDate entryDate = TestDateUtils.safeDate(company);
        Long supplierId = createSupplier("P2P Return Supplier", "RET-" + shortSuffix());
        Long rawMaterialId = createRawMaterial("Return Raw Material", "RM-RET-" + shortSuffix(), inventory.getId());

        BigDecimal purchaseQty = new BigDecimal("10");
        BigDecimal unitCost = new BigDecimal("8.00");
        PurchaseWorkflowIds workflow = createPurchaseOrderAndReceipt(supplierId, rawMaterialId, purchaseQty, unitCost, entryDate);

        Map<String, Object> line = new HashMap<>();
        line.put("rawMaterialId", rawMaterialId);
        line.put("quantity", purchaseQty);
        line.put("costPerUnit", unitCost);

        Map<String, Object> purchaseReq = new HashMap<>();
        purchaseReq.put("supplierId", supplierId);
        purchaseReq.put("invoiceNumber", "INV-" + shortSuffix());
        purchaseReq.put("invoiceDate", entryDate);
        purchaseReq.put("purchaseOrderId", workflow.purchaseOrderId());
        purchaseReq.put("goodsReceiptId", workflow.goodsReceiptId());
        purchaseReq.put("lines", List.of(line));

        ResponseEntity<Map> purchaseResp = rest.exchange(
                "/api/v1/purchasing/raw-material-purchases",
                HttpMethod.POST,
                new HttpEntity<>(purchaseReq, headers),
                Map.class);
        assertThat(purchaseResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> purchaseData = (Map<String, Object>) purchaseResp.getBody().get("data");
        Long purchaseId = ((Number) purchaseData.get("id")).longValue();

        RawMaterial afterPurchase = rawMaterialRepository.findById(rawMaterialId).orElseThrow();
        assertThat(afterPurchase.getCurrentStock()).isEqualByComparingTo(purchaseQty);

        String returnRef = "RET-" + shortSuffix();
        Map<String, Object> returnReq = new HashMap<>();
        returnReq.put("supplierId", supplierId);
        returnReq.put("purchaseId", purchaseId);
        returnReq.put("rawMaterialId", rawMaterialId);
        returnReq.put("quantity", new BigDecimal("4"));
        returnReq.put("unitCost", unitCost);
        returnReq.put("referenceNumber", returnRef);
        returnReq.put("returnDate", entryDate);
        returnReq.put("reason", "P2P return test");

        ResponseEntity<Map> returnResp = rest.exchange(
                "/api/v1/purchasing/raw-material-purchases/returns",
                HttpMethod.POST,
                new HttpEntity<>(returnReq, headers),
                Map.class);
        assertThat(returnResp.getStatusCode()).isEqualTo(HttpStatus.OK);

        RawMaterial afterReturn = rawMaterialRepository.findById(rawMaterialId).orElseThrow();
        assertThat(afterReturn.getCurrentStock()).isEqualByComparingTo(new BigDecimal("6"));

        RawMaterialPurchase updatedPurchase = purchaseRepository.findById(purchaseId).orElseThrow();
        assertThat(updatedPurchase.getOutstandingAmount()).isEqualByComparingTo(new BigDecimal("48.00"));
        assertThat(updatedPurchase.getStatus()).isEqualTo("PARTIAL");

        List<RawMaterialMovement> movements = rawMaterialMovementRepository
                .findByReferenceTypeAndReferenceId(InventoryReference.PURCHASE_RETURN, returnRef);
        assertThat(movements).hasSize(1);
        RawMaterialMovement movement = movements.get(0);
        assertThat(movement.getMovementType()).isEqualTo("RETURN");
        assertThat(movement.getQuantity()).isEqualByComparingTo(new BigDecimal("4"));
        assertThat(movement.getJournalEntryId()).isNotNull();
    }

    @Test
    @DisplayName("GST return includes input tax from purchase flow")
    void gstReturnIncludesInputTaxFromPurchases() {
        LocalDate entryDate = TestDateUtils.safeDate(company);
        YearMonth period = YearMonth.from(entryDate);
        Long supplierId = createSupplier("GST Supplier", "GST-" + shortSuffix());
        Long rawMaterialId = createRawMaterial("GST Raw Material", "RM-GST-" + shortSuffix(), inventory.getId());

        ResponseEntity<Map> beforeResp = rest.exchange(
                "/api/v1/accounting/gst/return?period=" + period,
                HttpMethod.GET,
                new HttpEntity<>(headers),
                Map.class);
        assertThat(beforeResp.getStatusCode())
                .withFailMessage("GST return failed: %s", beforeResp.getBody())
                .isEqualTo(HttpStatus.OK);
        Map<String, Object> beforeData = (Map<String, Object>) beforeResp.getBody().get("data");
        BigDecimal beforeInput = amount(beforeData, "inputTax");

        BigDecimal quantity = new BigDecimal("5");
        BigDecimal costPerUnit = new BigDecimal("100.00");
        BigDecimal taxAmount = new BigDecimal("90.00");
        PurchaseWorkflowIds workflow = createPurchaseOrderAndReceipt(supplierId, rawMaterialId, quantity, costPerUnit, entryDate);

        Map<String, Object> line = new HashMap<>();
        line.put("rawMaterialId", rawMaterialId);
        line.put("quantity", quantity);
        line.put("costPerUnit", costPerUnit);

        Map<String, Object> purchaseReq = new HashMap<>();
        purchaseReq.put("supplierId", supplierId);
        purchaseReq.put("invoiceNumber", "INV-GST-" + shortSuffix());
        purchaseReq.put("invoiceDate", entryDate);
        purchaseReq.put("purchaseOrderId", workflow.purchaseOrderId());
        purchaseReq.put("goodsReceiptId", workflow.goodsReceiptId());
        purchaseReq.put("taxAmount", taxAmount);
        purchaseReq.put("lines", List.of(line));

        ResponseEntity<Map> purchaseResp = rest.exchange(
                "/api/v1/purchasing/raw-material-purchases",
                HttpMethod.POST,
                new HttpEntity<>(purchaseReq, headers),
                Map.class);
        assertThat(purchaseResp.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<Map> afterResp = rest.exchange(
                "/api/v1/accounting/gst/return?period=" + period,
                HttpMethod.GET,
                new HttpEntity<>(headers),
                Map.class);
        assertThat(afterResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> afterData = (Map<String, Object>) afterResp.getBody().get("data");
        BigDecimal afterInput = amount(afterData, "inputTax");

        assertThat(afterInput.subtract(beforeInput)).isEqualByComparingTo(taxAmount);
    }

    @Test
    @DisplayName("Purchase return reverses GST input tax")
    void gstReturnReversesInputTaxOnReturn() {
        LocalDate entryDate = TestDateUtils.safeDate(company);
        YearMonth period = YearMonth.from(entryDate);
        Long supplierId = createSupplier("GST Return Supplier", "GST-RET-" + shortSuffix());
        Long rawMaterialId = createRawMaterial("GST Return Material", "RM-GST-RET-" + shortSuffix(), inventory.getId());

        BigDecimal quantity = new BigDecimal("5");
        BigDecimal costPerUnit = new BigDecimal("100.00");
        BigDecimal taxAmount = new BigDecimal("90.00");
        PurchaseWorkflowIds workflow = createPurchaseOrderAndReceipt(supplierId, rawMaterialId, quantity, costPerUnit, entryDate);

        BigDecimal beforeInput = amount(gstReturn(period), "inputTax");

        Map<String, Object> line = new HashMap<>();
        line.put("rawMaterialId", rawMaterialId);
        line.put("quantity", quantity);
        line.put("costPerUnit", costPerUnit);

        Map<String, Object> purchaseReq = new HashMap<>();
        purchaseReq.put("supplierId", supplierId);
        purchaseReq.put("invoiceNumber", "INV-GST-RET-" + shortSuffix());
        purchaseReq.put("invoiceDate", entryDate);
        purchaseReq.put("purchaseOrderId", workflow.purchaseOrderId());
        purchaseReq.put("goodsReceiptId", workflow.goodsReceiptId());
        purchaseReq.put("taxAmount", taxAmount);
        purchaseReq.put("lines", List.of(line));

        ResponseEntity<Map> purchaseResp = rest.exchange(
                "/api/v1/purchasing/raw-material-purchases",
                HttpMethod.POST,
                new HttpEntity<>(purchaseReq, headers),
                Map.class);
        assertThat(purchaseResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> purchaseData = (Map<String, Object>) purchaseResp.getBody().get("data");
        Long purchaseId = ((Number) purchaseData.get("id")).longValue();

        BigDecimal afterPurchaseInput = amount(gstReturn(period), "inputTax");
        assertThat(afterPurchaseInput.subtract(beforeInput)).isEqualByComparingTo(taxAmount);

        Map<String, Object> returnReq = new HashMap<>();
        returnReq.put("supplierId", supplierId);
        returnReq.put("purchaseId", purchaseId);
        returnReq.put("rawMaterialId", rawMaterialId);
        returnReq.put("quantity", quantity);
        returnReq.put("unitCost", costPerUnit);
        returnReq.put("referenceNumber", "RET-GST-" + shortSuffix());
        returnReq.put("returnDate", entryDate);
        returnReq.put("reason", "GST return test");

        ResponseEntity<Map> returnResp = rest.exchange(
                "/api/v1/purchasing/raw-material-purchases/returns",
                HttpMethod.POST,
                new HttpEntity<>(returnReq, headers),
                Map.class);
        assertThat(returnResp.getStatusCode()).isEqualTo(HttpStatus.OK);

        BigDecimal afterReturnInput = amount(gstReturn(period), "inputTax");
        assertThat(afterPurchaseInput.subtract(afterReturnInput)).isEqualByComparingTo(taxAmount);
    }

    @Test
    @DisplayName("GST return ignores draft journal entries")
    void gstReturnIgnoresDraftEntries() {
        LocalDate entryDate = TestDateUtils.safeDate(company);
        YearMonth period = YearMonth.from(entryDate);
        BigDecimal beforeOutput = gstOutputTax(period);

        Account gstOutput = accountRepository.findByCompanyAndCodeIgnoreCase(company, "GST-OUT-P2P-E2E")
                .orElseThrow();

        JournalEntry draft = new JournalEntry();
        draft.setCompany(company);
        draft.setReferenceNumber("GST-DRAFT-" + UUID.randomUUID());
        draft.setEntryDate(entryDate);
        draft.setMemo("Draft GST entry");
        draft.setStatus("DRAFT");

        JournalLine gstLine = new JournalLine();
        gstLine.setJournalEntry(draft);
        gstLine.setAccount(gstOutput);
        gstLine.setDebit(BigDecimal.ZERO);
        gstLine.setCredit(new BigDecimal("12.34"));
        draft.getLines().add(gstLine);

        JournalLine offsetLine = new JournalLine();
        offsetLine.setJournalEntry(draft);
        offsetLine.setAccount(cash);
        offsetLine.setDebit(new BigDecimal("12.34"));
        offsetLine.setCredit(BigDecimal.ZERO);
        draft.getLines().add(offsetLine);
        journalEntryRepository.saveAndFlush(draft);

        BigDecimal afterOutput = gstOutputTax(period);
        assertThat(afterOutput).isEqualByComparingTo(beforeOutput);
    }

    private Long createSupplier(String name, String code) {
        Map<String, Object> req = Map.of(
                "name", name,
                "code", code,
                "contactEmail", "p2p-" + shortSuffix() + "@bbp.com",
                "stateCode", "27",
                "creditLimit", new BigDecimal("25000.00")
        );
        ResponseEntity<Map> resp = rest.exchange(
                "/api/v1/suppliers",
                HttpMethod.POST,
                new HttpEntity<>(req, headers),
                Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> data = (Map<String, Object>) resp.getBody().get("data");
        Long supplierId = ((Number) data.get("id")).longValue();

        ResponseEntity<Map> approveResp = rest.exchange(
                "/api/v1/suppliers/" + supplierId + "/approve",
                HttpMethod.POST,
                new HttpEntity<>(headers),
                Map.class);
        assertThat(approveResp.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<Map> activateResp = rest.exchange(
                "/api/v1/suppliers/" + supplierId + "/activate",
                HttpMethod.POST,
                new HttpEntity<>(headers),
                Map.class);
        assertThat(activateResp.getStatusCode()).isEqualTo(HttpStatus.OK);

        return supplierId;
    }

    private Long createRawMaterial(String name, String sku, Long inventoryAccountId) {
        RawMaterial material = new RawMaterial();
        material.setCompany(company);
        material.setName(name);
        material.setSku(sku);
        material.setUnitType("KG");
        material.setReorderLevel(new BigDecimal("1"));
        material.setMinStock(new BigDecimal("1"));
        material.setMaxStock(new BigDecimal("100"));
        material.setMaterialType(MaterialType.PRODUCTION);
        material.setInventoryAccountId(inventoryAccountId);
        material.setCurrentStock(BigDecimal.ZERO);
        return rawMaterialRepository.save(material).getId();
    }

    private PurchaseWorkflowIds createPurchaseOrderAndReceipt(Long supplierId,
                                                              Long rawMaterialId,
                                                              BigDecimal quantity,
                                                              BigDecimal costPerUnit,
                                                              LocalDate entryDate) {
        Long purchaseOrderId = createPurchaseOrder(supplierId, rawMaterialId, quantity, costPerUnit, entryDate);
        Long goodsReceiptId = createGoodsReceipt(purchaseOrderId, rawMaterialId, quantity, costPerUnit, entryDate);
        return new PurchaseWorkflowIds(purchaseOrderId, goodsReceiptId);
    }

    private Long createPurchaseOrder(Long supplierId,
                                     Long rawMaterialId,
                                     BigDecimal quantity,
                                     BigDecimal costPerUnit,
                                     LocalDate entryDate) {
        Map<String, Object> line = new HashMap<>();
        line.put("rawMaterialId", rawMaterialId);
        line.put("quantity", quantity);
        line.put("costPerUnit", costPerUnit);
        line.put("unit", "KG");

        Map<String, Object> poReq = new HashMap<>();
        poReq.put("supplierId", supplierId);
        poReq.put("orderNumber", "PO-" + shortSuffix());
        poReq.put("orderDate", entryDate);
        poReq.put("lines", List.of(line));

        ResponseEntity<Map> poResp = rest.exchange(
                "/api/v1/purchasing/purchase-orders",
                HttpMethod.POST,
                new HttpEntity<>(poReq, headers),
                Map.class);
        assertThat(poResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> poData = (Map<String, Object>) poResp.getBody().get("data");
        Long purchaseOrderId = ((Number) poData.get("id")).longValue();

        ResponseEntity<Map> approveResp = rest.exchange(
                "/api/v1/purchasing/purchase-orders/" + purchaseOrderId + "/approve",
                HttpMethod.POST,
                new HttpEntity<>(headers),
                Map.class);
        assertThat(approveResp.getStatusCode()).isEqualTo(HttpStatus.OK);

        return purchaseOrderId;
    }

    private Long createGoodsReceipt(Long purchaseOrderId,
                                    Long rawMaterialId,
                                    BigDecimal quantity,
                                    BigDecimal costPerUnit,
                                    LocalDate entryDate) {
        Map<String, Object> grLine = new HashMap<>();
        grLine.put("rawMaterialId", rawMaterialId);
        grLine.put("quantity", quantity);
        grLine.put("costPerUnit", costPerUnit);
        grLine.put("unit", "KG");
        grLine.put("batchCode", "GRN-" + shortSuffix());

        Map<String, Object> grReq = new HashMap<>();
        grReq.put("purchaseOrderId", purchaseOrderId);
        grReq.put("receiptNumber", "GRN-" + shortSuffix());
        grReq.put("receiptDate", entryDate);
        grReq.put("idempotencyKey", "GRN-IDEMP-" + shortSuffix());
        grReq.put("lines", List.of(grLine));

        ResponseEntity<Map> grResp = rest.exchange(
                "/api/v1/purchasing/goods-receipts",
                HttpMethod.POST,
                new HttpEntity<>(grReq, headers),
                Map.class);
        assertThat(grResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> grData = (Map<String, Object>) grResp.getBody().get("data");
        return ((Number) grData.get("id")).longValue();
    }

    private record PurchaseWorkflowIds(Long purchaseOrderId, Long goodsReceiptId) {}

    private Account ensureAccount(String code, String name, AccountType type) {
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

    private void assertPurchaseJournalTotals(Long journalEntryId,
                                             BigDecimal expectedInventory,
                                             BigDecimal expectedTax,
                                             BigDecimal expectedTotal) {
        JournalEntry entry = journalEntryRepository.findById(journalEntryId).orElseThrow();
        Long gstInputId = company.getGstInputTaxAccountId();

        BigDecimal inventoryDebit = sumDebits(entry, inventory.getId());
        BigDecimal taxDebit = sumDebits(entry, gstInputId);
        BigDecimal creditTotal = sumCredits(entry);

        assertThat(inventoryDebit).isEqualByComparingTo(expectedInventory);
        assertThat(taxDebit).isEqualByComparingTo(expectedTax);
        assertThat(creditTotal).isEqualByComparingTo(expectedTotal);
        assertThat(inventoryDebit.add(taxDebit)).isEqualByComparingTo(expectedTotal);
    }

    private BigDecimal sumDebits(JournalEntry entry, Long accountId) {
        if (entry == null || entry.getLines() == null || accountId == null) {
            return BigDecimal.ZERO;
        }
        return entry.getLines().stream()
                .filter(line -> line.getAccount() != null && accountId.equals(line.getAccount().getId()))
                .map(line -> safeAmount(line.getDebit()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal sumCredits(JournalEntry entry) {
        if (entry == null || entry.getLines() == null) {
            return BigDecimal.ZERO;
        }
        return entry.getLines().stream()
                .map(line -> safeAmount(line.getCredit()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal safeAmount(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }

    private BigDecimal amount(Map<String, Object> data, String field) {
        if (data == null) {
            return BigDecimal.ZERO;
        }
        Object value = data.get(field);
        if (value instanceof Number) {
            return new BigDecimal(value.toString());
        }
        if (value instanceof String str && !str.isBlank()) {
            return new BigDecimal(str);
        }
        return BigDecimal.ZERO;
    }

    private Map<String, Object> gstReturn(YearMonth period) {
        ResponseEntity<Map> resp = rest.exchange(
                "/api/v1/accounting/gst/return?period=" + period,
                HttpMethod.GET,
                new HttpEntity<>(headers),
                Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        return (Map<String, Object>) resp.getBody().get("data");
    }

    private BigDecimal gstOutputTax(YearMonth period) {
        return amount(gstReturn(period), "outputTax");
    }

    private HttpHeaders authHeaders() {
        Map<String, Object> req = Map.of(
                "email", ADMIN_EMAIL,
                "password", ADMIN_PASSWORD,
                "companyCode", COMPANY_CODE
        );
        ResponseEntity<Map> login = rest.postForEntity("/api/v1/auth/login", req, Map.class);
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
        String token = (String) login.getBody().get("accessToken");
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        h.setContentType(MediaType.APPLICATION_JSON);
        h.set("X-Company-Code", COMPANY_CODE);
        return h;
    }

    private String shortSuffix() {
        String token = Long.toString(System.nanoTime());
        return token.length() > 8 ? token.substring(token.length() - 8) : token;
    }
}
