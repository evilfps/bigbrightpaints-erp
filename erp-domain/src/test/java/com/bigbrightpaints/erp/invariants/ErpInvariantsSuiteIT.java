package com.bigbrightpaints.erp.invariants;

import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountType;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.DealerLedgerRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.DealerLedgerEntry;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalLine;
import com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocation;
import com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocationRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.SupplierLedgerRepository;
import com.bigbrightpaints.erp.modules.accounting.service.TemporalBalanceService;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.modules.factory.domain.PackagingSizeMapping;
import com.bigbrightpaints.erp.modules.factory.domain.PackagingSizeMappingRepository;
import com.bigbrightpaints.erp.modules.factory.domain.ProductionLogRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGood;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryMovement;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryMovementRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryReference;
import com.bigbrightpaints.erp.modules.inventory.domain.MaterialType;
import com.bigbrightpaints.erp.modules.inventory.domain.PackagingSlip;
import com.bigbrightpaints.erp.modules.inventory.domain.PackagingSlipRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterial;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialBatch;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialBatchRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialMovement;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialMovementRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialRepository;
import com.bigbrightpaints.erp.modules.invoice.domain.Invoice;
import com.bigbrightpaints.erp.modules.invoice.domain.InvoiceLine;
import com.bigbrightpaints.erp.modules.invoice.domain.InvoiceRepository;
import com.bigbrightpaints.erp.modules.hr.domain.Employee;
import com.bigbrightpaints.erp.modules.hr.domain.EmployeeRepository;
import com.bigbrightpaints.erp.modules.hr.domain.PayrollRun;
import com.bigbrightpaints.erp.modules.hr.domain.PayrollRunLine;
import com.bigbrightpaints.erp.modules.hr.domain.PayrollRunLineRepository;
import com.bigbrightpaints.erp.modules.hr.domain.PayrollRunRepository;
import com.bigbrightpaints.erp.modules.production.domain.ProductionBrand;
import com.bigbrightpaints.erp.modules.production.domain.ProductionBrandRepository;
import com.bigbrightpaints.erp.modules.production.domain.ProductionProduct;
import com.bigbrightpaints.erp.modules.production.domain.ProductionProductRepository;
import com.bigbrightpaints.erp.modules.purchasing.domain.RawMaterialPurchase;
import com.bigbrightpaints.erp.modules.purchasing.domain.RawMaterialPurchaseRepository;
import com.bigbrightpaints.erp.modules.sales.domain.DealerRepository;
import com.bigbrightpaints.erp.modules.purchasing.domain.SupplierRepository;
import com.bigbrightpaints.erp.test.AbstractIntegrationTest;
import com.bigbrightpaints.erp.test.support.CanonicalErpDataset;
import com.bigbrightpaints.erp.test.support.CanonicalErpDatasetBuilder;
import com.bigbrightpaints.erp.test.support.TestDateUtils;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("erp-invariants")
@DisplayName("ERP Invariants: Golden Paths")
@Tag("critical")
@Tag("reconciliation")
public class ErpInvariantsSuiteIT extends AbstractIntegrationTest {

    private static final String PASSWORD = "test123";
    private static final List<String> BASE_ROLES = List.of(
            "ROLE_ADMIN", "ROLE_ACCOUNTING", "ROLE_SALES", "ROLE_FACTORY", "dispatch.confirm"
    );

    @Autowired private TestRestTemplate rest;
    @Autowired private CompanyRepository companyRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private DealerRepository dealerRepository;
    @Autowired private SupplierRepository supplierRepository;
    @Autowired private ProductionBrandRepository brandRepository;
    @Autowired private ProductionProductRepository productRepository;
    @Autowired private FinishedGoodRepository finishedGoodRepository;
    @Autowired private RawMaterialRepository rawMaterialRepository;
    @Autowired private RawMaterialBatchRepository rawMaterialBatchRepository;
    @Autowired private RawMaterialMovementRepository rawMaterialMovementRepository;
    @Autowired private InvoiceRepository invoiceRepository;
    @Autowired private PackagingSlipRepository packagingSlipRepository;
    @Autowired private InventoryMovementRepository inventoryMovementRepository;
    @Autowired private RawMaterialPurchaseRepository purchaseRepository;
    @Autowired private EmployeeRepository employeeRepository;
    @Autowired private PayrollRunRepository payrollRunRepository;
    @Autowired private PayrollRunLineRepository payrollRunLineRepository;
    @Autowired private JournalEntryRepository journalEntryRepository;
    @Autowired private DealerLedgerRepository dealerLedgerRepository;
    @Autowired private SupplierLedgerRepository supplierLedgerRepository;
    @Autowired private PartnerSettlementAllocationRepository settlementAllocationRepository;
    @Autowired private TemporalBalanceService temporalBalanceService;
    @Autowired private ProductionLogRepository productionLogRepository;
    @Autowired private PackagingSizeMappingRepository packagingSizeMappingRepository;

    private CanonicalErpDatasetBuilder datasetBuilder;
    private ErpInvariantAssertions invariants;
    private CanonicalErpDataset o2c;
    private CanonicalErpDataset p2p;
    private CanonicalErpDataset prod;
    private CanonicalErpDataset payroll;
    private CanonicalErpDataset r2r;

    @BeforeAll
    void setupFixtures() {
        seedUsers();
        datasetBuilder = new CanonicalErpDatasetBuilder(
                dataSeeder,
                companyRepository,
                accountRepository,
                dealerRepository,
                supplierRepository,
                brandRepository,
                productRepository,
                finishedGoodRepository
        );
        o2c = datasetBuilder.seedCompany("ERP-O2C");
        p2p = datasetBuilder.seedCompany("ERP-P2P");
        prod = datasetBuilder.seedCompany("ERP-PROD");
        payroll = datasetBuilder.seedCompany("ERP-PAY");
        r2r = datasetBuilder.seedCompany("ERP-R2R");

        invariants = new ErpInvariantAssertions(
                companyRepository,
                journalEntryRepository,
                invoiceRepository,
                purchaseRepository,
                payrollRunRepository,
                packagingSlipRepository,
                inventoryMovementRepository,
                finishedGoodRepository,
                rawMaterialRepository,
                dealerLedgerRepository,
                supplierLedgerRepository,
                accountRepository,
                temporalBalanceService
        );
    }

    private void seedUsers() {
        dataSeeder.ensureUser("o2c@test.com", PASSWORD, "O2C Admin", "ERP-O2C", BASE_ROLES);
        dataSeeder.ensureUser("p2p@test.com", PASSWORD, "P2P Admin", "ERP-P2P", BASE_ROLES);
        dataSeeder.ensureUser("prod@test.com", PASSWORD, "PROD Admin", "ERP-PROD", BASE_ROLES);
        dataSeeder.ensureUser("pay@test.com", PASSWORD, "PAY Admin", "ERP-PAY", BASE_ROLES);
        dataSeeder.ensureUser("r2r@test.com", PASSWORD, "R2R Admin", "ERP-R2R", BASE_ROLES);
    }

    @Test
    @DisplayName("Record-to-Report: manual journal + reversal stays balanced")
    void recordToReport_manualJournalReversal() {
        Company company = r2r.company();
        HttpHeaders headers = authHeaders("r2r@test.com", company.getCode());
        LocalDate entryDate = TestDateUtils.safeDate(company);

        Map<String, Object> payload = new HashMap<>();
        payload.put("entryDate", entryDate);
        payload.put("memo", "R2R adjustment entry");
        payload.put("lines", List.of(
                journalLine(r2r.requireAccount("CASH"), new BigDecimal("100.00"), BigDecimal.ZERO, "Cash"),
                journalLine(r2r.requireAccount("REV"), BigDecimal.ZERO, new BigDecimal("100.00"), "Revenue")
        ));

        ResponseEntity<Map> response = rest.exchange("/api/v1/accounting/journal-entries",
                HttpMethod.POST, new HttpEntity<>(payload, headers), Map.class);
        Map<?, ?> data = requireData(response, "post journal");
        Long journalId = ((Number) data.get("id")).longValue();

        invariants.assertJournalBalanced(journalId);

        Map<String, Object> reversal = new HashMap<>();
        reversal.put("reversalDate", entryDate);
        reversal.put("voidOnly", false);
        reversal.put("reason", "Test reversal");
        reversal.put("memo", "R2R reversal");
        reversal.put("adminOverride", false);

        ResponseEntity<Map> reversalResp = rest.exchange(
                "/api/v1/accounting/journal-entries/" + journalId + "/reverse",
                HttpMethod.POST,
                new HttpEntity<>(reversal, headers),
                Map.class);
        requireData(reversalResp, "reverse journal");

        invariants.assertReversalCreatesBalancedInverse(journalId);
    }

