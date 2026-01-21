package com.bigbrightpaints.erp.e2e.accounting;

import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountType;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryReference;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterial;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialBatch;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialBatchRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialMovement;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialMovementRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialRepository;
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
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
    @Autowired private RawMaterialPurchaseRepository purchaseRepository;

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
        if (company.getGstInputTaxAccountId() == null || company.getGstOutputTaxAccountId() == null) {
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

        Map<String, Object> line = new HashMap<>();
        line.put("rawMaterialId", rawMaterialId);
        line.put("quantity", quantity);
        line.put("costPerUnit", costPerUnit);

        Map<String, Object> purchaseReq = new HashMap<>();
        purchaseReq.put("supplierId", supplierId);
        purchaseReq.put("invoiceNumber", "INV-" + shortSuffix());
        purchaseReq.put("invoiceDate", entryDate);
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
        assertThat(movement.getReferenceType()).isEqualTo(InventoryReference.RAW_MATERIAL_PURCHASE);
        assertThat(movement.getMovementType()).isEqualTo("RECEIPT");
        assertThat(movement.getQuantity()).isEqualByComparingTo(quantity);
        assertThat(movement.getJournalEntryId()).isNotNull();

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

        RawMaterialPurchase purchase = purchaseRepository.findById(purchaseId).orElseThrow();
        assertThat(purchase.getOutstandingAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(purchase.getStatus()).isEqualTo("PAID");
    }

    @Test
    @DisplayName("Debit note clears purchase outstanding and sets status to PAID")
    void purchaseDebitNoteClearsOutstanding() {
        LocalDate entryDate = TestDateUtils.safeDate(company);
        Long supplierId = createSupplier("P2P Debit Supplier", "DN-" + shortSuffix());
        Long rawMaterialId = createRawMaterial("Debit Note Material", "RM-DN-" + shortSuffix(), inventory.getId());

        BigDecimal quantity = new BigDecimal("5");
        BigDecimal costPerUnit = new BigDecimal("20.00");

        Map<String, Object> line = new HashMap<>();
        line.put("rawMaterialId", rawMaterialId);
        line.put("quantity", quantity);
        line.put("costPerUnit", costPerUnit);

        String invoiceNumber = "INV-DN-" + shortSuffix();
        Map<String, Object> purchaseReq = new HashMap<>();
        purchaseReq.put("supplierId", supplierId);
        purchaseReq.put("invoiceNumber", invoiceNumber);
        purchaseReq.put("invoiceDate", entryDate);
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
        assertThat(purchase.getStatus()).isEqualTo("PAID");
    }

    @Test
    @DisplayName("Purchase return reduces stock and records movement")
    void purchaseReturnReducesStock() {
        LocalDate entryDate = TestDateUtils.safeDate(company);
        Long supplierId = createSupplier("P2P Return Supplier", "RET-" + shortSuffix());
        Long rawMaterialId = createRawMaterial("Return Raw Material", "RM-RET-" + shortSuffix(), inventory.getId());

        BigDecimal purchaseQty = new BigDecimal("10");
        BigDecimal unitCost = new BigDecimal("8.00");

        Map<String, Object> line = new HashMap<>();
        line.put("rawMaterialId", rawMaterialId);
        line.put("quantity", purchaseQty);
        line.put("costPerUnit", unitCost);

        Map<String, Object> purchaseReq = new HashMap<>();
        purchaseReq.put("supplierId", supplierId);
        purchaseReq.put("invoiceNumber", "INV-" + shortSuffix());
        purchaseReq.put("invoiceDate", entryDate);
        purchaseReq.put("lines", List.of(line));

        ResponseEntity<Map> purchaseResp = rest.exchange(
                "/api/v1/purchasing/raw-material-purchases",
                HttpMethod.POST,
                new HttpEntity<>(purchaseReq, headers),
                Map.class);
        assertThat(purchaseResp.getStatusCode()).isEqualTo(HttpStatus.OK);

        RawMaterial afterPurchase = rawMaterialRepository.findById(rawMaterialId).orElseThrow();
        assertThat(afterPurchase.getCurrentStock()).isEqualByComparingTo(purchaseQty);

        String returnRef = "RET-" + shortSuffix();
        Map<String, Object> returnReq = new HashMap<>();
        returnReq.put("supplierId", supplierId);
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

        Map<String, Object> line = new HashMap<>();
        line.put("rawMaterialId", rawMaterialId);
        line.put("quantity", quantity);
        line.put("costPerUnit", costPerUnit);

        Map<String, Object> purchaseReq = new HashMap<>();
        purchaseReq.put("supplierId", supplierId);
        purchaseReq.put("invoiceNumber", "INV-GST-" + shortSuffix());
        purchaseReq.put("invoiceDate", entryDate);
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

    private Long createSupplier(String name, String code) {
        Map<String, Object> req = Map.of(
                "name", name,
                "code", code,
                "contactEmail", "p2p-" + shortSuffix() + "@bbp.com",
                "creditLimit", new BigDecimal("25000.00")
        );
        ResponseEntity<Map> resp = rest.exchange(
                "/api/v1/suppliers",
                HttpMethod.POST,
                new HttpEntity<>(req, headers),
                Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> data = (Map<String, Object>) resp.getBody().get("data");
        return ((Number) data.get("id")).longValue();
    }

    private Long createRawMaterial(String name, String sku, Long inventoryAccountId) {
        Map<String, Object> req = Map.of(
                "name", name,
                "sku", sku,
                "unitType", "KG",
                "reorderLevel", new BigDecimal("1"),
                "minStock", new BigDecimal("1"),
                "maxStock", new BigDecimal("100"),
                "inventoryAccountId", inventoryAccountId
        );
        ResponseEntity<Map> resp = rest.exchange(
                "/api/v1/accounting/raw-materials",
                HttpMethod.POST,
                new HttpEntity<>(req, headers),
                Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> data = (Map<String, Object>) resp.getBody().get("data");
        return ((Number) data.get("id")).longValue();
    }

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
        h.set("X-Company-Id", COMPANY_CODE);
        return h;
    }

    private String shortSuffix() {
        String token = Long.toString(System.nanoTime());
        return token.length() > 8 ? token.substring(token.length() - 8) : token;
    }
}
