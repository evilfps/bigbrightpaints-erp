package com.bigbrightpaints.erp.e2e.accounting;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

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

import com.bigbrightpaints.erp.core.audittrail.AuditActionEvent;
import com.bigbrightpaints.erp.core.audittrail.AuditActionEventRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountType;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalLine;
import com.bigbrightpaints.erp.modules.accounting.domain.PartnerPaymentEvent;
import com.bigbrightpaints.erp.modules.accounting.domain.PartnerPaymentEventRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.PartnerPaymentFlow;
import com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocation;
import com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocationRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.PartnerType;
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
  @Autowired private PartnerPaymentEventRepository partnerPaymentEventRepository;
  @Autowired private RawMaterialPurchaseRepository purchaseRepository;
  @Autowired private PartnerSettlementAllocationRepository settlementAllocationRepository;
  @Autowired private AuditActionEventRepository auditActionEventRepository;

  private HttpHeaders headers;
  private Company company;
  private Account inventory;
  private Account cash;

  @BeforeEach
  void setup() {
    dataSeeder.ensureUser(
        ADMIN_EMAIL,
        ADMIN_PASSWORD,
        "P2P Admin",
        COMPANY_CODE,
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
    Long rawMaterialId =
        createRawMaterial("P2P Raw Material", "RM-" + shortSuffix(), inventory.getId());

    BigDecimal quantity = new BigDecimal("10");
    BigDecimal costPerUnit = new BigDecimal("12.50");
    BigDecimal totalAmount = quantity.multiply(costPerUnit);
    PurchaseWorkflowIds workflow =
        createPurchaseOrderAndReceipt(supplierId, rawMaterialId, quantity, costPerUnit, entryDate);
    UUID flowCorrelationId = UUID.randomUUID();
    HttpHeaders purchaseHeaders = headersWithCorrelationId(headers, flowCorrelationId);
    GoodsReceipt goodsReceipt =
        goodsReceiptRepository.findById(workflow.goodsReceiptId()).orElseThrow();
    int receiptMovementsBefore =
        rawMaterialMovementRepository
            .findByRawMaterialCompanyAndReferenceTypeAndReferenceId(
                company, InventoryReference.GOODS_RECEIPT, goodsReceipt.getReceiptNumber())
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

    ResponseEntity<Map> purchaseResp =
        rest.exchange(
            "/api/v1/purchasing/raw-material-purchases",
            HttpMethod.POST,
            new HttpEntity<>(purchaseReq, purchaseHeaders),
            Map.class);
    assertThat(purchaseResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    Map<String, Object> purchaseData = (Map<String, Object>) purchaseResp.getBody().get("data");
    Long purchaseId = ((Number) purchaseData.get("id")).longValue();

    RawMaterial material = rawMaterialRepository.findById(rawMaterialId).orElseThrow();
    assertThat(material.getCurrentStock()).isEqualByComparingTo(quantity);

    List<RawMaterialBatch> batches = rawMaterialBatchRepository.findByRawMaterial(material);
    assertThat(batches).hasSize(1);
    RawMaterialBatch batch = batches.get(0);
    List<RawMaterialMovement> movements =
        rawMaterialMovementRepository.findByRawMaterialBatch(batch);
    assertThat(movements).hasSize(1);
    RawMaterialMovement movement = movements.get(0);
    assertThat(movement.getReferenceType()).isEqualTo(InventoryReference.GOODS_RECEIPT);
    assertThat(movement.getMovementType()).isEqualTo("RECEIPT");
    assertThat(movement.getQuantity()).isEqualByComparingTo(quantity);
    assertThat(movement.getJournalEntryId()).isNotNull();
    int receiptMovementsAfter =
        rawMaterialMovementRepository
            .findByRawMaterialCompanyAndReferenceTypeAndReferenceId(
                company, InventoryReference.GOODS_RECEIPT, goodsReceipt.getReceiptNumber())
            .size();
    assertThat(receiptMovementsAfter).isEqualTo(receiptMovementsBefore);

    Map<String, Object> allocation =
        Map.of(
            "purchaseId", purchaseId,
            "appliedAmount", totalAmount);
    String settlementRef = "SET-" + shortSuffix();
    Map<String, Object> settlementReq = new HashMap<>();
    settlementReq.put("partnerType", "SUPPLIER");
    settlementReq.put("partnerId", supplierId);
    settlementReq.put("cashAccountId", cash.getId());
    settlementReq.put("settlementDate", entryDate);
    settlementReq.put("referenceNumber", settlementRef);
    settlementReq.put("allocations", List.of(allocation));
    HttpHeaders settlementHeaders =
        headersWithCorrelationId(headersWithIdempotencyKey(settlementRef), flowCorrelationId);

    ResponseEntity<Map> settleResp =
        rest.exchange(
            "/api/v1/accounting/settlements/suppliers",
            HttpMethod.POST,
            new HttpEntity<>(settlementReq, settlementHeaders),
            Map.class);
    assertThat(settleResp.getStatusCode()).isEqualTo(HttpStatus.OK);

    ResponseEntity<Map> settleRepeat =
        rest.exchange(
            "/api/v1/accounting/settlements/suppliers",
            HttpMethod.POST,
            new HttpEntity<>(settlementReq, settlementHeaders),
            Map.class);
    assertThat(settleRepeat.getStatusCode()).isEqualTo(HttpStatus.OK);

    RawMaterialPurchase purchase = purchaseRepository.findById(purchaseId).orElseThrow();
    assertThat(purchase.getOutstandingAmount()).isEqualByComparingTo(BigDecimal.ZERO);
    assertThat(purchase.getStatus()).isEqualTo("PAID");
    List<PartnerSettlementAllocation> settlementAllocations =
        settlementAllocationRepository.findByCompanyAndIdempotencyKey(company, settlementRef);
    assertThat(settlementAllocations).hasSize(1);
    assertThat(purchase.getJournalEntry()).isNotNull();
    JournalEntry settlementJournal =
        journalEntryRepository
            .findByCompanyAndReferenceNumber(company, settlementRef)
            .orElseThrow();
    PartnerPaymentEvent settlementPaymentEvent =
        partnerPaymentEventRepository
            .findByCompanyAndJournalEntry(company, settlementJournal)
            .orElseThrow();
    assertThat(settlementPaymentEvent.getPartnerType()).isEqualTo(PartnerType.SUPPLIER);
    assertThat(settlementPaymentEvent.getSupplier()).isNotNull();
    assertThat(settlementPaymentEvent.getSupplier().getId()).isEqualTo(supplierId);
    assertThat(settlementPaymentEvent.getPaymentFlow())
        .isEqualTo(PartnerPaymentFlow.SUPPLIER_SETTLEMENT);
    assertThat(settlementPaymentEvent.getSourceRoute())
        .isEqualTo("/api/v1/accounting/settlements/suppliers");
    assertThat(settlementPaymentEvent.getIdempotencyKey()).isEqualTo(settlementRef);
    assertThat(settlementPaymentEvent.getReferenceNumber()).isEqualTo(settlementRef);
    assertThat(settlementPaymentEvent.getAmount()).isEqualByComparingTo(totalAmount);
    assertThat(settlementAllocations)
        .allSatisfy(
            settlementRow -> {
              assertThat(settlementRow.getPaymentEvent()).isNotNull();
              assertThat(settlementRow.getPaymentEvent().getId())
                  .isEqualTo(settlementPaymentEvent.getId());
            });

    AuditActionEvent purchaseAudit =
        awaitBusinessAuditEvent(
            company.getId(),
            "ACCOUNTING",
            "SYSTEM_JOURNAL_CREATED",
            "JOURNAL_ENTRY",
            purchase.getJournalEntry().getId().toString());
    AuditActionEvent settlementAudit =
        awaitBusinessAuditEvent(
            company.getId(),
            "ACCOUNTING",
            "SETTLEMENT_JOURNAL_CREATED",
            "JOURNAL_ENTRY",
            settlementJournal.getId().toString());
    assertThat(purchaseAudit.getCorrelationId()).isEqualTo(flowCorrelationId);
    assertThat(settlementAudit.getCorrelationId()).isEqualTo(flowCorrelationId);
    assertThat(settlementAudit.getCorrelationId()).isEqualTo(purchaseAudit.getCorrelationId());

    List<?> purchaseEventTrail = transactionEventTrail(purchase.getJournalEntry().getId());
    assertThat(purchaseEventTrail).isNotEmpty();
    assertThat(purchaseEventTrail)
        .allSatisfy(
            row -> {
              assertThat(row).isInstanceOf(Map.class);
              assertThat(((Map<?, ?>) row).get("correlationId"))
                  .isEqualTo(flowCorrelationId.toString());
            });

    List<?> settlementEventTrail = transactionEventTrail(settlementJournal.getId());
    assertThat(settlementEventTrail).isNotEmpty();
    assertThat(settlementEventTrail)
        .allSatisfy(
            row -> {
              assertThat(row).isInstanceOf(Map.class);
              Map<?, ?> eventTrailRow = (Map<?, ?>) row;
              assertThat(eventTrailRow.get("correlationId"))
                  .isEqualTo(flowCorrelationId.toString());
            });
    List<?> settlementSpecificRows =
        settlementEventTrail.stream()
            .filter(Map.class::isInstance)
            .map(row -> (Map<?, ?>) row)
            .filter(
                row -> {
                  String eventType = String.valueOf(row.get("eventType"));
                  return "SUPPLIER_PAYMENT_POSTED".equals(eventType)
                      || "SETTLEMENT_ALLOCATED".equals(eventType);
                })
            .toList();
    assertThat(settlementSpecificRows).isNotEmpty();
    assertThat(settlementSpecificRows)
        .allSatisfy(
            row -> {
              assertThat(row).isInstanceOf(Map.class);
              assertThat(((Map<?, ?>) row).get("correlationId"))
                  .isEqualTo(flowCorrelationId.toString());
            });
  }

  @Test
  @DisplayName("Goods receipt detail remains readable after linked invoice posting")
  void goodsReceiptDetailRemainsReadableAfterInvoicePosting() {
    LocalDate entryDate = TestDateUtils.safeDate(company);
    Long supplierId = createSupplier("P2P Detail Supplier", "P2P-DETAIL-" + shortSuffix());
    Long rawMaterialId =
        createRawMaterial("P2P Detail Material", "RM-DETAIL-" + shortSuffix(), inventory.getId());
    BigDecimal quantity = new BigDecimal("6");
    BigDecimal costPerUnit = new BigDecimal("15.00");
    PurchaseWorkflowIds workflow =
        createPurchaseOrderAndReceipt(supplierId, rawMaterialId, quantity, costPerUnit, entryDate);

    Map<String, Object> purchaseReq = new HashMap<>();
    purchaseReq.put("supplierId", supplierId);
    purchaseReq.put("invoiceNumber", "INV-DETAIL-" + shortSuffix());
    purchaseReq.put("invoiceDate", entryDate);
    purchaseReq.put("purchaseOrderId", workflow.purchaseOrderId());
    purchaseReq.put("goodsReceiptId", workflow.goodsReceiptId());
    purchaseReq.put(
        "lines",
        List.of(
            Map.of(
                "rawMaterialId", rawMaterialId, "quantity", quantity, "costPerUnit", costPerUnit)));

    ResponseEntity<Map> purchaseResp =
        rest.exchange(
            "/api/v1/purchasing/raw-material-purchases",
            HttpMethod.POST,
            new HttpEntity<>(purchaseReq, headers),
            Map.class);
    assertThat(purchaseResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    Map<String, Object> purchaseData = (Map<String, Object>) purchaseResp.getBody().get("data");
    Long purchaseId = ((Number) purchaseData.get("id")).longValue();

    ResponseEntity<Map> goodsReceiptDetailResp =
        rest.exchange(
            "/api/v1/purchasing/goods-receipts/" + workflow.goodsReceiptId(),
            HttpMethod.GET,
            new HttpEntity<>(headers),
            Map.class);
    assertThat(goodsReceiptDetailResp.getStatusCode()).isEqualTo(HttpStatus.OK);
    Map<String, Object> receiptData =
        (Map<String, Object>) goodsReceiptDetailResp.getBody().get("data");

    assertThat(((Number) receiptData.get("supplierId")).longValue()).isEqualTo(supplierId);
    assertThat(((Number) receiptData.get("purchaseOrderId")).longValue())
        .isEqualTo(workflow.purchaseOrderId());
    assertThat((List<Map<String, Object>>) receiptData.get("lines"))
        .isNotEmpty()
        .anySatisfy(
            line ->
                assertThat(((Number) line.get("rawMaterialId")).longValue())
                    .isEqualTo(rawMaterialId));

    Map<String, Object> lifecycle = (Map<String, Object>) receiptData.get("lifecycle");
    assertThat(lifecycle.get("workflowStatus")).isEqualTo("INVOICED");
    assertThat(lifecycle.get("accountingStatus")).isEqualTo("POSTED");

    List<Map<String, Object>> linkedReferences =
        (List<Map<String, Object>>) receiptData.get("linkedReferences");
    assertThat(linkedReferences)
        .extracting(reference -> reference.get("relationType"))
        .contains("PURCHASE_ORDER", "PURCHASE_INVOICE", "ACCOUNTING_ENTRY", "SELF");
    assertThat(linkedReferences)
        .filteredOn(reference -> "PURCHASE_INVOICE".equals(reference.get("relationType")))
        .singleElement()
        .satisfies(
            reference ->
                assertThat(((Number) reference.get("documentId")).longValue())
                    .isEqualTo(purchaseId));
  }

  @Test
  @DisplayName(
      "GST-bearing purchase invoice succeeds when fresh tenant and supplier state metadata are"
          + " missing")
  void purchaseInvoiceSucceedsWhenStateMetadataMissing() {
    company.setStateCode(null);
    companyRepository.saveAndFlush(company);

    LocalDate entryDate = TestDateUtils.safeDate(company);
    Long supplierId =
        createSupplierWithoutStateCode(
            "P2P GST Fallback Supplier", "P2P-GST-FALLBACK-" + shortSuffix(), "27ABCDE1234F1Z5");
    Long rawMaterialId =
        createRawMaterial(
            "P2P GST Fallback Material", "RM-GST-FALLBACK-" + shortSuffix(), inventory.getId());

    PurchaseWorkflowIds workflow =
        createPurchaseOrderAndReceipt(
            supplierId, rawMaterialId, new BigDecimal("5"), new BigDecimal("10.00"), entryDate);

    Map<String, Object> line = new HashMap<>();
    line.put("rawMaterialId", rawMaterialId);
    line.put("quantity", new BigDecimal("5"));
    line.put("costPerUnit", new BigDecimal("10.00"));

    Map<String, Object> purchaseReq = new HashMap<>();
    purchaseReq.put("supplierId", supplierId);
    purchaseReq.put("invoiceNumber", "INV-GST-FALLBACK-" + shortSuffix());
    purchaseReq.put("invoiceDate", entryDate);
    purchaseReq.put("purchaseOrderId", workflow.purchaseOrderId());
    purchaseReq.put("goodsReceiptId", workflow.goodsReceiptId());
    purchaseReq.put("lines", List.of(line));

    ResponseEntity<Map> purchaseResp =
        rest.exchange(
            "/api/v1/purchasing/raw-material-purchases",
            HttpMethod.POST,
            new HttpEntity<>(purchaseReq, headers),
            Map.class);

    assertThat(purchaseResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    Map<String, Object> purchaseData = (Map<String, Object>) purchaseResp.getBody().get("data");
    Long purchaseId = ((Number) purchaseData.get("id")).longValue();
    RawMaterialPurchase persistedPurchase = purchaseRepository.findById(purchaseId).orElseThrow();
    assertThat(persistedPurchase.getJournalEntry()).isNotNull();

    ResponseEntity<Map> supplierDetailResp =
        rest.exchange(
            "/api/v1/suppliers/" + supplierId,
            HttpMethod.GET,
            new HttpEntity<>(headers),
            Map.class);
    assertThat(supplierDetailResp.getStatusCode()).isEqualTo(HttpStatus.OK);
    Map<String, Object> supplierData =
        (Map<String, Object>) supplierDetailResp.getBody().get("data");
    assertThat(String.valueOf(supplierData.get("stateCode"))).isEqualTo("27");

    Company refreshed = companyRepository.findByCodeIgnoreCase(COMPANY_CODE).orElseThrow();
    assertThat(refreshed.getStateCode()).isNull();
  }

  @Test
  @DisplayName("Purchase tax computed balances inventory + tax to payable")
  void purchaseComputedTax_BalancesJournalTotals() {
    LocalDate entryDate = TestDateUtils.safeDate(company);
    Long supplierId = createSupplier("P2P Tax Computed Supplier", "P2P-TAX-C-" + shortSuffix());
    Long rawMaterialId =
        createRawMaterial("P2P Tax Material", "RM-TAX-C-" + shortSuffix(), inventory.getId());

    BigDecimal quantity = new BigDecimal("5");
    BigDecimal costPerUnit = new BigDecimal("100.00");
    BigDecimal taxRate = new BigDecimal("18.00");
    BigDecimal expectedInventory = costPerUnit.multiply(quantity);
    BigDecimal expectedTax =
        expectedInventory.multiply(taxRate).divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
    BigDecimal expectedTotal = expectedInventory.add(expectedTax);

    PurchaseWorkflowIds workflow =
        createPurchaseOrderAndReceipt(supplierId, rawMaterialId, quantity, costPerUnit, entryDate);

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

    ResponseEntity<Map> purchaseResp =
        rest.exchange(
            "/api/v1/purchasing/raw-material-purchases",
            HttpMethod.POST,
            new HttpEntity<>(purchaseReq, headers),
            Map.class);
    assertThat(purchaseResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    Map<String, Object> purchaseData = (Map<String, Object>) purchaseResp.getBody().get("data");
    Long purchaseId = ((Number) purchaseData.get("id")).longValue();

    RawMaterialPurchase purchase = purchaseRepository.findById(purchaseId).orElseThrow();
    assertThat(purchase.getTaxAmount()).isEqualByComparingTo(expectedTax);
    assertThat(purchase.getTotalAmount()).isEqualByComparingTo(expectedTotal);
    assertPurchaseJournalTotals(
        purchase.getJournalEntry().getId(), expectedInventory, expectedTax, expectedTotal);
  }

  @Test
  @DisplayName("Purchase tax provided balances inventory + tax to payable")
  void purchaseProvidedTax_BalancesJournalTotals() {
    LocalDate entryDate = TestDateUtils.safeDate(company);
    Long supplierId = createSupplier("P2P Tax Provided Supplier", "P2P-TAX-P-" + shortSuffix());
    Long rawMaterialId =
        createRawMaterial(
            "P2P Tax Provided Material", "RM-TAX-P-" + shortSuffix(), inventory.getId());

    BigDecimal quantity = new BigDecimal("4");
    BigDecimal costPerUnit = new BigDecimal("125.00");
    BigDecimal expectedInventory = costPerUnit.multiply(quantity);
    BigDecimal providedTax = new BigDecimal("91.11");
    BigDecimal expectedTotal = expectedInventory.add(providedTax);

    PurchaseWorkflowIds workflow =
        createPurchaseOrderAndReceipt(supplierId, rawMaterialId, quantity, costPerUnit, entryDate);

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

    ResponseEntity<Map> purchaseResp =
        rest.exchange(
            "/api/v1/purchasing/raw-material-purchases",
            HttpMethod.POST,
            new HttpEntity<>(purchaseReq, headers),
            Map.class);
    assertThat(purchaseResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    Map<String, Object> purchaseData = (Map<String, Object>) purchaseResp.getBody().get("data");
    Long purchaseId = ((Number) purchaseData.get("id")).longValue();

    RawMaterialPurchase purchase = purchaseRepository.findById(purchaseId).orElseThrow();
    assertThat(purchase.getTaxAmount()).isEqualByComparingTo(providedTax);
    assertThat(purchase.getTotalAmount()).isEqualByComparingTo(expectedTotal);
    assertPurchaseJournalTotals(
        purchase.getJournalEntry().getId(), expectedInventory, providedTax, expectedTotal);
  }

  @Test
  @DisplayName("Goods receipt quantity cannot exceed purchase order quantity")
  void goodsReceiptCannotExceedPurchaseOrder() {
    LocalDate entryDate = TestDateUtils.safeDate(company);
    Long supplierId = createSupplier("P2P Receipt Supplier", "P2P-GRN-" + shortSuffix());
    Long rawMaterialId =
        createRawMaterial("P2P Receipt Material", "RM-GRN-" + shortSuffix(), inventory.getId());

    Long purchaseOrderId =
        createPurchaseOrder(
            supplierId, rawMaterialId, new BigDecimal("5"), new BigDecimal("10.00"), entryDate);

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

    ResponseEntity<Map> grResp =
        rest.exchange(
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
    Long rawMaterialId =
        createRawMaterial("P2P Invoice Material", "RM-INV-" + shortSuffix(), inventory.getId());

    PurchaseWorkflowIds workflow =
        createPurchaseOrderAndReceipt(
            supplierId, rawMaterialId, new BigDecimal("5"), new BigDecimal("10.00"), entryDate);

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

    ResponseEntity<Map> purchaseResp =
        rest.exchange(
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
    Long rawMaterialId =
        createRawMaterial("Intake Raw Material", "RM-INTAKE-" + shortSuffix(), inventory.getId());

    Map<String, Object> intakeReq = new HashMap<>();
    intakeReq.put("rawMaterialId", rawMaterialId);
    intakeReq.put("batchCode", "INTAKE-" + shortSuffix());
    intakeReq.put("quantity", new BigDecimal("5"));
    intakeReq.put("unit", "KG");
    intakeReq.put("costPerUnit", new BigDecimal("10.00"));
    intakeReq.put("supplierId", supplierId);
    intakeReq.put("notes", "Adjustment-only intake");

    ResponseEntity<Map> resp =
        rest.exchange(
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
    Long rawMaterialId =
        createRawMaterial("Payment Material", "RM-PAY-" + shortSuffix(), inventory.getId());

    BigDecimal quantity = new BigDecimal("5");
    BigDecimal costPerUnit = new BigDecimal("10.00");
    BigDecimal totalAmount = quantity.multiply(costPerUnit);
    PurchaseWorkflowIds workflow =
        createPurchaseOrderAndReceipt(supplierId, rawMaterialId, quantity, costPerUnit, entryDate);

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

    ResponseEntity<Map> purchaseResp =
        rest.exchange(
            "/api/v1/purchasing/raw-material-purchases",
            HttpMethod.POST,
            new HttpEntity<>(purchaseReq, headers),
            Map.class);
    assertThat(purchaseResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    Map<String, Object> purchaseData = (Map<String, Object>) purchaseResp.getBody().get("data");
    Long purchaseId = ((Number) purchaseData.get("id")).longValue();

    Map<String, Object> allocation =
        Map.of(
            "purchaseId", purchaseId,
            "appliedAmount", totalAmount);
    Map<String, Object> paymentReq = new HashMap<>();
    paymentReq.put("partnerType", "SUPPLIER");
    paymentReq.put("partnerId", supplierId);
    paymentReq.put("cashAccountId", cash.getId());
    paymentReq.put("amount", totalAmount);
    paymentReq.put("settlementDate", entryDate);
    String paymentRef = "PAY-" + shortSuffix();
    paymentReq.put("referenceNumber", paymentRef);
    paymentReq.put("memo", "Supplier payment allocation");
    paymentReq.put("allocations", List.of(allocation));
    HttpHeaders paymentHeaders = headersWithIdempotencyKey(paymentRef);

    ResponseEntity<Map> paymentResp =
        rest.exchange(
            "/api/v1/accounting/settlements/suppliers",
            HttpMethod.POST,
            new HttpEntity<>(paymentReq, paymentHeaders),
            Map.class);
    assertThat(paymentResp.getStatusCode()).isEqualTo(HttpStatus.OK);
    JournalEntry paymentJournal =
        journalEntryRepository.findByCompanyAndReferenceNumber(company, paymentRef).orElseThrow();
    PartnerPaymentEvent paymentEvent =
        partnerPaymentEventRepository
            .findByCompanyAndJournalEntry(company, paymentJournal)
            .orElseThrow();
    assertThat(paymentEvent.getPartnerType()).isEqualTo(PartnerType.SUPPLIER);
    assertThat(paymentEvent.getPaymentFlow()).isEqualTo(PartnerPaymentFlow.SUPPLIER_SETTLEMENT);
    assertThat(paymentEvent.getSourceRoute()).isEqualTo("/api/v1/accounting/settlements/suppliers");
    assertThat(paymentEvent.getAmount()).isEqualByComparingTo(totalAmount);
    assertThat(
            settlementAllocationRepository
                .findByCompanyAndIdempotencyKeyIgnoreCaseOrderByCreatedAtAscIdAsc(
                    company, paymentRef))
        .allSatisfy(
            settlementRow -> {
              assertThat(settlementRow.getPaymentEvent()).isNotNull();
              assertThat(settlementRow.getPaymentEvent().getId()).isEqualTo(paymentEvent.getId());
            });

    RawMaterialPurchase purchase = purchaseRepository.findById(purchaseId).orElseThrow();
    assertThat(purchase.getOutstandingAmount()).isEqualByComparingTo(BigDecimal.ZERO);
    assertThat(purchase.getStatus()).isEqualTo("PAID");
  }

  @Test
  @DisplayName(
      "Supplier auto-settle posts SUPPLIER_PAYMENT via payment-event and oldest-open order")
  void supplierAutoSettleUsesCanonicalPaymentEventFlow() {
    LocalDate entryDate = TestDateUtils.safeDate(company);
    Long supplierId = createSupplier("P2P Auto Supplier", "AUTO-" + shortSuffix());
    Long rawMaterialId =
        createRawMaterial(
            "Auto Settlement Material", "RM-AUTO-" + shortSuffix(), inventory.getId());

    BigDecimal quantity = new BigDecimal("5");
    BigDecimal costPerUnit = new BigDecimal("10.00");
    BigDecimal invoiceTotal = quantity.multiply(costPerUnit);
    PurchaseWorkflowIds firstWorkflow =
        createPurchaseOrderAndReceipt(supplierId, rawMaterialId, quantity, costPerUnit, entryDate);
    PurchaseWorkflowIds secondWorkflow =
        createPurchaseOrderAndReceipt(supplierId, rawMaterialId, quantity, costPerUnit, entryDate);

    Long firstPurchaseId =
        createPurchaseInvoiceForWorkflow(
            supplierId,
            rawMaterialId,
            quantity,
            costPerUnit,
            entryDate,
            firstWorkflow,
            "INV-AUTO-1-" + shortSuffix());
    Long secondPurchaseId =
        createPurchaseInvoiceForWorkflow(
            supplierId,
            rawMaterialId,
            quantity,
            costPerUnit,
            entryDate,
            secondWorkflow,
            "INV-AUTO-2-" + shortSuffix());
    assertThat(firstPurchaseId).isLessThan(secondPurchaseId);

    String autoRef = "SUP-AUTO-" + shortSuffix();
    BigDecimal autoAmount = new BigDecimal("75.00");
    Map<String, Object> autoReq = new HashMap<>();
    autoReq.put("cashAccountId", cash.getId());
    autoReq.put("amount", autoAmount);
    autoReq.put("referenceNumber", autoRef);
    autoReq.put("idempotencyKey", autoRef);
    autoReq.put("memo", "Supplier auto settle");
    HttpHeaders autoHeaders = headersWithIdempotencyKey(autoRef);

    ResponseEntity<Map> autoResp =
        rest.exchange(
            "/api/v1/accounting/suppliers/" + supplierId + "/auto-settle",
            HttpMethod.POST,
            new HttpEntity<>(autoReq, autoHeaders),
            Map.class);
    assertThat(autoResp.getStatusCode()).isEqualTo(HttpStatus.OK);
    ResponseEntity<Map> replayResp =
        rest.exchange(
            "/api/v1/accounting/suppliers/" + supplierId + "/auto-settle",
            HttpMethod.POST,
            new HttpEntity<>(autoReq, autoHeaders),
            Map.class);
    assertThat(replayResp.getStatusCode()).isEqualTo(HttpStatus.OK);

    JournalEntry autoJournal =
        journalEntryRepository.findByCompanyAndReferenceNumber(company, autoRef).orElseThrow();
    assertThat(autoJournal.getSourceModule()).isEqualTo("SUPPLIER_PAYMENT");
    PartnerPaymentEvent autoEvent =
        partnerPaymentEventRepository
            .findByCompanyAndJournalEntry(company, autoJournal)
            .orElseThrow();
    assertThat(autoEvent.getPartnerType()).isEqualTo(PartnerType.SUPPLIER);
    assertThat(autoEvent.getSupplier()).isNotNull();
    assertThat(autoEvent.getSupplier().getId()).isEqualTo(supplierId);
    assertThat(autoEvent.getPaymentFlow()).isEqualTo(PartnerPaymentFlow.SUPPLIER_SETTLEMENT);
    assertThat(autoEvent.getSourceRoute())
        .isEqualTo("/api/v1/accounting/suppliers/{supplierId}/auto-settle");
    assertThat(autoEvent.getAmount()).isEqualByComparingTo(autoAmount);
    assertThat(autoEvent.getReferenceNumber()).isEqualTo(autoRef);
    assertThat(autoEvent.getIdempotencyKey()).isEqualTo(autoRef);

    List<PartnerSettlementAllocation> autoAllocations =
        settlementAllocationRepository
            .findByCompanyAndIdempotencyKeyIgnoreCaseOrderByCreatedAtAscIdAsc(company, autoRef);
    assertThat(autoAllocations).hasSize(2);
    assertThat(autoAllocations.get(0).getPurchase()).isNotNull();
    assertThat(autoAllocations.get(0).getPurchase().getId()).isEqualTo(firstPurchaseId);
    assertThat(autoAllocations.get(0).getAllocationAmount()).isEqualByComparingTo(invoiceTotal);
    assertThat(autoAllocations.get(1).getPurchase()).isNotNull();
    assertThat(autoAllocations.get(1).getPurchase().getId()).isEqualTo(secondPurchaseId);
    assertThat(autoAllocations.get(1).getAllocationAmount())
        .isEqualByComparingTo(autoAmount.subtract(invoiceTotal));
    assertThat(autoAllocations)
        .allSatisfy(
            allocation -> {
              assertThat(allocation.getPaymentEvent()).isNotNull();
              assertThat(allocation.getPaymentEvent().getId()).isEqualTo(autoEvent.getId());
            });

    RawMaterialPurchase firstPurchase = purchaseRepository.findById(firstPurchaseId).orElseThrow();
    RawMaterialPurchase secondPurchase =
        purchaseRepository.findById(secondPurchaseId).orElseThrow();
    assertThat(firstPurchase.getOutstandingAmount()).isEqualByComparingTo(BigDecimal.ZERO);
    assertThat(firstPurchase.getStatus()).isEqualTo("PAID");
    assertThat(secondPurchase.getOutstandingAmount())
        .isEqualByComparingTo(invoiceTotal.subtract(autoAmount.subtract(invoiceTotal)));
    assertThat(secondPurchase.getStatus()).isEqualTo("PARTIAL");
  }

  @Test
  @DisplayName(
      "Supplier auto-settle keyless same-amount calls derive identity from resolved due-date"
          + " ordered allocations")
  void supplierAutoSettleKeylessSuccessiveCallsUseDueDateOrderingAndDistinctIdentity() {
    LocalDate baseDate = TestDateUtils.safeDate(company);
    Long supplierId = createSupplier("P2P Keyless Supplier", "AUTO-KEYLESS-" + shortSuffix());
    Long rawMaterialId =
        createRawMaterial(
            "Auto Keyless Material", "RM-AUTO-KEYLESS-" + shortSuffix(), inventory.getId());

    BigDecimal quantity = new BigDecimal("5");
    BigDecimal costPerUnit = new BigDecimal("10.00");
    BigDecimal invoiceTotal = quantity.multiply(costPerUnit);

    PurchaseWorkflowIds firstWorkflow =
        createPurchaseOrderAndReceipt(supplierId, rawMaterialId, quantity, costPerUnit, baseDate);
    PurchaseWorkflowIds secondWorkflow =
        createPurchaseOrderAndReceipt(supplierId, rawMaterialId, quantity, costPerUnit, baseDate);

    Long firstPurchaseId =
        createPurchaseInvoiceForWorkflow(
            supplierId,
            rawMaterialId,
            quantity,
            costPerUnit,
            baseDate.minusDays(10),
            firstWorkflow,
            "INV-AUTO-KEYLESS-1-" + shortSuffix());
    Long secondPurchaseId =
        createPurchaseInvoiceForWorkflow(
            supplierId,
            rawMaterialId,
            quantity,
            costPerUnit,
            baseDate.minusDays(1),
            secondWorkflow,
            "INV-AUTO-KEYLESS-2-" + shortSuffix());

    RawMaterialPurchase firstPurchase = purchaseRepository.findById(firstPurchaseId).orElseThrow();
    RawMaterialPurchase secondPurchase =
        purchaseRepository.findById(secondPurchaseId).orElseThrow();
    assertThat(firstPurchase.getDueDate()).isEqualTo(baseDate.minusDays(10).plusDays(30));
    assertThat(secondPurchase.getDueDate()).isEqualTo(baseDate.minusDays(1).plusDays(30));
    firstPurchase.setDueDate(baseDate.plusDays(30));
    secondPurchase.setDueDate(baseDate.plusDays(2));
    purchaseRepository.saveAll(List.of(firstPurchase, secondPurchase));

    Map<String, Object> autoReq = new HashMap<>();
    autoReq.put("cashAccountId", cash.getId());
    autoReq.put("amount", invoiceTotal);
    autoReq.put("memo", "Supplier auto settle keyless");

    ResponseEntity<Map> firstResp =
        rest.exchange(
            "/api/v1/accounting/suppliers/" + supplierId + "/auto-settle",
            HttpMethod.POST,
            new HttpEntity<>(autoReq, headers),
            Map.class);
    assertThat(firstResp.getStatusCode()).isEqualTo(HttpStatus.OK);
    Map<String, Object> firstData = (Map<String, Object>) firstResp.getBody().get("data");
    Map<String, Object> firstJournal = (Map<String, Object>) firstData.get("journalEntry");
    String firstReference = (String) firstJournal.get("referenceNumber");
    List<Map<String, Object>> firstAllocations =
        (List<Map<String, Object>>) firstData.get("allocations");
    assertThat(firstAllocations).hasSize(1);
    assertThat(((Number) firstAllocations.get(0).get("purchaseId")).longValue())
        .isEqualTo(secondPurchaseId);

    ResponseEntity<Map> secondResp =
        rest.exchange(
            "/api/v1/accounting/suppliers/" + supplierId + "/auto-settle",
            HttpMethod.POST,
            new HttpEntity<>(autoReq, headers),
            Map.class);
    assertThat(secondResp.getStatusCode()).isEqualTo(HttpStatus.OK);
    Map<String, Object> secondData = (Map<String, Object>) secondResp.getBody().get("data");
    Map<String, Object> secondJournal = (Map<String, Object>) secondData.get("journalEntry");
    String secondReference = (String) secondJournal.get("referenceNumber");
    List<Map<String, Object>> secondAllocations =
        (List<Map<String, Object>>) secondData.get("allocations");
    assertThat(secondAllocations).hasSize(1);
    assertThat(((Number) secondAllocations.get(0).get("purchaseId")).longValue())
        .isEqualTo(firstPurchaseId);

    assertThat(secondReference).isNotEqualTo(firstReference);

    JournalEntry firstEntry =
        journalEntryRepository
            .findByCompanyAndReferenceNumber(company, firstReference)
            .orElseThrow();
    JournalEntry secondEntry =
        journalEntryRepository
            .findByCompanyAndReferenceNumber(company, secondReference)
            .orElseThrow();
    PartnerPaymentEvent firstEvent =
        partnerPaymentEventRepository
            .findByCompanyAndJournalEntry(company, firstEntry)
            .orElseThrow();
    PartnerPaymentEvent secondEvent =
        partnerPaymentEventRepository
            .findByCompanyAndJournalEntry(company, secondEntry)
            .orElseThrow();
    assertThat(firstEvent.getIdempotencyKey()).isNotEqualTo(secondEvent.getIdempotencyKey());

    RawMaterialPurchase firstSettled = purchaseRepository.findById(firstPurchaseId).orElseThrow();
    RawMaterialPurchase secondSettled = purchaseRepository.findById(secondPurchaseId).orElseThrow();
    assertThat(firstSettled.getOutstandingAmount()).isEqualByComparingTo(BigDecimal.ZERO);
    assertThat(firstSettled.getStatus()).isEqualTo("PAID");
    assertThat(secondSettled.getOutstandingAmount()).isEqualByComparingTo(BigDecimal.ZERO);
    assertThat(secondSettled.getStatus()).isEqualTo("PAID");
  }

  @Test
  @DisplayName("Debit note clears purchase outstanding and sets status to VOID")
  void purchaseDebitNoteClearsOutstanding() {
    LocalDate entryDate = TestDateUtils.safeDate(company);
    Long supplierId = createSupplier("P2P Debit Supplier", "DN-" + shortSuffix());
    Long rawMaterialId =
        createRawMaterial("Debit Note Material", "RM-DN-" + shortSuffix(), inventory.getId());

    BigDecimal quantity = new BigDecimal("5");
    BigDecimal costPerUnit = new BigDecimal("20.00");
    PurchaseWorkflowIds workflow =
        createPurchaseOrderAndReceipt(supplierId, rawMaterialId, quantity, costPerUnit, entryDate);

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

    ResponseEntity<Map> purchaseResp =
        rest.exchange(
            "/api/v1/purchasing/raw-material-purchases",
            HttpMethod.POST,
            new HttpEntity<>(purchaseReq, headers),
            Map.class);
    assertThat(purchaseResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    Map<String, Object> purchaseData = (Map<String, Object>) purchaseResp.getBody().get("data");
    Long purchaseId = ((Number) purchaseData.get("id")).longValue();

    Map<String, Object> debitNoteReq = new HashMap<>();
    debitNoteReq.put("purchaseId", purchaseId);
    debitNoteReq.put("referenceNumber", "DN-" + shortSuffix());
    debitNoteReq.put("memo", "Debit note test");

    ResponseEntity<Map> debitResp =
        rest.exchange(
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

    ResponseEntity<Map> duplicateResp =
        rest.exchange(
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
    Long rawMaterialId =
        createRawMaterial(
            "Partial Debit Material", "RM-DN-PART-" + shortSuffix(), inventory.getId());

    BigDecimal quantity = new BigDecimal("5");
    BigDecimal costPerUnit = new BigDecimal("20.00");
    BigDecimal totalAmount = quantity.multiply(costPerUnit);
    PurchaseWorkflowIds workflow =
        createPurchaseOrderAndReceipt(supplierId, rawMaterialId, quantity, costPerUnit, entryDate);

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

    ResponseEntity<Map> purchaseResp =
        rest.exchange(
            "/api/v1/purchasing/raw-material-purchases",
            HttpMethod.POST,
            new HttpEntity<>(purchaseReq, headers),
            Map.class);
    assertThat(purchaseResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    Map<String, Object> purchaseData = (Map<String, Object>) purchaseResp.getBody().get("data");
    Long purchaseId = ((Number) purchaseData.get("id")).longValue();

    BigDecimal debitAmount = new BigDecimal("40.00");
    Map<String, Object> debitNoteReq = new HashMap<>();
    debitNoteReq.put("purchaseId", purchaseId);
    debitNoteReq.put("amount", debitAmount);
    debitNoteReq.put("referenceNumber", "DN-PART-" + shortSuffix());
    debitNoteReq.put("memo", "Partial debit note");

    ResponseEntity<Map> debitResp =
        rest.exchange(
            "/api/v1/accounting/debit-notes",
            HttpMethod.POST,
            new HttpEntity<>(debitNoteReq, headers),
            Map.class);
    assertThat(debitResp.getStatusCode()).isEqualTo(HttpStatus.OK);

    RawMaterialPurchase purchase = purchaseRepository.findById(purchaseId).orElseThrow();
    assertThat(purchase.getOutstandingAmount())
        .isEqualByComparingTo(totalAmount.subtract(debitAmount));
    assertThat(purchase.getStatus()).isEqualTo("PARTIAL");

    ResponseEntity<Map> purchaseDetailResp =
        rest.exchange(
            "/api/v1/purchasing/raw-material-purchases/" + purchaseId,
            HttpMethod.GET,
            new HttpEntity<>(headers),
            Map.class);
    assertThat(purchaseDetailResp.getStatusCode()).isEqualTo(HttpStatus.OK);
    Map<String, Object> purchaseDetailData =
        (Map<String, Object>) purchaseDetailResp.getBody().get("data");
    assertThat(new BigDecimal(purchaseDetailData.get("outstandingAmount").toString()))
        .isEqualByComparingTo(totalAmount.subtract(debitAmount));
    assertThat(((Map<String, Object>) purchaseDetailData.get("lifecycle")).get("workflowStatus"))
        .isEqualTo("PARTIAL");
    assertThat((List<Map<String, Object>>) purchaseDetailData.get("linkedReferences"))
        .isNotEmpty()
        .anySatisfy(reference -> assertThat(reference.get("relationType")).isEqualTo("SELF"));

    ResponseEntity<Map> purchaseListResp =
        rest.exchange(
            "/api/v1/purchasing/raw-material-purchases?supplierId=" + supplierId,
            HttpMethod.GET,
            new HttpEntity<>(headers),
            Map.class);
    assertThat(purchaseListResp.getStatusCode()).isEqualTo(HttpStatus.OK);
    List<Map<String, Object>> listedPurchases =
        (List<Map<String, Object>>) purchaseListResp.getBody().get("data");
    assertThat(listedPurchases)
        .isNotEmpty()
        .anySatisfy(
            listed -> assertThat(((Number) listed.get("id")).longValue()).isEqualTo(purchaseId));
  }

  @Test
  @DisplayName("Partial debit note rebalances rounded multi-line reversal journals")
  void purchasePartialDebitNoteRebalancesRoundedMultiLineJournals() {
    LocalDate entryDate = TestDateUtils.safeDate(company);
    Long supplierId = createSupplier("P2P Rebalance Supplier", "DN-RB-" + shortSuffix());
    Long rawMaterialId =
        createRawMaterial("Rebalance Material", "RM-DN-RB-" + shortSuffix(), inventory.getId());

    BigDecimal quantity = new BigDecimal("5");
    BigDecimal costPerUnit = new BigDecimal("99.99");
    BigDecimal taxRate = new BigDecimal("18.00");
    PurchaseWorkflowIds workflow =
        createPurchaseOrderAndReceipt(supplierId, rawMaterialId, quantity, costPerUnit, entryDate);

    Map<String, Object> line = new HashMap<>();
    line.put("rawMaterialId", rawMaterialId);
    line.put("quantity", quantity);
    line.put("costPerUnit", costPerUnit);
    line.put("taxRate", taxRate);
    line.put("taxInclusive", false);

    String invoiceNumber = "INV-DN-RB-" + shortSuffix();
    Map<String, Object> purchaseReq = new HashMap<>();
    purchaseReq.put("supplierId", supplierId);
    purchaseReq.put("invoiceNumber", invoiceNumber);
    purchaseReq.put("invoiceDate", entryDate);
    purchaseReq.put("purchaseOrderId", workflow.purchaseOrderId());
    purchaseReq.put("goodsReceiptId", workflow.goodsReceiptId());
    purchaseReq.put("lines", List.of(line));

    ResponseEntity<Map> purchaseResp =
        rest.exchange(
            "/api/v1/purchasing/raw-material-purchases",
            HttpMethod.POST,
            new HttpEntity<>(purchaseReq, headers),
            Map.class);
    assertThat(purchaseResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    Map<String, Object> purchaseData = (Map<String, Object>) purchaseResp.getBody().get("data");
    Long purchaseId = ((Number) purchaseData.get("id")).longValue();

    RawMaterialPurchase purchase = purchaseRepository.findById(purchaseId).orElseThrow();
    BigDecimal sourceAmount = purchase.getOutstandingAmount();
    BigDecimal taxComponent =
        purchase.getTaxAmount() != null ? purchase.getTaxAmount() : BigDecimal.ZERO;
    BigDecimal inventoryComponent = sourceAmount.subtract(taxComponent);
    BigDecimal debitAmount =
        findRoundingImbalanceAmount(List.of(inventoryComponent, taxComponent), sourceAmount);
    assertThat(debitAmount)
        .as("fixture must include an amount that imbalances naive line rounding")
        .isNotNull();

    String reference = "DN-RB-" + shortSuffix();
    Map<String, Object> debitNoteReq = new HashMap<>();
    debitNoteReq.put("purchaseId", purchaseId);
    debitNoteReq.put("amount", debitAmount);
    debitNoteReq.put("referenceNumber", reference);
    debitNoteReq.put("memo", "Partial debit note rounding rebalance");

    ResponseEntity<Map> debitResp =
        rest.exchange(
            "/api/v1/accounting/debit-notes",
            HttpMethod.POST,
            new HttpEntity<>(debitNoteReq, headers),
            Map.class);
    assertThat(debitResp.getStatusCode()).isEqualTo(HttpStatus.OK);

    JournalEntry note =
        journalEntryRepository.findByCompanyAndReferenceNumber(company, reference).orElseThrow();
    assertThat(sumDebits(note)).isEqualByComparingTo(sumCredits(note));

    RawMaterialPurchase refreshed = purchaseRepository.findById(purchaseId).orElseThrow();
    assertThat(refreshed.getOutstandingAmount()).isLessThan(sourceAmount);
  }

  @Test
  @DisplayName("Debit note is idempotent by reference for the same purchase")
  void purchaseDebitNoteIdempotentByReference() {
    LocalDate entryDate = TestDateUtils.safeDate(company);
    Long supplierId = createSupplier("P2P Idempotent Supplier", "DN-IDEMP-" + shortSuffix());
    Long rawMaterialId =
        createRawMaterial(
            "Idempotent Debit Material", "RM-DN-IDEMP-" + shortSuffix(), inventory.getId());

    BigDecimal quantity = new BigDecimal("3");
    BigDecimal costPerUnit = new BigDecimal("30.00");
    BigDecimal totalAmount = quantity.multiply(costPerUnit);
    PurchaseWorkflowIds workflow =
        createPurchaseOrderAndReceipt(supplierId, rawMaterialId, quantity, costPerUnit, entryDate);

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

    ResponseEntity<Map> purchaseResp =
        rest.exchange(
            "/api/v1/purchasing/raw-material-purchases",
            HttpMethod.POST,
            new HttpEntity<>(purchaseReq, headers),
            Map.class);
    assertThat(purchaseResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    Map<String, Object> purchaseData = (Map<String, Object>) purchaseResp.getBody().get("data");
    Long purchaseId = ((Number) purchaseData.get("id")).longValue();

    String reference = "DN-" + shortSuffix();
    Map<String, Object> debitNoteReq = new HashMap<>();
    debitNoteReq.put("purchaseId", purchaseId);
    debitNoteReq.put("referenceNumber", reference);
    debitNoteReq.put("memo", "Debit note idempotency test");

    ResponseEntity<Map> first =
        rest.exchange(
            "/api/v1/accounting/debit-notes",
            HttpMethod.POST,
            new HttpEntity<>(debitNoteReq, headers),
            Map.class);
    assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
    Map<String, Object> firstDebitNote = (Map<String, Object>) first.getBody().get("data");
    Long firstDebitNoteId = ((Number) firstDebitNote.get("id")).longValue();

    ResponseEntity<Map> second =
        rest.exchange(
            "/api/v1/accounting/debit-notes",
            HttpMethod.POST,
            new HttpEntity<>(debitNoteReq, headers),
            Map.class);
    assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
    Map<String, Object> secondDebitNote = (Map<String, Object>) second.getBody().get("data");
    assertThat(((Number) secondDebitNote.get("id")).longValue()).isEqualTo(firstDebitNoteId);
    assertThat(secondDebitNote.get("referenceNumber")).isEqualTo(reference);

    assertThat(journalEntryRepository.findByCompanyAndReferenceNumber(company, reference))
        .isPresent();

    RawMaterialPurchase purchase = purchaseRepository.findById(purchaseId).orElseThrow();
    assertThat(purchase.getOutstandingAmount()).isEqualByComparingTo(BigDecimal.ZERO);
    assertThat(purchase.getStatus()).isEqualTo("VOID");
  }

  @Test
  @DisplayName("Debit note is rejected after purchase is fully settled")
  void purchaseDebitNoteRejectedAfterSettlement() {
    LocalDate entryDate = TestDateUtils.safeDate(company);
    Long supplierId = createSupplier("P2P Paid Supplier", "DN-PAID-" + shortSuffix());
    Long rawMaterialId =
        createRawMaterial("Paid Debit Material", "RM-DN-PAID-" + shortSuffix(), inventory.getId());

    BigDecimal quantity = new BigDecimal("4");
    BigDecimal costPerUnit = new BigDecimal("25.00");
    BigDecimal totalAmount = quantity.multiply(costPerUnit);
    PurchaseWorkflowIds workflow =
        createPurchaseOrderAndReceipt(supplierId, rawMaterialId, quantity, costPerUnit, entryDate);

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

    ResponseEntity<Map> purchaseResp =
        rest.exchange(
            "/api/v1/purchasing/raw-material-purchases",
            HttpMethod.POST,
            new HttpEntity<>(purchaseReq, headers),
            Map.class);
    assertThat(purchaseResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    Map<String, Object> purchaseData = (Map<String, Object>) purchaseResp.getBody().get("data");
    Long purchaseId = ((Number) purchaseData.get("id")).longValue();

    Map<String, Object> allocation =
        Map.of(
            "purchaseId", purchaseId,
            "appliedAmount", totalAmount);
    String settlementRef = "SET-PAID-" + shortSuffix();
    Map<String, Object> settlementReq = new HashMap<>();
    settlementReq.put("partnerType", "SUPPLIER");
    settlementReq.put("partnerId", supplierId);
    settlementReq.put("cashAccountId", cash.getId());
    settlementReq.put("settlementDate", entryDate);
    settlementReq.put("referenceNumber", settlementRef);
    settlementReq.put("allocations", List.of(allocation));
    HttpHeaders settlementHeaders = headersWithIdempotencyKey(settlementRef);

    ResponseEntity<Map> settleResp =
        rest.exchange(
            "/api/v1/accounting/settlements/suppliers",
            HttpMethod.POST,
            new HttpEntity<>(settlementReq, settlementHeaders),
            Map.class);
    assertThat(settleResp.getStatusCode()).isEqualTo(HttpStatus.OK);

    RawMaterialPurchase paidPurchase = purchaseRepository.findById(purchaseId).orElseThrow();
    assertThat(paidPurchase.getOutstandingAmount()).isEqualByComparingTo(BigDecimal.ZERO);
    assertThat(paidPurchase.getStatus()).isEqualTo("PAID");

    Map<String, Object> debitNoteReq = new HashMap<>();
    debitNoteReq.put("purchaseId", purchaseId);
    debitNoteReq.put("referenceNumber", "DN-PAID-" + shortSuffix());
    debitNoteReq.put("memo", "Debit note after settlement");

    ResponseEntity<Map> debitResp =
        rest.exchange(
            "/api/v1/accounting/debit-notes",
            HttpMethod.POST,
            new HttpEntity<>(debitNoteReq, headers),
            Map.class);
    assertThat(debitResp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

    RawMaterialPurchase refreshed = purchaseRepository.findById(purchaseId).orElseThrow();
    assertThat(refreshed.getOutstandingAmount()).isEqualByComparingTo(BigDecimal.ZERO);
    assertThat(refreshed.getStatus()).isEqualTo("PAID");
  }

  @Test
  @DisplayName("Purchase return reduces stock and records movement")
  void purchaseReturnReducesStock() {
    LocalDate entryDate = TestDateUtils.safeDate(company);
    Long supplierId = createSupplier("P2P Return Supplier", "RET-" + shortSuffix());
    Long rawMaterialId =
        createRawMaterial("Return Raw Material", "RM-RET-" + shortSuffix(), inventory.getId());

    BigDecimal purchaseQty = new BigDecimal("10");
    BigDecimal unitCost = new BigDecimal("8.00");
    PurchaseWorkflowIds workflow =
        createPurchaseOrderAndReceipt(supplierId, rawMaterialId, purchaseQty, unitCost, entryDate);

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

    ResponseEntity<Map> purchaseResp =
        rest.exchange(
            "/api/v1/purchasing/raw-material-purchases",
            HttpMethod.POST,
            new HttpEntity<>(purchaseReq, headers),
            Map.class);
    assertThat(purchaseResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
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

    ResponseEntity<Map> returnResp =
        rest.exchange(
            "/api/v1/purchasing/raw-material-purchases/returns",
            HttpMethod.POST,
            new HttpEntity<>(returnReq, headers),
            Map.class);
    assertThat(returnResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);

    RawMaterial afterReturn = rawMaterialRepository.findById(rawMaterialId).orElseThrow();
    assertThat(afterReturn.getCurrentStock()).isEqualByComparingTo(new BigDecimal("6"));

    RawMaterialPurchase updatedPurchase = purchaseRepository.findById(purchaseId).orElseThrow();
    assertThat(updatedPurchase.getOutstandingAmount())
        .isEqualByComparingTo(new BigDecimal("48.00"));
    assertThat(updatedPurchase.getStatus()).isEqualTo("PARTIAL");

    List<RawMaterialMovement> movements =
        rawMaterialMovementRepository.findByReferenceTypeAndReferenceId(
            InventoryReference.PURCHASE_RETURN, returnRef);
    assertThat(movements).hasSize(1);
    RawMaterialMovement movement = movements.get(0);
    assertThat(movement.getMovementType()).isEqualTo("RETURN");
    assertThat(movement.getQuantity()).isEqualByComparingTo(new BigDecimal("4"));
    assertThat(movement.getJournalEntryId()).isNotNull();
  }

  @Test
  @DisplayName(
      "Purchase invoice replay with canonical Idempotency-Key returns original purchase without"
          + " duplicate posting")
  void purchaseInvoiceReplayUsesCanonicalIdempotencyHeader() {
    LocalDate entryDate = TestDateUtils.safeDate(company);
    Long supplierId = createSupplier("P2P Invoice Replay Supplier", "INV-REPLAY-" + shortSuffix());
    Long rawMaterialId =
        createRawMaterial(
            "Invoice Replay Material", "RM-INV-REPLAY-" + shortSuffix(), inventory.getId());
    BigDecimal quantity = new BigDecimal("7");
    BigDecimal unitCost = new BigDecimal("9.00");
    PurchaseWorkflowIds workflow =
        createPurchaseOrderAndReceipt(supplierId, rawMaterialId, quantity, unitCost, entryDate);
    GoodsReceipt goodsReceipt =
        goodsReceiptRepository.findById(workflow.goodsReceiptId()).orElseThrow();
    int receiptMovementsBeforeReplay =
        rawMaterialMovementRepository
            .findByRawMaterialCompanyAndReferenceTypeAndReferenceId(
                company, InventoryReference.GOODS_RECEIPT, goodsReceipt.getReceiptNumber())
            .size();

    Map<String, Object> line = new HashMap<>();
    line.put("rawMaterialId", rawMaterialId);
    line.put("quantity", quantity);
    line.put("costPerUnit", unitCost);

    String invoiceNumber = "INV-REPLAY-" + shortSuffix();
    Map<String, Object> purchaseReq = new HashMap<>();
    purchaseReq.put("supplierId", supplierId);
    purchaseReq.put("invoiceNumber", invoiceNumber);
    purchaseReq.put("invoiceDate", entryDate);
    purchaseReq.put("purchaseOrderId", workflow.purchaseOrderId());
    purchaseReq.put("goodsReceiptId", workflow.goodsReceiptId());
    purchaseReq.put("lines", List.of(line));

    String idempotencyKey = "purch-invoice-" + shortSuffix();
    HttpHeaders purchaseHeaders = headersWithIdempotencyKey(idempotencyKey);

    ResponseEntity<Map> firstResponse =
        rest.exchange(
            "/api/v1/purchasing/raw-material-purchases",
            HttpMethod.POST,
            new HttpEntity<>(purchaseReq, purchaseHeaders),
            Map.class);
    ResponseEntity<Map> replayResponse =
        rest.exchange(
            "/api/v1/purchasing/raw-material-purchases",
            HttpMethod.POST,
            new HttpEntity<>(purchaseReq, purchaseHeaders),
            Map.class);

    assertThat(firstResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(replayResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    Long firstPurchaseId =
        ((Number) ((Map<String, Object>) firstResponse.getBody().get("data")).get("id"))
            .longValue();
    Long replayPurchaseId =
        ((Number) ((Map<String, Object>) replayResponse.getBody().get("data")).get("id"))
            .longValue();
    assertThat(replayPurchaseId).isEqualTo(firstPurchaseId);
    assertThat(purchaseRepository.findByCompanyAndIdempotencyKey(company, idempotencyKey))
        .isPresent();
    assertThat(purchaseRepository.findByCompanyAndInvoiceNumberIgnoreCase(company, invoiceNumber))
        .isPresent();

    int receiptMovementsAfterReplay =
        rawMaterialMovementRepository
            .findByRawMaterialCompanyAndReferenceTypeAndReferenceId(
                company, InventoryReference.GOODS_RECEIPT, goodsReceipt.getReceiptNumber())
            .size();
    assertThat(receiptMovementsAfterReplay).isEqualTo(receiptMovementsBeforeReplay);
  }

  @Test
  @DisplayName("Purchase invoice rejects X-Idempotency-Key legacy header")
  void purchaseInvoiceRejectsLegacyIdempotencyHeader() {
    LocalDate entryDate = TestDateUtils.safeDate(company);
    Long supplierId = createSupplier("P2P Invoice Legacy Supplier", "INV-LEGACY-" + shortSuffix());
    Long rawMaterialId =
        createRawMaterial(
            "Invoice Legacy Material", "RM-INV-LEGACY-" + shortSuffix(), inventory.getId());
    BigDecimal quantity = new BigDecimal("4");
    BigDecimal unitCost = new BigDecimal("11.00");
    PurchaseWorkflowIds workflow =
        createPurchaseOrderAndReceipt(supplierId, rawMaterialId, quantity, unitCost, entryDate);

    String invoiceNumber = "INV-LEGACY-" + shortSuffix();
    Map<String, Object> purchaseReq = new HashMap<>();
    purchaseReq.put("supplierId", supplierId);
    purchaseReq.put("invoiceNumber", invoiceNumber);
    purchaseReq.put("invoiceDate", entryDate);
    purchaseReq.put("purchaseOrderId", workflow.purchaseOrderId());
    purchaseReq.put("goodsReceiptId", workflow.goodsReceiptId());
    purchaseReq.put(
        "lines",
        List.of(
            Map.of("rawMaterialId", rawMaterialId, "quantity", quantity, "costPerUnit", unitCost)));

    HttpHeaders legacyHeaders = headersWithLegacyIdempotencyKey("legacy-invoice-" + shortSuffix());
    ResponseEntity<Map> response =
        rest.exchange(
            "/api/v1/purchasing/raw-material-purchases",
            HttpMethod.POST,
            new HttpEntity<>(purchaseReq, legacyHeaders),
            Map.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    Map<String, Object> data = (Map<String, Object>) response.getBody().get("data");
    assertThat(data.get("message"))
        .isEqualTo("X-Idempotency-Key is not supported for purchase invoices; use Idempotency-Key");
    assertThat(purchaseRepository.findByCompanyAndInvoiceNumberIgnoreCase(company, invoiceNumber))
        .isEmpty();
  }

  @Test
  @DisplayName(
      "Purchase return replay with canonical Idempotency-Key returns original reversal without"
          + " duplicate stock effects")
  void purchaseReturnReplayUsesCanonicalIdempotencyHeader() {
    LocalDate entryDate = TestDateUtils.safeDate(company);
    Long supplierId = createSupplier("P2P Return Replay Supplier", "RET-REPLAY-" + shortSuffix());
    Long rawMaterialId =
        createRawMaterial(
            "Return Replay Material", "RM-RET-REPLAY-" + shortSuffix(), inventory.getId());
    BigDecimal purchaseQty = new BigDecimal("10");
    BigDecimal unitCost = new BigDecimal("8.00");
    PurchaseWorkflowIds workflow =
        createPurchaseOrderAndReceipt(supplierId, rawMaterialId, purchaseQty, unitCost, entryDate);

    Map<String, Object> purchaseReq = new HashMap<>();
    purchaseReq.put("supplierId", supplierId);
    purchaseReq.put("invoiceNumber", "INV-RET-REPLAY-" + shortSuffix());
    purchaseReq.put("invoiceDate", entryDate);
    purchaseReq.put("purchaseOrderId", workflow.purchaseOrderId());
    purchaseReq.put("goodsReceiptId", workflow.goodsReceiptId());
    purchaseReq.put(
        "lines",
        List.of(
            Map.of(
                "rawMaterialId", rawMaterialId, "quantity", purchaseQty, "costPerUnit", unitCost)));

    ResponseEntity<Map> purchaseResp =
        rest.exchange(
            "/api/v1/purchasing/raw-material-purchases",
            HttpMethod.POST,
            new HttpEntity<>(purchaseReq, headers),
            Map.class);
    assertThat(purchaseResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    Long purchaseId =
        ((Number) ((Map<String, Object>) purchaseResp.getBody().get("data")).get("id")).longValue();

    String returnIdempotencyKey = "purch-return-" + shortSuffix();
    HttpHeaders returnHeaders = headersWithIdempotencyKey(returnIdempotencyKey);
    Map<String, Object> returnReq = new HashMap<>();
    returnReq.put("supplierId", supplierId);
    returnReq.put("purchaseId", purchaseId);
    returnReq.put("rawMaterialId", rawMaterialId);
    returnReq.put("quantity", new BigDecimal("4"));
    returnReq.put("unitCost", unitCost);
    returnReq.put("returnDate", entryDate);
    returnReq.put("reason", "P2P return replay test");

    ResponseEntity<Map> firstReturnResp =
        rest.exchange(
            "/api/v1/purchasing/raw-material-purchases/returns",
            HttpMethod.POST,
            new HttpEntity<>(returnReq, returnHeaders),
            Map.class);
    ResponseEntity<Map> replayReturnResp =
        rest.exchange(
            "/api/v1/purchasing/raw-material-purchases/returns",
            HttpMethod.POST,
            new HttpEntity<>(returnReq, returnHeaders),
            Map.class);

    assertThat(firstReturnResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(replayReturnResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    Long firstJournalId =
        ((Number) ((Map<String, Object>) firstReturnResp.getBody().get("data")).get("id"))
            .longValue();
    Long replayJournalId =
        ((Number) ((Map<String, Object>) replayReturnResp.getBody().get("data")).get("id"))
            .longValue();
    assertThat(replayJournalId).isEqualTo(firstJournalId);

    RawMaterial afterReturn = rawMaterialRepository.findById(rawMaterialId).orElseThrow();
    assertThat(afterReturn.getCurrentStock()).isEqualByComparingTo(new BigDecimal("6"));
    List<RawMaterialMovement> returnMovements =
        rawMaterialMovementRepository.findByReferenceTypeAndReferenceId(
            InventoryReference.PURCHASE_RETURN, returnIdempotencyKey);
    assertThat(returnMovements).hasSize(1);
  }

  @Test
  @DisplayName("Purchase return rejects X-Idempotency-Key legacy header")
  void purchaseReturnRejectsLegacyIdempotencyHeader() {
    LocalDate entryDate = TestDateUtils.safeDate(company);
    Long supplierId = createSupplier("P2P Return Legacy Supplier", "RET-LEGACY-" + shortSuffix());
    Long rawMaterialId =
        createRawMaterial(
            "Return Legacy Material", "RM-RET-LEGACY-" + shortSuffix(), inventory.getId());
    BigDecimal purchaseQty = new BigDecimal("6");
    BigDecimal unitCost = new BigDecimal("7.00");
    PurchaseWorkflowIds workflow =
        createPurchaseOrderAndReceipt(supplierId, rawMaterialId, purchaseQty, unitCost, entryDate);

    Map<String, Object> purchaseReq = new HashMap<>();
    purchaseReq.put("supplierId", supplierId);
    purchaseReq.put("invoiceNumber", "INV-RET-LEGACY-" + shortSuffix());
    purchaseReq.put("invoiceDate", entryDate);
    purchaseReq.put("purchaseOrderId", workflow.purchaseOrderId());
    purchaseReq.put("goodsReceiptId", workflow.goodsReceiptId());
    purchaseReq.put(
        "lines",
        List.of(
            Map.of(
                "rawMaterialId", rawMaterialId, "quantity", purchaseQty, "costPerUnit", unitCost)));

    ResponseEntity<Map> purchaseResp =
        rest.exchange(
            "/api/v1/purchasing/raw-material-purchases",
            HttpMethod.POST,
            new HttpEntity<>(purchaseReq, headers),
            Map.class);
    assertThat(purchaseResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    Long purchaseId =
        ((Number) ((Map<String, Object>) purchaseResp.getBody().get("data")).get("id")).longValue();

    RawMaterial beforeReturn = rawMaterialRepository.findById(rawMaterialId).orElseThrow();
    HttpHeaders legacyHeaders = headersWithLegacyIdempotencyKey("legacy-return-" + shortSuffix());
    Map<String, Object> returnReq = new HashMap<>();
    returnReq.put("supplierId", supplierId);
    returnReq.put("purchaseId", purchaseId);
    returnReq.put("rawMaterialId", rawMaterialId);
    returnReq.put("quantity", BigDecimal.ONE);
    returnReq.put("unitCost", unitCost);
    returnReq.put("returnDate", entryDate);
    returnReq.put("reason", "legacy header should fail");

    ResponseEntity<Map> response =
        rest.exchange(
            "/api/v1/purchasing/raw-material-purchases/returns",
            HttpMethod.POST,
            new HttpEntity<>(returnReq, legacyHeaders),
            Map.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    Map<String, Object> data = (Map<String, Object>) response.getBody().get("data");
    assertThat(data.get("message"))
        .isEqualTo("X-Idempotency-Key is not supported for purchase returns; use Idempotency-Key");

    RawMaterial afterReturnAttempt = rawMaterialRepository.findById(rawMaterialId).orElseThrow();
    assertThat(afterReturnAttempt.getCurrentStock())
        .isEqualByComparingTo(beforeReturn.getCurrentStock());
  }

  @Test
  @DisplayName("GST return includes input tax from purchase flow")
  void gstReturnIncludesInputTaxFromPurchases() {
    LocalDate entryDate = TestDateUtils.safeDate(company);
    YearMonth period = YearMonth.from(entryDate);
    Long supplierId = createSupplier("GST Supplier", "GST-" + shortSuffix());
    Long rawMaterialId =
        createRawMaterial("GST Raw Material", "RM-GST-" + shortSuffix(), inventory.getId());

    ResponseEntity<Map> beforeResp =
        rest.exchange(
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
    PurchaseWorkflowIds workflow =
        createPurchaseOrderAndReceipt(supplierId, rawMaterialId, quantity, costPerUnit, entryDate);

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

    ResponseEntity<Map> purchaseResp =
        rest.exchange(
            "/api/v1/purchasing/raw-material-purchases",
            HttpMethod.POST,
            new HttpEntity<>(purchaseReq, headers),
            Map.class);
    assertThat(purchaseResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);

    ResponseEntity<Map> afterResp =
        rest.exchange(
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
    Long rawMaterialId =
        createRawMaterial("GST Return Material", "RM-GST-RET-" + shortSuffix(), inventory.getId());

    BigDecimal quantity = new BigDecimal("5");
    BigDecimal costPerUnit = new BigDecimal("100.00");
    BigDecimal taxAmount = new BigDecimal("90.00");
    PurchaseWorkflowIds workflow =
        createPurchaseOrderAndReceipt(supplierId, rawMaterialId, quantity, costPerUnit, entryDate);

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

    ResponseEntity<Map> purchaseResp =
        rest.exchange(
            "/api/v1/purchasing/raw-material-purchases",
            HttpMethod.POST,
            new HttpEntity<>(purchaseReq, headers),
            Map.class);
    assertThat(purchaseResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
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

    ResponseEntity<Map> returnResp =
        rest.exchange(
            "/api/v1/purchasing/raw-material-purchases/returns",
            HttpMethod.POST,
            new HttpEntity<>(returnReq, headers),
            Map.class);
    assertThat(returnResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);

    BigDecimal afterReturnInput = amount(gstReturn(period), "inputTax");
    assertThat(afterPurchaseInput.subtract(afterReturnInput)).isEqualByComparingTo(taxAmount);
  }

  @Test
  @DisplayName("GST return ignores draft journal entries")
  void gstReturnIgnoresDraftEntries() {
    LocalDate entryDate = TestDateUtils.safeDate(company);
    YearMonth period = YearMonth.from(entryDate);
    BigDecimal beforeOutput = gstOutputTax(period);

    Account gstOutput =
        accountRepository.findByCompanyAndCodeIgnoreCase(company, "GST-OUT-P2P-E2E").orElseThrow();

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
    draft.addLine(gstLine);

    JournalLine offsetLine = new JournalLine();
    offsetLine.setJournalEntry(draft);
    offsetLine.setAccount(cash);
    offsetLine.setDebit(new BigDecimal("12.34"));
    offsetLine.setCredit(BigDecimal.ZERO);
    draft.addLine(offsetLine);
    journalEntryRepository.saveAndFlush(draft);

    BigDecimal afterOutput = gstOutputTax(period);
    assertThat(afterOutput).isEqualByComparingTo(beforeOutput);
  }

  private Long createSupplier(String name, String code) {
    Map<String, Object> req =
        Map.of(
            "name",
            name,
            "code",
            code,
            "contactEmail",
            "p2p-" + shortSuffix() + "@bbp.com",
            "stateCode",
            "27",
            "creditLimit",
            new BigDecimal("25000.00"));
    ResponseEntity<Map> resp =
        rest.exchange(
            "/api/v1/suppliers", HttpMethod.POST, new HttpEntity<>(req, headers), Map.class);
    assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    Map<String, Object> data = (Map<String, Object>) resp.getBody().get("data");
    assertThat(data).containsKey("outstandingBalance");
    Long supplierId = ((Number) data.get("id")).longValue();

    ResponseEntity<Map> supplierDetailResp =
        rest.exchange(
            "/api/v1/suppliers/" + supplierId,
            HttpMethod.GET,
            new HttpEntity<>(headers),
            Map.class);
    assertThat(supplierDetailResp.getStatusCode()).isEqualTo(HttpStatus.OK);
    Map<String, Object> supplierDetailData =
        (Map<String, Object>) supplierDetailResp.getBody().get("data");
    assertThat(supplierDetailData).containsKey("outstandingBalance");

    ResponseEntity<Map> approveResp =
        rest.exchange(
            "/api/v1/suppliers/" + supplierId + "/approve",
            HttpMethod.POST,
            new HttpEntity<>(headers),
            Map.class);
    assertThat(approveResp.getStatusCode()).isEqualTo(HttpStatus.OK);

    ResponseEntity<Map> activateResp =
        rest.exchange(
            "/api/v1/suppliers/" + supplierId + "/activate",
            HttpMethod.POST,
            new HttpEntity<>(headers),
            Map.class);
    assertThat(activateResp.getStatusCode()).isEqualTo(HttpStatus.OK);

    return supplierId;
  }

  private Long createSupplierWithoutStateCode(String name, String code, String gstNumber) {
    Map<String, Object> req = new HashMap<>();
    req.put("name", name);
    req.put("code", code);
    req.put("contactEmail", "p2p-" + shortSuffix() + "@bbp.com");
    req.put("creditLimit", new BigDecimal("25000.00"));
    req.put("gstNumber", gstNumber);
    ResponseEntity<Map> resp =
        rest.exchange(
            "/api/v1/suppliers", HttpMethod.POST, new HttpEntity<>(req, headers), Map.class);
    assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    Map<String, Object> data = (Map<String, Object>) resp.getBody().get("data");
    Long supplierId = ((Number) data.get("id")).longValue();

    ResponseEntity<Map> approveResp =
        rest.exchange(
            "/api/v1/suppliers/" + supplierId + "/approve",
            HttpMethod.POST,
            new HttpEntity<>(headers),
            Map.class);
    assertThat(approveResp.getStatusCode()).isEqualTo(HttpStatus.OK);

    ResponseEntity<Map> activateResp =
        rest.exchange(
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

  private PurchaseWorkflowIds createPurchaseOrderAndReceipt(
      Long supplierId,
      Long rawMaterialId,
      BigDecimal quantity,
      BigDecimal costPerUnit,
      LocalDate entryDate) {
    Long purchaseOrderId =
        createPurchaseOrder(supplierId, rawMaterialId, quantity, costPerUnit, entryDate);
    Long goodsReceiptId =
        createGoodsReceipt(purchaseOrderId, rawMaterialId, quantity, costPerUnit, entryDate);
    return new PurchaseWorkflowIds(purchaseOrderId, goodsReceiptId);
  }

  private Long createPurchaseInvoiceForWorkflow(
      Long supplierId,
      Long rawMaterialId,
      BigDecimal quantity,
      BigDecimal costPerUnit,
      LocalDate invoiceDate,
      PurchaseWorkflowIds workflow,
      String invoiceNumber) {
    Map<String, Object> line = new HashMap<>();
    line.put("rawMaterialId", rawMaterialId);
    line.put("quantity", quantity);
    line.put("costPerUnit", costPerUnit);

    Map<String, Object> purchaseReq = new HashMap<>();
    purchaseReq.put("supplierId", supplierId);
    purchaseReq.put("invoiceNumber", invoiceNumber);
    purchaseReq.put("invoiceDate", invoiceDate);
    purchaseReq.put("purchaseOrderId", workflow.purchaseOrderId());
    purchaseReq.put("goodsReceiptId", workflow.goodsReceiptId());
    purchaseReq.put("lines", List.of(line));

    ResponseEntity<Map> purchaseResp =
        rest.exchange(
            "/api/v1/purchasing/raw-material-purchases",
            HttpMethod.POST,
            new HttpEntity<>(purchaseReq, headers),
            Map.class);
    assertThat(purchaseResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    Map<String, Object> purchaseData = (Map<String, Object>) purchaseResp.getBody().get("data");
    return ((Number) purchaseData.get("id")).longValue();
  }

  private Long createPurchaseOrder(
      Long supplierId,
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

    ResponseEntity<Map> poResp =
        rest.exchange(
            "/api/v1/purchasing/purchase-orders",
            HttpMethod.POST,
            new HttpEntity<>(poReq, headers),
            Map.class);
    assertThat(poResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    Map<String, Object> poData = (Map<String, Object>) poResp.getBody().get("data");
    Long purchaseOrderId = ((Number) poData.get("id")).longValue();

    ResponseEntity<Map> approveResp =
        rest.exchange(
            "/api/v1/purchasing/purchase-orders/" + purchaseOrderId + "/approve",
            HttpMethod.POST,
            new HttpEntity<>(headers),
            Map.class);
    assertThat(approveResp.getStatusCode()).isEqualTo(HttpStatus.OK);

    ResponseEntity<Map> timelineResp =
        rest.exchange(
            "/api/v1/purchasing/purchase-orders/" + purchaseOrderId + "/timeline",
            HttpMethod.GET,
            new HttpEntity<>(headers),
            Map.class);
    assertThat(timelineResp.getStatusCode()).isEqualTo(HttpStatus.OK);
    List<Map<String, Object>> timeline =
        (List<Map<String, Object>>) timelineResp.getBody().get("data");
    assertThat(timeline).isNotEmpty();
    assertThat(timeline)
        .allSatisfy(
            event -> {
              assertThat(event).containsKeys("status", "timestamp", "actor");
            });
    assertThat(timeline).anySatisfy(event -> assertThat(event.get("status")).isEqualTo("APPROVED"));

    return purchaseOrderId;
  }

  private Long createGoodsReceipt(
      Long purchaseOrderId,
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

    ResponseEntity<Map> grResp =
        rest.exchange(
            "/api/v1/purchasing/goods-receipts",
            HttpMethod.POST,
            new HttpEntity<>(grReq, headers),
            Map.class);
    assertThat(grResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    Map<String, Object> grData = (Map<String, Object>) grResp.getBody().get("data");
    return ((Number) grData.get("id")).longValue();
  }

  private record PurchaseWorkflowIds(Long purchaseOrderId, Long goodsReceiptId) {}

  private Account ensureAccount(String code, String name, AccountType type) {
    return accountRepository
        .findByCompanyAndCodeIgnoreCase(company, code)
        .orElseGet(
            () -> {
              Account account = new Account();
              account.setCompany(company);
              account.setCode(code);
              account.setName(name);
              account.setType(type);
              return accountRepository.save(account);
            });
  }

  private void assertPurchaseJournalTotals(
      Long journalEntryId,
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

  private BigDecimal sumDebits(JournalEntry entry) {
    if (entry == null || entry.getLines() == null) {
      return BigDecimal.ZERO;
    }
    return entry.getLines().stream()
        .map(line -> safeAmount(line.getDebit()))
        .reduce(BigDecimal.ZERO, BigDecimal::add);
  }

  private BigDecimal findRoundingImbalanceAmount(
      List<BigDecimal> sourceDebitComponents, BigDecimal sourceAmount) {
    BigDecimal maxAmount = sourceAmount.setScale(2, RoundingMode.DOWN);
    long maxCents = maxAmount.movePointRight(2).longValue();
    for (long cents = 1; cents < maxCents; cents++) {
      BigDecimal amount = BigDecimal.valueOf(cents, 2);
      BigDecimal ratio = amount.divide(sourceAmount, 6, RoundingMode.HALF_UP);
      BigDecimal scaledDebits = sourceAmount.multiply(ratio).setScale(2, RoundingMode.HALF_UP);
      BigDecimal scaledCredits =
          sourceDebitComponents.stream()
              .map(
                  component ->
                      safeAmount(component).multiply(ratio).setScale(2, RoundingMode.HALF_UP))
              .reduce(BigDecimal.ZERO, BigDecimal::add);
      if (scaledDebits.compareTo(BigDecimal.ZERO) > 0
          && scaledCredits.compareTo(BigDecimal.ZERO) > 0
          && scaledDebits.compareTo(scaledCredits) != 0) {
        return amount;
      }
    }
    return null;
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
    ResponseEntity<Map> resp =
        rest.exchange(
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

  private List<?> transactionEventTrail(Long journalEntryId) {
    ResponseEntity<Map> response =
        rest.exchange(
            "/api/v1/accounting/audit/transactions/" + journalEntryId,
            HttpMethod.GET,
            new HttpEntity<>(headers),
            Map.class);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    Object body = response.getBody();
    assertThat(body).isInstanceOf(Map.class);
    Object data = ((Map<?, ?>) body).get("data");
    assertThat(data).isInstanceOf(Map.class);
    Object eventTrail = ((Map<?, ?>) data).get("eventTrail");
    assertThat(eventTrail).isInstanceOf(List.class);
    return (List<?>) eventTrail;
  }

  private HttpHeaders authHeaders() {
    Map<String, Object> req =
        Map.of(
            "email", ADMIN_EMAIL,
            "password", ADMIN_PASSWORD,
            "companyCode", COMPANY_CODE);
    ResponseEntity<Map> login = rest.postForEntity("/api/v1/auth/login", req, Map.class);
    assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
    String token = (String) login.getBody().get("accessToken");
    HttpHeaders h = new HttpHeaders();
    h.setBearerAuth(token);
    h.setContentType(MediaType.APPLICATION_JSON);
    h.set("X-Company-Code", COMPANY_CODE);
    return h;
  }

  private HttpHeaders headersWithIdempotencyKey(String idempotencyKey) {
    HttpHeaders scoped = new HttpHeaders();
    scoped.putAll(headers);
    scoped.set("Idempotency-Key", idempotencyKey);
    return scoped;
  }

  private HttpHeaders headersWithCorrelationId(HttpHeaders baseHeaders, UUID correlationId) {
    HttpHeaders scoped = new HttpHeaders();
    scoped.putAll(baseHeaders);
    scoped.set("X-Correlation-Id", correlationId.toString());
    return scoped;
  }

  private HttpHeaders headersWithLegacyIdempotencyKey(String idempotencyKey) {
    HttpHeaders scoped = new HttpHeaders();
    scoped.putAll(headers);
    scoped.set("X-Idempotency-Key", idempotencyKey);
    return scoped;
  }

  private String shortSuffix() {
    String token = Long.toString(System.nanoTime());
    return token.length() > 8 ? token.substring(token.length() - 8) : token;
  }

  private AuditActionEvent awaitBusinessAuditEvent(
      Long companyId, String module, String action, String entityType, String entityId) {
    Instant deadline = Instant.now().plus(Duration.ofSeconds(5));
    while (Instant.now().isBefore(deadline)) {
      Optional<AuditActionEvent> match =
          auditActionEventRepository
              .findTopByCompanyIdAndModuleIgnoreCaseAndActionIgnoreCaseAndEntityTypeIgnoreCaseAndEntityIdOrderByOccurredAtDesc(
                  companyId, module, action, entityType, entityId);
      if (match.isPresent()) {
        return match.get();
      }
      try {
        Thread.sleep(100);
      } catch (InterruptedException ex) {
        Thread.currentThread().interrupt();
        throw new AssertionError("Interrupted while waiting for audit event", ex);
      }
    }
    throw new AssertionError(
        "Audit event not found: module="
            + module
            + ", action="
            + action
            + ", entityType="
            + entityType
            + ", entityId="
            + entityId);
  }
}