    @Test
    @DisplayName("Order-to-Cash: order -> dispatch -> invoice -> settlement")
    void orderToCash_goldenPath() {
        Company company = o2c.company();
        HttpHeaders headers = authHeaders("o2c@test.com", company.getCode());
        FinishedGood finishedGood = o2c.finishedGood();
        LocalDate entryDate = TestDateUtils.safeDate(company);

        Map<String, Object> orderLine = new HashMap<>();
        orderLine.put("productCode", finishedGood.getProductCode());
        orderLine.put("description", "O2C fixture item");
        orderLine.put("quantity", new BigDecimal("5"));
        orderLine.put("unitPrice", new BigDecimal("100.00"));
        orderLine.put("gstRate", BigDecimal.ZERO);

        Map<String, Object> orderReq = new HashMap<>();
        orderReq.put("dealerId", o2c.dealer().getId());
        orderReq.put("totalAmount", new BigDecimal("500.00"));
        orderReq.put("currency", "INR");
        orderReq.put("gstTreatment", "NONE");
        orderReq.put("items", List.of(orderLine));
        orderReq.put("idempotencyKey", "O2C-ORDER-001");

        ResponseEntity<Map> orderResp = rest.exchange("/api/v1/sales/orders",
                HttpMethod.POST, new HttpEntity<>(orderReq, headers), Map.class);
        Map<?, ?> orderData = requireData(orderResp, "create order");
        Long orderId = ((Number) orderData.get("id")).longValue();
        ResponseEntity<Map> orderRepeatResp = rest.exchange(
                "/api/v1/sales/orders",
                HttpMethod.POST,
                new HttpEntity<>(orderReq, headers),
                Map.class);
        Map<?, ?> orderRepeatData = requireData(
                orderRepeatResp,
                "create order idempotent");
        Long repeatOrderId = ((Number) orderRepeatData.get("id")).longValue();
        assertThat(repeatOrderId).isEqualTo(orderId);

        rest.exchange("/api/v1/sales/orders/" + orderId + "/confirm",
                HttpMethod.POST, new HttpEntity<>(headers), Map.class);

        Map<String, Object> dispatchReq = new HashMap<>();
        dispatchReq.put("orderId", orderId);
        dispatchReq.put("confirmedBy", "o2c-test");

        ResponseEntity<Map> dispatchResp = rest.exchange("/api/v1/sales/dispatch/confirm",
                HttpMethod.POST, new HttpEntity<>(dispatchReq, headers), Map.class);
        Map<?, ?> dispatchData = requireData(dispatchResp, "dispatch confirm");
        Object invoiceValue = dispatchData.get("finalInvoiceId");
        if (!(invoiceValue instanceof Number invoiceNumber)) {
            throw new AssertionError("dispatch confirm response missing finalInvoiceId: " + dispatchData);
        }
        Long invoiceId = invoiceNumber.longValue();
        Object arJournalValue = dispatchData.get("arJournalEntryId");
        if (!(arJournalValue instanceof Number arJournalNumber)) {
            throw new AssertionError("dispatch confirm response missing arJournalEntryId: " + dispatchData);
        }
        Long arJournalId = arJournalNumber.longValue();

        Invoice invoice = invoiceRepository.findById(invoiceId)
                .orElseThrow(() -> new AssertionError("Invoice missing for O2C flow"));
        BigDecimal invoiceSubtotal = invoice.getSubtotal() != null ? invoice.getSubtotal() : BigDecimal.ZERO;
        BigDecimal invoiceTax = invoice.getTaxTotal() != null ? invoice.getTaxTotal() : BigDecimal.ZERO;
        BigDecimal invoiceTotal = invoice.getTotalAmount() != null ? invoice.getTotalAmount() : BigDecimal.ZERO;
        assertThat(invoiceSubtotal.add(invoiceTax)).isEqualByComparingTo(invoiceTotal);
        for (InvoiceLine line : invoice.getLines()) {
            BigDecimal lineNet = line.getTaxableAmount() != null ? line.getTaxableAmount() : BigDecimal.ZERO;
            BigDecimal lineTax = line.getTaxAmount() != null ? line.getTaxAmount() : BigDecimal.ZERO;
            BigDecimal lineTotal = line.getLineTotal() != null ? line.getLineTotal() : BigDecimal.ZERO;
            assertThat(lineNet.add(lineTax)).isEqualByComparingTo(lineTotal);
            BigDecimal discount = line.getDiscountAmount() != null ? line.getDiscountAmount() : BigDecimal.ZERO;
            assertThat(discount).isEqualByComparingTo(BigDecimal.ZERO);
        }
        JournalEntry arJournal = journalEntryRepository.findById(arJournalId)
                .orElseThrow(() -> new AssertionError("AR journal missing for dispatch " + arJournalId));
        String arReference = arJournal.getReferenceNumber();
        invariants.assertJournalLinkedTo("INVOICE", invoiceId);
        invariants.assertJournalBalanced(invoice.getJournalEntry().getId());
        List<DealerLedgerEntry> invoiceLedgerEntries =
                dealerLedgerRepository.findByCompanyAndJournalEntry(company, invoice.getJournalEntry());
        assertThat(invoiceLedgerEntries)
                .as("dealer ledger entries created for invoice")
                .isNotEmpty();
        for (DealerLedgerEntry entry : invoiceLedgerEntries) {
            assertThat(entry.getInvoiceNumber()).isEqualTo(invoice.getInvoiceNumber());
            assertThat(entry.getDueDate()).isEqualTo(invoice.getDueDate());
            assertThat(entry.getPaymentStatus()).isEqualTo("UNPAID");
            assertThat(entry.getAmountPaid()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(entry.getPaidDate()).isNull();
        }

        PackagingSlip slip = packagingSlipRepository.findAllByCompanyAndSalesOrderId(company, orderId)
                .stream().findFirst()
                .orElseThrow(() -> new AssertionError("Packaging slip missing for order " + orderId));
        assertThat(slip.getInvoiceId()).isEqualTo(invoiceId);
        assertThat(slip.getJournalEntryId())
                .as("packaging slip AR journal link")
                .isEqualTo(arJournalId);
        assertThat(slip.getCogsJournalEntryId())
                .as("packaging slip COGS journal link")
                .isNotNull();
        invariants.assertJournalBalanced(slip.getCogsJournalEntryId());
        invariants.assertJournalLinkedTo("PACKAGING_SLIP", slip.getId());

        List<InventoryMovement> movements = inventoryMovementRepository
                .findByReferenceTypeAndReferenceIdOrderByCreatedAtAsc(InventoryReference.SALES_ORDER, orderId.toString());
        assertThat(movements).as("inventory movements created").isNotEmpty();
        invariants.assertNoNegativeStock(company.getId(), finishedGood.getProductCode());
        List<Long> movementIds = movements.stream()
                .map(InventoryMovement::getId)
                .toList();

        ResponseEntity<Map> dispatchRepeatResp = rest.exchange(
                "/api/v1/sales/dispatch/confirm",
                HttpMethod.POST,
                new HttpEntity<>(dispatchReq, headers),
                Map.class);
        Map<?, ?> dispatchRepeatData = requireData(
                dispatchRepeatResp,
                "dispatch confirm idempotent");
        Object repeatInvoiceValue = dispatchRepeatData.get("finalInvoiceId");
        if (!(repeatInvoiceValue instanceof Number repeatInvoiceNumber)) {
            throw new AssertionError("dispatch repeat response missing finalInvoiceId: " + dispatchRepeatData);
        }
        Object repeatJournalValue = dispatchRepeatData.get("arJournalEntryId");
        if (!(repeatJournalValue instanceof Number repeatJournalNumber)) {
            throw new AssertionError("dispatch repeat response missing arJournalEntryId: " + dispatchRepeatData);
        }
        assertThat(repeatInvoiceNumber.longValue()).isEqualTo(invoiceId);
        assertThat(repeatJournalNumber.longValue()).isEqualTo(arJournalId);
        List<InventoryMovement> repeatMovements = inventoryMovementRepository
                .findByReferenceTypeAndReferenceIdOrderByCreatedAtAsc(InventoryReference.SALES_ORDER, orderId.toString());
        assertThat(repeatMovements).as("inventory movements unchanged on replay")
                .hasSize(movementIds.size());
        List<Long> repeatMovementIds = repeatMovements.stream()
                .map(InventoryMovement::getId)
                .toList();
        assertThat(repeatMovementIds).containsExactlyElementsOf(movementIds);
        long arReferenceCount = journalEntryRepository.findAll().stream()
                .filter(entry -> entry.getCompany().getId().equals(company.getId()))
                .filter(entry -> arReference.equals(entry.getReferenceNumber()))
                .count();
        assertThat(arReferenceCount)
                .as("AR journal reference should be unique for dispatch")
                .isEqualTo(1);

        Map<String, Object> allocation = Map.of(
                "invoiceId", invoiceId,
                "appliedAmount", invoice.getTotalAmount()
        );
        Map<String, Object> settlementReq = new HashMap<>();
        settlementReq.put("dealerId", o2c.dealer().getId());
        settlementReq.put("cashAccountId", o2c.requireAccount("CASH").getId());
        settlementReq.put("settlementDate", entryDate);
        settlementReq.put("referenceNumber", "O2C-SETTLE-001");
        settlementReq.put("idempotencyKey", "O2C-SETTLE-001");
        settlementReq.put("allocations", List.of(allocation));

        ResponseEntity<Map> settleResp = rest.exchange("/api/v1/accounting/settlements/dealers",
                HttpMethod.POST, new HttpEntity<>(settlementReq, headers), Map.class);
        Map<?, ?> settleData = requireData(settleResp, "dealer settlement");
        Map<?, ?> journalPayload = (Map<?, ?>) settleData.get("journalEntry");
        Long settlementJeId = ((Number) journalPayload.get("id")).longValue();
        invariants.assertJournalBalanced(settlementJeId);
        List<DealerLedgerEntry> settledEntries =
                dealerLedgerRepository.findByCompanyAndJournalEntry(company, invoice.getJournalEntry());
        assertThat(settledEntries)
                .as("dealer ledger entries updated after settlement")
                .isNotEmpty();
        for (DealerLedgerEntry entry : settledEntries) {
            assertThat(entry.getPaymentStatus()).isEqualTo("PAID");
            assertThat(entry.getAmountPaid()).isEqualByComparingTo(invoice.getTotalAmount());
            assertThat(entry.getPaidDate()).isEqualTo(entryDate);
        }

        ResponseEntity<Map> settleRepeatResp = rest.exchange(
                "/api/v1/accounting/settlements/dealers",
                HttpMethod.POST,
                new HttpEntity<>(settlementReq, headers),
                Map.class);
        Map<?, ?> settleRepeatData = requireData(
                settleRepeatResp,
                "dealer settlement idempotent");
        Map<?, ?> repeatJournalPayload = (Map<?, ?>) settleRepeatData.get("journalEntry");
        Long repeatSettlementId = ((Number) repeatJournalPayload.get("id")).longValue();
        assertThat(repeatSettlementId).isEqualTo(settlementJeId);
        Invoice settledInvoice = invoiceRepository.findById(invoiceId)
                .orElseThrow(() -> new AssertionError("Invoice missing after settlement replay"));
        List<DealerLedgerEntry> settledEntriesRepeat =
                dealerLedgerRepository.findByCompanyAndJournalEntry(company, settledInvoice.getJournalEntry());
        assertThat(settledEntriesRepeat)
                .as("dealer ledger entries retained after settlement replay")
                .isNotEmpty();
        for (DealerLedgerEntry entry : settledEntriesRepeat) {
            assertThat(entry.getPaymentStatus()).isEqualTo("PAID");
            assertThat(entry.getAmountPaid()).isEqualByComparingTo(settledInvoice.getTotalAmount());
            assertThat(entry.getPaidDate()).isEqualTo(entryDate);
        }

        invariants.assertSubledgerReconciles(o2c.requireAccount("AR").getId(), null);
    }

    @Test
    @DisplayName("Order-to-Cash: sales return restocks inventory and posts reversals")
    void orderToCash_salesReturnRestocksInventory() {
        Company company = o2c.company();
        HttpHeaders headers = authHeaders("o2c@test.com", company.getCode());
        FinishedGood finishedGood = o2c.finishedGood();

        Map<String, Object> orderLine = new HashMap<>();
        orderLine.put("productCode", finishedGood.getProductCode());
        orderLine.put("description", "O2C return item");
        orderLine.put("quantity", new BigDecimal("2"));
        orderLine.put("unitPrice", new BigDecimal("100.00"));
        orderLine.put("gstRate", BigDecimal.ZERO);

        Map<String, Object> orderReq = new HashMap<>();
        orderReq.put("dealerId", o2c.dealer().getId());
        orderReq.put("totalAmount", new BigDecimal("200.00"));
        orderReq.put("currency", "INR");
        orderReq.put("gstTreatment", "NONE");
        orderReq.put("items", List.of(orderLine));
        orderReq.put("idempotencyKey", "O2C-RETURN-ORDER-001");

        ResponseEntity<Map> orderResp = rest.exchange("/api/v1/sales/orders",
                HttpMethod.POST, new HttpEntity<>(orderReq, headers), Map.class);
        Map<?, ?> orderData = requireData(orderResp, "create order for return");
        Long orderId = ((Number) orderData.get("id")).longValue();

        rest.exchange("/api/v1/sales/orders/" + orderId + "/confirm",
                HttpMethod.POST, new HttpEntity<>(headers), Map.class);

        Map<String, Object> dispatchReq = new HashMap<>();
        dispatchReq.put("orderId", orderId);
        dispatchReq.put("confirmedBy", "o2c-test");

        ResponseEntity<Map> dispatchResp = rest.exchange("/api/v1/sales/dispatch/confirm",
                HttpMethod.POST, new HttpEntity<>(dispatchReq, headers), Map.class);
        Map<?, ?> dispatchData = requireData(dispatchResp, "dispatch confirm for return");
        Long invoiceId = ((Number) dispatchData.get("finalInvoiceId")).longValue();

        Invoice invoice = invoiceRepository.findById(invoiceId)
                .orElseThrow(() -> new AssertionError("Invoice missing for return flow"));
        if (invoice.getLines().isEmpty()) {
            throw new AssertionError("Invoice lines missing for return flow");
        }
        InvoiceLine invoiceLine = invoice.getLines().get(0);

        BigDecimal stockAfterDispatch = finishedGoodRepository.findById(finishedGood.getId())
                .orElseThrow(() -> new AssertionError("Finished good missing after dispatch"))
                .getCurrentStock();

        Map<String, Object> returnLine = Map.of(
                "invoiceLineId", invoiceLine.getId(),
                "quantity", new BigDecimal("1")
        );
        Map<String, Object> returnReq = new HashMap<>();
        returnReq.put("invoiceId", invoiceId);
        returnReq.put("reason", "Damaged on delivery");
        returnReq.put("lines", List.of(returnLine));

        ResponseEntity<Map> returnResp = rest.exchange("/api/v1/accounting/sales/returns",
                HttpMethod.POST, new HttpEntity<>(returnReq, headers), Map.class);
        requireData(returnResp, "sales return");

        BigDecimal stockAfterReturn = finishedGoodRepository.findById(finishedGood.getId())
                .orElseThrow(() -> new AssertionError("Finished good missing after return"))
                .getCurrentStock();
        assertThat(stockAfterReturn)
                .as("stock should increase by returned quantity")
                .isEqualByComparingTo(stockAfterDispatch.add(new BigDecimal("1")));

        String salesReturnRef = "CRN-" + invoice.getInvoiceNumber();
        assertThat(journalEntryRepository.findByCompanyAndReferenceNumber(company, salesReturnRef))
                .as("sales return journal entry exists")
                .isPresent();
        assertThat(journalEntryRepository.findFirstByCompanyAndReferenceNumberStartingWith(company,
                salesReturnRef + "-COGS"))
                .as("COGS reversal journal entry exists")
                .isPresent();

        List<InventoryMovement> returnMovements =
                inventoryMovementRepository.findByReferenceTypeAndReferenceIdStartingWithOrderByCreatedAtAsc(
                        "SALES_RETURN",
                        invoice.getInvoiceNumber());
        BigDecimal returnedQuantity = returnMovements.stream()
                .map(InventoryMovement::getQuantity)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(returnedQuantity)
                .as("inventory return movement quantity")
                .isEqualByComparingTo(new BigDecimal("1"));
    }

    @Test
    @DisplayName("Order-to-Cash: credit note reverses invoice journal")
    void orderToCash_creditNoteReversesInvoice() {
        Company company = o2c.company();
        HttpHeaders headers = authHeaders("o2c@test.com", company.getCode());
        FinishedGood finishedGood = o2c.finishedGood();

        Map<String, Object> orderLine = new HashMap<>();
        orderLine.put("productCode", finishedGood.getProductCode());
        orderLine.put("description", "O2C credit note item");
        orderLine.put("quantity", new BigDecimal("1"));
        orderLine.put("unitPrice", new BigDecimal("150.00"));
        orderLine.put("gstRate", BigDecimal.ZERO);

        Map<String, Object> orderReq = new HashMap<>();
        orderReq.put("dealerId", o2c.dealer().getId());
        orderReq.put("totalAmount", new BigDecimal("150.00"));
        orderReq.put("currency", "INR");
        orderReq.put("gstTreatment", "NONE");
        orderReq.put("items", List.of(orderLine));
        orderReq.put("idempotencyKey", "O2C-CN-ORDER-001");

        ResponseEntity<Map> orderResp = rest.exchange("/api/v1/sales/orders",
                HttpMethod.POST, new HttpEntity<>(orderReq, headers), Map.class);
        Map<?, ?> orderData = requireData(orderResp, "create order for credit note");
        Long orderId = ((Number) orderData.get("id")).longValue();

        rest.exchange("/api/v1/sales/orders/" + orderId + "/confirm",
                HttpMethod.POST, new HttpEntity<>(headers), Map.class);

        Map<String, Object> dispatchReq = new HashMap<>();
        dispatchReq.put("orderId", orderId);
        dispatchReq.put("confirmedBy", "o2c-test");

        ResponseEntity<Map> dispatchResp = rest.exchange("/api/v1/sales/dispatch/confirm",
                HttpMethod.POST, new HttpEntity<>(dispatchReq, headers), Map.class);
        Map<?, ?> dispatchData = requireData(dispatchResp, "dispatch confirm for credit note");
        Long invoiceId = ((Number) dispatchData.get("finalInvoiceId")).longValue();

        Invoice invoice = invoiceRepository.findById(invoiceId)
                .orElseThrow(() -> new AssertionError("Invoice missing for credit note flow"));
        if (invoice.getJournalEntry() == null) {
            throw new AssertionError("Invoice journal missing for credit note flow");
        }

        String reference = "CN-O2C-" + invoice.getInvoiceNumber();
        Map<String, Object> creditReq = new HashMap<>();
        creditReq.put("invoiceId", invoiceId);
        creditReq.put("referenceNumber", reference);
        creditReq.put("memo", "O2C credit note test");

        ResponseEntity<Map> creditResp = rest.exchange("/api/v1/accounting/credit-notes",
                HttpMethod.POST, new HttpEntity<>(creditReq, headers), Map.class);
        requireData(creditResp, "credit note");

        JournalEntry creditNote = journalEntryRepository.findByCompanyAndReferenceNumber(company, reference)
                .orElseThrow(() -> new AssertionError("Credit note journal missing"));
        assertThat(creditNote.getReversalOf())
                .as("credit note links to invoice journal")
                .isNotNull();
        assertThat(creditNote.getReversalOf().getId())
                .isEqualTo(invoice.getJournalEntry().getId());

        invariants.assertJournalBalanced(creditNote.getId());

        Invoice refreshed = invoiceRepository.findById(invoiceId)
                .orElseThrow(() -> new AssertionError("Invoice missing after credit note"));
        assertThat(refreshed.getOutstandingAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(refreshed.getStatus()).isEqualTo("VOID");
    }

    @Test
    @DisplayName("Procure-to-Pay: purchase -> intake -> settlement")
    void procureToPay_goldenPath() {
        Company company = p2p.company();
        HttpHeaders headers = authHeaders("p2p@test.com", company.getCode());
        LocalDate entryDate = TestDateUtils.safeDate(company);

        RawMaterial material = createRawMaterial(company, "RM-P2P-001", "P2P Raw Material",
                p2p.requireAccount("INV").getId());

        Map<String, Object> line = new HashMap<>();
        line.put("rawMaterialId", material.getId());
        line.put("quantity", new BigDecimal("10"));
        line.put("costPerUnit", new BigDecimal("15.00"));
        PurchaseWorkflowIds workflow = createPurchaseOrderAndReceipt(headers, p2p.supplier().getId(),
                material.getId(), new BigDecimal("10"), new BigDecimal("15.00"), entryDate);

        Map<String, Object> purchaseReq = new HashMap<>();
        purchaseReq.put("supplierId", p2p.supplier().getId());
        purchaseReq.put("invoiceNumber", "P2P-INV-001");
        purchaseReq.put("invoiceDate", entryDate);
        purchaseReq.put("purchaseOrderId", workflow.purchaseOrderId());
        purchaseReq.put("goodsReceiptId", workflow.goodsReceiptId());
        purchaseReq.put("lines", List.of(line));

        ResponseEntity<Map> purchaseResp = rest.exchange("/api/v1/purchasing/raw-material-purchases",
                HttpMethod.POST, new HttpEntity<>(purchaseReq, headers), Map.class);
        Map<?, ?> purchaseData = requireData(purchaseResp, "create purchase");
        Long purchaseId = ((Number) purchaseData.get("id")).longValue();

        RawMaterialPurchase purchase = purchaseRepository.findById(purchaseId)
                .orElseThrow(() -> new AssertionError("Purchase missing: " + purchaseId));
        RawMaterial refreshedMaterial = rawMaterialRepository.findById(material.getId())
                .orElseThrow(() -> new AssertionError("Raw material missing: " + material.getId()));
        assertThat(refreshedMaterial.getCurrentStock()).isEqualByComparingTo(new BigDecimal("10"));

        List<RawMaterialBatch> batches = rawMaterialBatchRepository.findByRawMaterial(refreshedMaterial);
        assertThat(batches).hasSize(1);
        RawMaterialBatch batch = batches.get(0);
        assertThat(batch.getQuantity()).isEqualByComparingTo(new BigDecimal("10"));

        List<RawMaterialMovement> movements = rawMaterialMovementRepository.findByRawMaterialBatch(batch);
        assertThat(movements).hasSize(1);
        RawMaterialMovement movement = movements.get(0);
        assertThat(movement.getReferenceType()).isEqualTo(InventoryReference.RAW_MATERIAL_PURCHASE);
        assertThat(movement.getMovementType()).isEqualTo("RECEIPT");
        assertThat(movement.getQuantity()).isEqualByComparingTo(new BigDecimal("10"));
        assertThat(movement.getJournalEntryId()).isNotNull();
        invariants.assertJournalLinkedTo("PURCHASE", purchaseId);
        invariants.assertJournalBalanced(purchase.getJournalEntry().getId());

        JournalEntry purchaseEntry = journalEntryRepository.findById(purchase.getJournalEntry().getId())
                .orElseThrow(() -> new AssertionError("Purchase journal missing"));
        BigDecimal expectedTotal = new BigDecimal("150.00");
        Long inventoryAccountId = p2p.requireAccount("INV").getId();
        Long payableAccountId = p2p.requireAccount("AP").getId();
        BigDecimal inventoryDebit = purchaseEntry.getLines().stream()
                .filter(journalLine -> journalLine.getAccount().getId().equals(inventoryAccountId))
                .map(JournalLine::getDebit)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal payableCredit = purchaseEntry.getLines().stream()
                .filter(journalLine -> journalLine.getAccount().getId().equals(payableAccountId))
                .map(JournalLine::getCredit)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(inventoryDebit).isEqualByComparingTo(expectedTotal);
        assertThat(payableCredit).isEqualByComparingTo(expectedTotal);

        Map<String, Object> allocation = Map.of(
                "purchaseId", purchaseId,
                "appliedAmount", purchase.getTotalAmount()
        );
        Map<String, Object> settlementReq = new HashMap<>();
        settlementReq.put("supplierId", p2p.supplier().getId());
        settlementReq.put("cashAccountId", p2p.requireAccount("CASH").getId());
        settlementReq.put("settlementDate", entryDate);
        settlementReq.put("referenceNumber", "P2P-SETTLE-001");
        settlementReq.put("idempotencyKey", "P2P-SETTLE-001");
        settlementReq.put("allocations", List.of(allocation));

        ResponseEntity<Map> settleResp = rest.exchange("/api/v1/accounting/settlements/suppliers",
                HttpMethod.POST, new HttpEntity<>(settlementReq, headers), Map.class);
        Map<?, ?> settleData = requireData(settleResp, "supplier settlement");
        Map<?, ?> journalData = (Map<?, ?>) settleData.get("journalEntry");
        Long settlementJournalId = journalData != null
                ? ((Number) journalData.get("id")).longValue()
                : null;

        ResponseEntity<Map> settleRepeatResp = rest.exchange("/api/v1/accounting/settlements/suppliers",
                HttpMethod.POST, new HttpEntity<>(settlementReq, headers), Map.class);
        Map<?, ?> settleRepeatData = requireData(settleRepeatResp, "supplier settlement idempotent");
        Map<?, ?> repeatJournalData = (Map<?, ?>) settleRepeatData.get("journalEntry");
        Long repeatJournalId = repeatJournalData != null
                ? ((Number) repeatJournalData.get("id")).longValue()
                : null;
        assertThat(repeatJournalId).isEqualTo(settlementJournalId);

        List<PartnerSettlementAllocation> allocations =
                settlementAllocationRepository.findByCompanyAndIdempotencyKey(company, "P2P-SETTLE-001");
        assertThat(allocations)
                .as("supplier settlement idempotency key should create one allocation set")
                .hasSize(1);

        invariants.assertSubledgerReconciles(p2p.requireAccount("AP").getId(), entryDate);
        invariants.assertNoNegativeStock(company.getId(), material.getSku());
    }

    @Test
    @DisplayName("Procure-to-Pay: purchase return reduces stock and records movement")
    void procureToPay_purchaseReturn_reducesStock() {
        CanonicalErpDataset returnDataset = datasetBuilder.seedCompany("ERP-P2P-RET");
        dataSeeder.ensureUser("p2p-ret@test.com", PASSWORD, "P2P Return Admin",
                returnDataset.company().getCode(), BASE_ROLES);

        Company company = returnDataset.company();
        HttpHeaders headers = authHeaders("p2p-ret@test.com", company.getCode());
        LocalDate entryDate = TestDateUtils.safeDate(company);

        RawMaterial material = createRawMaterial(company, "RM-P2P-RET-001", "P2P Return Material",
                returnDataset.requireAccount("INV").getId());

        Map<String, Object> line = new HashMap<>();
        line.put("rawMaterialId", material.getId());
        line.put("quantity", new BigDecimal("10"));
        line.put("costPerUnit", new BigDecimal("15.00"));
        PurchaseWorkflowIds workflow = createPurchaseOrderAndReceipt(headers, returnDataset.supplier().getId(),
                material.getId(), new BigDecimal("10"), new BigDecimal("15.00"), entryDate);

        String invoiceNumber = "P2P-RET-INV-" + System.nanoTime();
        Map<String, Object> purchaseReq = new HashMap<>();
        purchaseReq.put("supplierId", returnDataset.supplier().getId());
        purchaseReq.put("invoiceNumber", invoiceNumber);
        purchaseReq.put("invoiceDate", entryDate);
        purchaseReq.put("purchaseOrderId", workflow.purchaseOrderId());
        purchaseReq.put("goodsReceiptId", workflow.goodsReceiptId());
        purchaseReq.put("lines", List.of(line));

        ResponseEntity<Map> purchaseResp = rest.exchange("/api/v1/purchasing/raw-material-purchases",
                HttpMethod.POST, new HttpEntity<>(purchaseReq, headers), Map.class);
        Map<?, ?> purchaseData = requireData(purchaseResp, "create purchase for return");
        Long purchaseId = ((Number) purchaseData.get("id")).longValue();

        RawMaterial afterPurchase = rawMaterialRepository.findById(material.getId())
                .orElseThrow(() -> new AssertionError("Raw material missing after purchase"));
        assertThat(afterPurchase.getCurrentStock()).isEqualByComparingTo(new BigDecimal("10"));

        String returnRef = "P2P-RET-" + System.nanoTime();
        Map<String, Object> returnReq = new HashMap<>();
        returnReq.put("supplierId", returnDataset.supplier().getId());
        returnReq.put("purchaseId", purchaseId);
        returnReq.put("rawMaterialId", material.getId());
        returnReq.put("quantity", new BigDecimal("4"));
        returnReq.put("unitCost", new BigDecimal("15.00"));
        returnReq.put("referenceNumber", returnRef);
        returnReq.put("returnDate", entryDate);
        returnReq.put("reason", "Return test");

        ResponseEntity<Map> returnResp = rest.exchange("/api/v1/purchasing/raw-material-purchases/returns",
                HttpMethod.POST, new HttpEntity<>(returnReq, headers), Map.class);
        Map<?, ?> returnData = requireData(returnResp, "purchase return");
        Long returnEntryId = ((Number) returnData.get("id")).longValue();

        RawMaterial afterReturn = rawMaterialRepository.findById(material.getId())
                .orElseThrow(() -> new AssertionError("Raw material missing after return"));
        assertThat(afterReturn.getCurrentStock()).isEqualByComparingTo(new BigDecimal("6"));

        List<RawMaterialMovement> returnMovements = rawMaterialMovementRepository
                .findByReferenceTypeAndReferenceId(InventoryReference.PURCHASE_RETURN, returnRef);
        assertThat(returnMovements).hasSize(1);
        RawMaterialMovement movement = returnMovements.get(0);
        assertThat(movement.getMovementType()).isEqualTo("RETURN");
        assertThat(movement.getQuantity()).isEqualByComparingTo(new BigDecimal("4"));
        assertThat(movement.getJournalEntryId()).isNotNull();

        JournalEntry returnEntry = journalEntryRepository.findById(returnEntryId)
                .orElseThrow(() -> new AssertionError("Return journal missing"));
        BigDecimal expectedTotal = new BigDecimal("60.00");
        Long inventoryAccountId = returnDataset.requireAccount("INV").getId();
        Long payableAccountId = returnDataset.requireAccount("AP").getId();
        BigDecimal payableDebit = returnEntry.getLines().stream()
                .filter(journalLine -> journalLine.getAccount().getId().equals(payableAccountId))
                .map(JournalLine::getDebit)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal inventoryCredit = returnEntry.getLines().stream()
                .filter(journalLine -> journalLine.getAccount().getId().equals(inventoryAccountId))
                .map(JournalLine::getCredit)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(payableDebit).isEqualByComparingTo(expectedTotal);
        assertThat(inventoryCredit).isEqualByComparingTo(expectedTotal);
    }

    @Test
    @DisplayName("Produce-to-Stock: production log -> packing -> finished goods")
    void produceToStock_goldenPath() {
        Company company = prod.company();
        HttpHeaders headers = authHeaders("prod@test.com", company.getCode());
        LocalDate entryDate = TestDateUtils.safeDate(company);

        RawMaterial rm1 = createRawMaterialWithBatch(company, "RM-PROD-01", "Production Base",
                new BigDecimal("100"), prod.requireAccount("INV").getId(), new BigDecimal("5.00"));
        RawMaterial rm2 = createRawMaterialWithBatch(company, "RM-PROD-02", "Production Additive",
                new BigDecimal("50"), prod.requireAccount("INV").getId(), new BigDecimal("3.00"));

        ProductionBrand brand = ensureBrand(company, "PROD-BRAND", "Production Brand");
        ProductionProduct product = ensureProduct(company, brand, "PROD-FG-001", "Production Finish", prod);
        ensurePackagingMapping(company, "10L");

        Map<String, Object> material1 = Map.of("rawMaterialId", rm1.getId(), "quantity", new BigDecimal("10"));
        Map<String, Object> material2 = Map.of("rawMaterialId", rm2.getId(), "quantity", new BigDecimal("5"));
        Map<String, Object> logRequest = new HashMap<>();
        logRequest.put("brandId", brand.getId());
        logRequest.put("productId", product.getId());
        logRequest.put("batchSize", new BigDecimal("100"));
        logRequest.put("mixedQuantity", new BigDecimal("100"));
        logRequest.put("producedAt", entryDate.toString());
        logRequest.put("createdBy", "prod-test");
        logRequest.put("materials", List.of(material1, material2));

        ResponseEntity<Map> logResp = rest.exchange("/api/v1/factory/production/logs",
                HttpMethod.POST, new HttpEntity<>(logRequest, headers), Map.class);
        Map<?, ?> logData = requireData(logResp, "create production log");
        Long logId = ((Number) logData.get("id")).longValue();
        String productionCode = (String) logData.get("productionCode");

        Map<String, Object> packingLine = new HashMap<>();
        packingLine.put("packagingSize", "10L");
        packingLine.put("quantityLiters", new BigDecimal("80"));
        packingLine.put("piecesCount", 80);
        packingLine.put("boxesCount", 8);
        packingLine.put("piecesPerBox", 10);

        Map<String, Object> packingRequest = new HashMap<>();
        packingRequest.put("productionLogId", logId);
        packingRequest.put("packedDate", entryDate);
        packingRequest.put("packedBy", "packer");
        packingRequest.put("idempotencyKey", "INV-PACK-" + logId + "-" + System.nanoTime());
        packingRequest.put("lines", List.of(packingLine));

        ResponseEntity<Map> packingResp = rest.exchange("/api/v1/factory/packing-records",
                HttpMethod.POST, new HttpEntity<>(packingRequest, headers), Map.class);
        requireData(packingResp, "pack production");

        FinishedGood finishedGood = finishedGoodRepository.findByCompanyAndProductCode(company, product.getSkuCode())
                .orElseThrow(() -> new AssertionError("Finished good missing after packing"));
        assertThat(finishedGood.getCurrentStock()).isGreaterThan(BigDecimal.ZERO);

        List<InventoryMovement> movements = inventoryMovementRepository
                .findByReferenceTypeAndReferenceIdOrderByCreatedAtAsc(InventoryReference.PRODUCTION_LOG, productionCode);
        assertThat(movements).as("production movements created").isNotEmpty();
        invariants.assertNoNegativeStock(company.getId(), finishedGood.getProductCode());
        assertThat(productionLogRepository.findById(logId)).isPresent();
    }

    @Test
    @DisplayName("Hire-to-Pay: employee -> attendance -> payroll run -> post -> pay")
    void hireToPay_goldenPath() {
        Company company = payroll.company();
        HttpHeaders headers = authHeaders("pay@test.com", company.getCode());
        LocalDate entryDate = TestDateUtils.safeDate(company);

        datasetBuilder.ensurePayrollAccount(company, "SALARY-EXP", "Salary Expense",
                AccountType.EXPENSE);
        datasetBuilder.ensurePayrollAccount(company, "WAGE-EXP", "Wage Expense",
                AccountType.EXPENSE);
        datasetBuilder.ensurePayrollAccount(company, "SALARY-PAYABLE", "Salary Payable",
                AccountType.LIABILITY);
        datasetBuilder.ensurePayrollAccount(company, "EMP-ADV", "Employee Advances",
                AccountType.ASSET);

        Map<String, Object> employeeReq = new HashMap<>();
        employeeReq.put("firstName", "Pat");
        employeeReq.put("lastName", "Worker");
        employeeReq.put("email", "pat.worker@erp.test");
        employeeReq.put("phone", "555-0101");
        employeeReq.put("role", "Painter");
        employeeReq.put("hiredDate", entryDate.minusDays(10));
        employeeReq.put("employeeType", "STAFF");
        employeeReq.put("paymentSchedule", "MONTHLY");
        BigDecimal monthlySalary = new BigDecimal("26000.00");
        employeeReq.put("monthlySalary", monthlySalary);
        employeeReq.put("workingDaysPerMonth", 26);
        employeeReq.put("weeklyOffDays", 1);
        employeeReq.put("standardHoursPerDay", new BigDecimal("8"));
        employeeReq.put("overtimeRateMultiplier", new BigDecimal("1.5"));
        employeeReq.put("doubleOtRateMultiplier", new BigDecimal("2.0"));

        ResponseEntity<Map> empResp = rest.exchange("/api/v1/hr/employees",
                HttpMethod.POST, new HttpEntity<>(employeeReq, headers), Map.class);
        Map<?, ?> empData = requireData(empResp, "create employee");
        Long employeeId = ((Number) empData.get("id")).longValue();
        Employee employee = employeeRepository.findByCompanyAndId(company, employeeId)
                .orElseThrow(() -> new AssertionError("Employee missing after create"));
        employee.setAdvanceBalance(new BigDecimal("500.00"));
        employeeRepository.save(employee);

        Map<String, Object> attendanceReq = new HashMap<>();
        attendanceReq.put("date", entryDate);
        attendanceReq.put("status", "PRESENT");
        attendanceReq.put("regularHours", new BigDecimal("8"));
        attendanceReq.put("overtimeHours", BigDecimal.ZERO);
        attendanceReq.put("doubleOvertimeHours", BigDecimal.ZERO);
        attendanceReq.put("holiday", false);
        attendanceReq.put("weekend", false);
        attendanceReq.put("remarks", "golden path attendance");

        rest.exchange("/api/v1/hr/attendance/mark/" + employeeId,
                HttpMethod.POST, new HttpEntity<>(attendanceReq, headers), Map.class);

        LocalDate periodStart = entryDate.withDayOfMonth(1);
        LocalDate periodEnd = entryDate.withDayOfMonth(entryDate.lengthOfMonth());
        Map<String, Object> runRequest = new HashMap<>();
        runRequest.put("runType", "MONTHLY");
        runRequest.put("periodStart", periodStart);
        runRequest.put("periodEnd", periodEnd);
        runRequest.put("remarks", "Payroll run");

        ResponseEntity<Map> runResp = rest.exchange("/api/v1/payroll/runs",
                HttpMethod.POST, new HttpEntity<>(runRequest, headers), Map.class);
        Map<?, ?> runData = requireData(runResp, "create payroll run");
        Long runId = ((Number) runData.get("id")).longValue();

        rest.exchange("/api/v1/payroll/runs/" + runId + "/calculate",
                HttpMethod.POST, new HttpEntity<>(headers), Map.class);
        rest.exchange("/api/v1/payroll/runs/" + runId + "/approve",
                HttpMethod.POST, new HttpEntity<>(headers), Map.class);
        rest.exchange("/api/v1/payroll/runs/" + runId + "/post",
                HttpMethod.POST, new HttpEntity<>(headers), Map.class);

        PayrollRun run = payrollRunRepository.findById(runId)
                .orElseThrow(() -> new AssertionError("Payroll run missing: " + runId));
        BigDecimal dailyRate = monthlySalary.divide(new BigDecimal("26"), 2, RoundingMode.HALF_UP);
        BigDecimal expectedGross = dailyRate;
        BigDecimal expectedAdvance = expectedGross.multiply(new BigDecimal("0.20")).setScale(2, RoundingMode.HALF_UP);
        BigDecimal expectedNet = expectedGross.subtract(expectedAdvance);

        List<PayrollRunLine> lines = payrollRunLineRepository.findByPayrollRun(run);
        assertThat(lines).isNotEmpty();
        PayrollRunLine line = lines.stream()
                .filter(candidate -> candidate.getEmployee().getId().equals(employeeId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Payroll line missing for employee " + employeeId));
        assertThat(line.getBasePay()).isEqualByComparingTo(expectedGross);
        assertThat(line.getGrossPay()).isEqualByComparingTo(expectedGross);
        assertThat(line.getAdvanceDeduction()).isEqualByComparingTo(expectedAdvance);
        assertThat(line.getTotalDeductions()).isEqualByComparingTo(expectedAdvance);
        assertThat(line.getNetPay()).isEqualByComparingTo(expectedNet);

        BigDecimal totalBasePay = lines.stream()
                .map(PayrollRunLine::getBasePay)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalOvertimePay = lines.stream()
                .map(PayrollRunLine::getOvertimePay)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalGrossPay = lines.stream()
                .map(PayrollRunLine::getGrossPay)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalAdvances = lines.stream()
                .map(PayrollRunLine::getAdvanceDeduction)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalDeductions = lines.stream()
                .map(PayrollRunLine::getTotalDeductions)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalNetPay = lines.stream()
                .map(PayrollRunLine::getNetPay)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        assertThat(run.getTotalEmployees()).isEqualTo(lines.size());
        assertThat(run.getTotalBasePay()).isEqualByComparingTo(totalBasePay);
        assertThat(run.getTotalOvertimePay()).isEqualByComparingTo(totalOvertimePay);
        assertThat(run.getTotalDeductions()).isEqualByComparingTo(totalDeductions);
        assertThat(run.getTotalNetPay()).isEqualByComparingTo(totalNetPay);

        invariants.assertJournalLinkedTo("PAYROLL_RUN", runId);
        if (run.getJournalEntryId() != null) {
            invariants.assertJournalBalanced(run.getJournalEntryId());
            JournalEntry journal = journalEntryRepository.findById(run.getJournalEntryId())
                    .orElseThrow(() -> new AssertionError("Payroll journal missing: " + run.getJournalEntryId()));
            Account salaryExpense = accountRepository.findByCompanyAndCodeIgnoreCase(company, "SALARY-EXP")
                    .orElseThrow(() -> new AssertionError("Salary expense account missing"));
            Account salaryPayable = accountRepository.findByCompanyAndCodeIgnoreCase(company, "SALARY-PAYABLE")
                    .orElseThrow(() -> new AssertionError("Salary payable account missing"));
            Account advanceAccount = accountRepository.findByCompanyAndCodeIgnoreCase(company, "EMP-ADV")
                    .orElseThrow(() -> new AssertionError("Employee advance account missing"));

            BigDecimal expenseDebit = journal.getLines().stream()
                    .filter(journalLine -> journalLine.getAccount().getId().equals(salaryExpense.getId()))
                    .map(journalLine -> journalLine.getDebit() == null ? BigDecimal.ZERO : journalLine.getDebit())
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal expenseCredit = journal.getLines().stream()
                    .filter(journalLine -> journalLine.getAccount().getId().equals(salaryExpense.getId()))
                    .map(journalLine -> journalLine.getCredit() == null ? BigDecimal.ZERO : journalLine.getCredit())
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal payableCredit = journal.getLines().stream()
                    .filter(journalLine -> journalLine.getAccount().getId().equals(salaryPayable.getId()))
                    .map(journalLine -> journalLine.getCredit() == null ? BigDecimal.ZERO : journalLine.getCredit())
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal payableDebit = journal.getLines().stream()
                    .filter(journalLine -> journalLine.getAccount().getId().equals(salaryPayable.getId()))
                    .map(journalLine -> journalLine.getDebit() == null ? BigDecimal.ZERO : journalLine.getDebit())
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal advanceCredit = journal.getLines().stream()
                    .filter(journalLine -> journalLine.getAccount().getId().equals(advanceAccount.getId()))
                    .map(journalLine -> journalLine.getCredit() == null ? BigDecimal.ZERO : journalLine.getCredit())
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal advanceDebit = journal.getLines().stream()
                    .filter(journalLine -> journalLine.getAccount().getId().equals(advanceAccount.getId()))
                    .map(journalLine -> journalLine.getDebit() == null ? BigDecimal.ZERO : journalLine.getDebit())
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            assertThat(expenseDebit).isEqualByComparingTo(totalGrossPay);
            assertThat(expenseCredit).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(payableCredit).isEqualByComparingTo(totalNetPay);
            assertThat(payableDebit).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(advanceCredit).isEqualByComparingTo(totalAdvances);
            assertThat(advanceDebit).isEqualByComparingTo(BigDecimal.ZERO);
        }

        String paymentReference = "PAYROLL-PAY-" + runId;
        Account cashAccount = accountRepository.findByCompanyAndCodeIgnoreCase(company, "CASH")
                .orElseThrow(() -> new AssertionError("Cash account missing"));
        Account salaryExpenseAccount = accountRepository.findByCompanyAndCodeIgnoreCase(company, "SALARY-EXP")
                .orElseThrow(() -> new AssertionError("Salary expense account missing"));
        Map<String, Object> paymentReq = new HashMap<>();
        paymentReq.put("payrollRunId", runId);
        paymentReq.put("cashAccountId", cashAccount.getId());
        paymentReq.put("expenseAccountId", salaryExpenseAccount.getId());
        paymentReq.put("amount", totalNetPay);
        paymentReq.put("referenceNumber", paymentReference);
        rest.exchange("/api/v1/accounting/payroll/payments",
                HttpMethod.POST, new HttpEntity<>(paymentReq, headers), Map.class);

        Map<String, Object> markPaidReq = Map.of("paymentReference", paymentReference);
        rest.exchange("/api/v1/payroll/runs/" + runId + "/mark-paid",
                HttpMethod.POST, new HttpEntity<>(markPaidReq, headers), Map.class);

        PayrollRun paid = payrollRunRepository.findById(runId)
                .orElseThrow(() -> new AssertionError("Payroll run missing after pay: " + runId));
        assertThat(paid.getStatusString()).isEqualTo("PAID");
        Employee paidEmployee = employeeRepository.findByCompanyAndId(company, employeeId)
                .orElseThrow(() -> new AssertionError("Employee missing after pay"));
        assertThat(paidEmployee.getAdvanceBalance()).isEqualByComparingTo(new BigDecimal("300.00"));
    }

    @Test
    @DisplayName("Hire-to-Pay: payroll journal reversal creates balanced inverse")
    void hireToPay_reversal() {
        Company company = payroll.company();
        HttpHeaders headers = authHeaders("pay@test.com", company.getCode());
        // Use a weekly run to avoid payroll run number collisions in this suite.
        LocalDate entryDate = TestDateUtils.safeDate(company);

        datasetBuilder.ensurePayrollAccount(company, "SALARY-EXP", "Salary Expense",
                AccountType.EXPENSE);
        datasetBuilder.ensurePayrollAccount(company, "WAGE-EXP", "Wage Expense",
                AccountType.EXPENSE);
        datasetBuilder.ensurePayrollAccount(company, "SALARY-PAYABLE", "Salary Payable",
                AccountType.LIABILITY);

        Map<String, Object> employeeReq = new HashMap<>();
        employeeReq.put("firstName", "Reva");
        employeeReq.put("lastName", "Lance");
        employeeReq.put("email", "reva.lance@erp.test");
        employeeReq.put("phone", "555-0102");
        employeeReq.put("role", "Payroll");
        employeeReq.put("hiredDate", entryDate.minusDays(10));
        employeeReq.put("employeeType", "LABOUR");
        employeeReq.put("paymentSchedule", "WEEKLY");
        employeeReq.put("dailyWage", new BigDecimal("1000.00"));
        employeeReq.put("weeklyOffDays", 1);
        employeeReq.put("standardHoursPerDay", new BigDecimal("8"));
        employeeReq.put("overtimeRateMultiplier", new BigDecimal("1.5"));
        employeeReq.put("doubleOtRateMultiplier", new BigDecimal("2.0"));

        ResponseEntity<Map> empResp = rest.exchange("/api/v1/hr/employees",
                HttpMethod.POST, new HttpEntity<>(employeeReq, headers), Map.class);
        Map<?, ?> empData = requireData(empResp, "create employee");
        Long employeeId = ((Number) empData.get("id")).longValue();

        Map<String, Object> attendanceReq = new HashMap<>();
        attendanceReq.put("date", entryDate);
        attendanceReq.put("status", "PRESENT");
        attendanceReq.put("regularHours", new BigDecimal("8"));
        attendanceReq.put("overtimeHours", BigDecimal.ZERO);
        attendanceReq.put("doubleOvertimeHours", BigDecimal.ZERO);
        attendanceReq.put("holiday", false);
        attendanceReq.put("weekend", false);
        attendanceReq.put("remarks", "reversal attendance");

        rest.exchange("/api/v1/hr/attendance/mark/" + employeeId,
                HttpMethod.POST, new HttpEntity<>(attendanceReq, headers), Map.class);

        LocalDate periodStart = entryDate;
        LocalDate periodEnd = entryDate;
        Map<String, Object> runRequest = new HashMap<>();
        runRequest.put("runType", "WEEKLY");
        runRequest.put("periodStart", periodStart);
        runRequest.put("periodEnd", periodEnd);
        runRequest.put("remarks", "Weekly payroll run reversal");

        ResponseEntity<Map> runResp = rest.exchange("/api/v1/payroll/runs",
                HttpMethod.POST, new HttpEntity<>(runRequest, headers), Map.class);
        Map<?, ?> runData = requireData(runResp, "create payroll run");
        Long runId = ((Number) runData.get("id")).longValue();

        rest.exchange("/api/v1/payroll/runs/" + runId + "/calculate",
                HttpMethod.POST, new HttpEntity<>(headers), Map.class);
        rest.exchange("/api/v1/payroll/runs/" + runId + "/approve",
                HttpMethod.POST, new HttpEntity<>(headers), Map.class);
        rest.exchange("/api/v1/payroll/runs/" + runId + "/post",
                HttpMethod.POST, new HttpEntity<>(headers), Map.class);

        PayrollRun run = payrollRunRepository.findById(runId)
                .orElseThrow(() -> new AssertionError("Payroll run missing: " + runId));
        Long journalId = run.getJournalEntryId();
        assertThat(journalId).as("payroll journal id").isNotNull();

        Map<String, Object> reversalRequest = new HashMap<>();
        reversalRequest.put("reversalDate", entryDate);
        reversalRequest.put("reason", "PAYROLL_REVERSAL_TEST");
        reversalRequest.put("memo", "Payroll reversal test");

        ResponseEntity<Map> reversalResp = rest.exchange(
                "/api/v1/accounting/journal-entries/" + journalId + "/reverse",
                HttpMethod.POST,
                new HttpEntity<>(reversalRequest, headers),
                Map.class);
        requireData(reversalResp, "reverse payroll journal");

        invariants.assertReversalCreatesBalancedInverse(journalId);
    }

    private HttpHeaders authHeaders(String email, String companyCode) {
        Map<String, Object> login = Map.of(
                "email", email,
                "password", PASSWORD,
                "companyCode", companyCode
        );
        ResponseEntity<Map> response = rest.postForEntity("/api/v1/auth/login", login, Map.class);
        Map<?, ?> body = response.getBody();
        String token = body == null ? null : (String) body.get("accessToken");
        if (token == null) {
            throw new AssertionError("Login failed for " + email + ": " + body);
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Company-Id", companyCode);
        return headers;
    }

    private Map<?, ?> requireData(ResponseEntity<Map> response, String action) {
        if (!response.getStatusCode().is2xxSuccessful()) {
            throw new AssertionError(action + " failed: status=" + response.getStatusCode() + " body=" + response.getBody());
        }
        Map<?, ?> body = response.getBody();
        if (body == null || body.get("data") == null) {
            throw new AssertionError(action + " response missing data: " + body);
        }
        Object data = body.get("data");
        if (!(data instanceof Map<?, ?> map)) {
            throw new AssertionError(action + " response has unexpected payload: " + data);
        }
        return map;
    }

    private Map<String, Object> journalLine(Account account, BigDecimal debit, BigDecimal credit, String description) {
        Map<String, Object> line = new HashMap<>();
        line.put("accountId", account.getId());
        line.put("description", description);
        line.put("debit", debit);
        line.put("credit", credit);
        return line;
    }

    private RawMaterial createRawMaterial(Company company, String sku, String name, Long inventoryAccountId) {
        RawMaterial material = new RawMaterial();
        material.setCompany(company);
        material.setSku(sku);
        material.setName(name);
        material.setUnitType("KG");
        material.setCurrentStock(BigDecimal.ZERO);
        material.setInventoryAccountId(inventoryAccountId);
        return rawMaterialRepository.save(material);
    }

    private RawMaterial createRawMaterialWithBatch(Company company, String sku, String name,
                                                   BigDecimal stock, Long inventoryAccountId,
                                                   BigDecimal unitCost) {
        RawMaterial material = new RawMaterial();
        material.setCompany(company);
        material.setSku(sku);
        material.setName(name);
        material.setUnitType("KG");
        material.setCurrentStock(stock);
        material.setInventoryAccountId(inventoryAccountId);
        RawMaterial saved = rawMaterialRepository.save(material);

        RawMaterialBatch batch = new RawMaterialBatch();
        batch.setRawMaterial(saved);
        batch.setQuantity(stock);
        batch.setCostPerUnit(unitCost);
        batch.setBatchCode("BATCH-" + sku);
        batch.setUnit(saved.getUnitType());
        batch.setReceivedAt(Instant.now());
        rawMaterialBatchRepository.save(batch);
        return saved;
    }

    private void ensurePackagingMapping(Company company, String size) {
        List<PackagingSizeMapping> existing = packagingSizeMappingRepository
                .findActiveByCompanyAndPackagingSizeIgnoreCase(company, size);
        if (!existing.isEmpty()) {
            Long materialId = existing.get(0).getRawMaterial().getId();
            RawMaterial material = rawMaterialRepository.findById(materialId).orElseThrow();
            topUpPackagingMaterial(material, new BigDecimal("200"), new BigDecimal("2.00"));
            return;
        }
        String sku = "PACK-" + size;
        RawMaterial material = createPackagingMaterialWithBatch(company, sku, "Packaging " + size,
                new BigDecimal("200"), prod.requireAccount("INV").getId(), new BigDecimal("2.00"));

        PackagingSizeMapping mapping = new PackagingSizeMapping();
        mapping.setCompany(company);
        mapping.setPackagingSize(size);
        mapping.setRawMaterial(material);
        mapping.setUnitsPerPack(1);
        mapping.setLitersPerUnit(new BigDecimal(size.replace("L", "")));
        mapping.setActive(true);
        packagingSizeMappingRepository.save(mapping);
    }

    private RawMaterial createPackagingMaterialWithBatch(Company company, String sku, String name,
                                                         BigDecimal stock, Long inventoryAccountId,
                                                         BigDecimal unitCost) {
        RawMaterial material = new RawMaterial();
        material.setCompany(company);
        material.setSku(sku);
        material.setName(name);
        material.setUnitType("UNIT");
        material.setMaterialType(MaterialType.PACKAGING);
        material.setCurrentStock(stock);
        material.setInventoryAccountId(inventoryAccountId);
        RawMaterial saved = rawMaterialRepository.save(material);

        RawMaterialBatch batch = new RawMaterialBatch();
        batch.setRawMaterial(saved);
        batch.setQuantity(stock);
        batch.setCostPerUnit(unitCost);
        batch.setBatchCode("BATCH-" + sku);
        batch.setUnit(saved.getUnitType());
        batch.setReceivedAt(Instant.now());
        rawMaterialBatchRepository.save(batch);
        return saved;
    }

    private void topUpPackagingMaterial(RawMaterial material, BigDecimal quantity, BigDecimal unitCost) {
        BigDecimal current = material.getCurrentStock() != null ? material.getCurrentStock() : BigDecimal.ZERO;
        BigDecimal topUp = quantity != null ? quantity : BigDecimal.ZERO;
        material.setCurrentStock(current.add(topUp));
        RawMaterial saved = rawMaterialRepository.save(material);

        RawMaterialBatch batch = new RawMaterialBatch();
        batch.setRawMaterial(saved);
        batch.setQuantity(topUp);
        batch.setCostPerUnit(unitCost);
        batch.setBatchCode("BATCH-" + saved.getSku() + "-" + System.currentTimeMillis());
        batch.setUnit(saved.getUnitType());
        batch.setReceivedAt(Instant.now());
        rawMaterialBatchRepository.save(batch);
    }

    private ProductionBrand ensureBrand(Company company, String code, String name) {
        return brandRepository.findByCompanyAndCodeIgnoreCase(company, code)
                .orElseGet(() -> {
                    ProductionBrand brand = new ProductionBrand();
                    brand.setCompany(company);
                    brand.setCode(code);
                    brand.setName(name);
                    return brandRepository.save(brand);
                });
    }

    private PurchaseWorkflowIds createPurchaseOrderAndReceipt(HttpHeaders headers,
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
        poReq.put("orderNumber", "PO-" + System.nanoTime());
        poReq.put("orderDate", entryDate);
        poReq.put("lines", List.of(line));

        ResponseEntity<Map> poResp = rest.exchange(
                "/api/v1/purchasing/purchase-orders",
                HttpMethod.POST,
                new HttpEntity<>(poReq, headers),
                Map.class);
        Map<?, ?> poData = requireData(poResp, "create purchase order");
        Long purchaseOrderId = ((Number) poData.get("id")).longValue();

        Map<String, Object> grLine = new HashMap<>(line);
        grLine.put("batchCode", "GRN-" + System.nanoTime());

        Map<String, Object> grReq = new HashMap<>();
        grReq.put("purchaseOrderId", purchaseOrderId);
        grReq.put("receiptNumber", "GRN-" + System.nanoTime());
        grReq.put("receiptDate", entryDate);
        grReq.put("idempotencyKey", "GRN-IDEMP-" + System.nanoTime());
        grReq.put("lines", List.of(grLine));

        ResponseEntity<Map> grResp = rest.exchange(
                "/api/v1/purchasing/goods-receipts",
                HttpMethod.POST,
                new HttpEntity<>(grReq, headers),
                Map.class);
        Map<?, ?> grData = requireData(grResp, "create goods receipt");
        Long goodsReceiptId = ((Number) grData.get("id")).longValue();

        return new PurchaseWorkflowIds(purchaseOrderId, goodsReceiptId);
    }

    private record PurchaseWorkflowIds(Long purchaseOrderId, Long goodsReceiptId) {}

    private ProductionProduct ensureProduct(Company company, ProductionBrand brand,
                                            String sku, String name, CanonicalErpDataset dataset) {
        return productRepository.findByCompanyAndSkuCode(company, sku)
                .orElseGet(() -> {
                    ProductionProduct product = new ProductionProduct();
                    product.setCompany(company);
                    product.setBrand(brand);
                    product.setSkuCode(sku);
                    product.setProductName(name);
                    product.setCategory("FINISHED_GOOD");
                    product.setUnitOfMeasure("UNIT");
                    product.setBasePrice(new BigDecimal("100.00"));
                    product.setGstRate(new BigDecimal("18.00"));
                    Map<String, Object> metadata = new HashMap<>();
                    metadata.put("wipAccountId", dataset.requireAccount("WIP").getId());
                    metadata.put("wastageAccountId", dataset.requireAccount("COGS").getId());
                    metadata.put("semiFinishedAccountId", dataset.requireAccount("INV").getId());
                    metadata.put("fgValuationAccountId", dataset.requireAccount("INV").getId());
                    metadata.put("fgCogsAccountId", dataset.requireAccount("COGS").getId());
                    metadata.put("fgRevenueAccountId", dataset.requireAccount("REV").getId());
                    metadata.put("fgDiscountAccountId", dataset.requireAccount("DISC").getId());
                    metadata.put("fgTaxAccountId", dataset.requireAccount("GST_OUT").getId());
                    product.setMetadata(metadata);
                    return productRepository.save(product);
                });
    }
}
