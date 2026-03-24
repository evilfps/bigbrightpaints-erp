package com.bigbrightpaints.erp.e2e.accounting;

import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountType;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.MaterialType;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterial;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialRepository;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("E2E: Supplier Statements and Aging")
class SupplierStatementAgingIT extends AbstractIntegrationTest {

    private static final String COMPANY_CODE = "SUP-STMT";
    private static final String ADMIN_EMAIL = "sup-stmt-admin@bbp.com";
    private static final String ADMIN_PASSWORD = "supstmt123";

    @Autowired private TestRestTemplate rest;
    @Autowired private CompanyRepository companyRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private RawMaterialRepository rawMaterialRepository;

    private HttpHeaders headers;
    private Company company;
    private Account inventory;
    private Account cash;

    @BeforeEach
    void setup() {
        dataSeeder.ensureUser(ADMIN_EMAIL, ADMIN_PASSWORD, "Supplier Statement Admin", COMPANY_CODE,
                List.of("ROLE_ADMIN", "ROLE_ACCOUNTING", "ROLE_FACTORY"));
        company = companyRepository.findByCodeIgnoreCase(COMPANY_CODE).orElseThrow();
        if (company.getStateCode() == null) {
            company.setStateCode("27");
            company = companyRepository.save(company);
        }
        headers = authHeaders();
        inventory = ensureAccount("INV-SUP-STMT", "Supplier Inventory", AccountType.ASSET);
        cash = ensureAccount("CASH-SUP-STMT", "Supplier Cash", AccountType.ASSET);
    }

    @Test
    @DisplayName("Supplier statement and aging reconcile to purchase/settlement activity")
    void supplierStatementAndAgingReconcile() {
        LocalDate entryDate = TestDateUtils.safeDate(company);

        String supplierCode = "SUPS-" + shortSuffix();
        Map<String, Object> supplier = createSupplier("Statement Supplier", supplierCode);
        Long supplierId = ((Number) supplier.get("id")).longValue();
        assertThat(supplier.get("payableAccountId")).isNotNull();

        String materialSku = "RM-" + shortSuffix();
        Long rawMaterialId = createRawMaterial("Statement Raw Material", materialSku, inventory.getId());

        BigDecimal quantity = new BigDecimal("10");
        BigDecimal costPerUnit = new BigDecimal("15.00");
        BigDecimal purchaseTotal = quantity.multiply(costPerUnit);
        PurchaseWorkflowIds workflow = createPurchaseOrderAndReceipt(supplierId, rawMaterialId, quantity, costPerUnit, entryDate);

        Map<String, Object> line = new HashMap<>();
        line.put("rawMaterialId", rawMaterialId);
        line.put("quantity", quantity);
        line.put("costPerUnit", costPerUnit);

        String invoiceNumber = "INV-" + shortSuffix();
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

        BigDecimal paymentAmount = new BigDecimal("60.00");
        String settlementRef = "SET-" + shortSuffix();
        Map<String, Object> allocation = Map.of(
                "purchaseId", purchaseId,
                "appliedAmount", paymentAmount
        );
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

        LocalDate from = entryDate.withDayOfMonth(1);
        LocalDate to = entryDate.withDayOfMonth(entryDate.lengthOfMonth());
        ResponseEntity<Map> stmtResp = rest.exchange(
                "/api/v1/accounting/statements/suppliers/" + supplierId + "?from=" + from + "&to=" + to,
                HttpMethod.GET,
                new HttpEntity<>(headers),
                Map.class);
        assertThat(stmtResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> stmt = (Map<String, Object>) stmtResp.getBody().get("data");
        BigDecimal openingBalance = new BigDecimal(stmt.get("openingBalance").toString());
        BigDecimal closingBalance = new BigDecimal(stmt.get("closingBalance").toString());
        BigDecimal expectedOutstanding = purchaseTotal.subtract(paymentAmount);
        assertThat(openingBalance).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(closingBalance).isEqualByComparingTo(expectedOutstanding);

        List<Map<String, Object>> transactions = (List<Map<String, Object>>) stmt.get("transactions");
        BigDecimal txnTotal = BigDecimal.ZERO;
        for (Map<String, Object> txn : transactions) {
            txnTotal = txnTotal.add(safeAmount(txn.get("credit")).subtract(safeAmount(txn.get("debit"))));
        }
        assertThat(txnTotal).isEqualByComparingTo(closingBalance);
        assertThat(transactions.stream()
                .anyMatch(txn -> safeAmount(txn.get("credit")).compareTo(purchaseTotal) == 0))
                .isTrue();
        assertThat(transactions.stream()
                .anyMatch(txn -> safeAmount(txn.get("debit")).compareTo(paymentAmount) == 0))
                .isTrue();

        ResponseEntity<Map> agingResp = rest.exchange(
                "/api/v1/accounting/aging/suppliers/" + supplierId + "?asOf=" + entryDate,
                HttpMethod.GET,
                new HttpEntity<>(headers),
                Map.class);
        assertThat(agingResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> aging = (Map<String, Object>) agingResp.getBody().get("data");
        BigDecimal totalOutstanding = new BigDecimal(aging.get("totalOutstanding").toString());
        assertThat(totalOutstanding).isEqualByComparingTo(expectedOutstanding);

        List<Map<String, Object>> buckets = (List<Map<String, Object>>) aging.get("buckets");
        BigDecimal bucketTotal = BigDecimal.ZERO;
        for (Map<String, Object> bucket : buckets) {
            bucketTotal = bucketTotal.add(safeAmount(bucket.get("amount")));
        }
        assertThat(bucketTotal).isEqualByComparingTo(expectedOutstanding);
    }

    private Map<String, Object> createSupplier(String name, String code) {
        Map<String, Object> req = Map.of(
                "name", name,
                "code", code,
                "contactEmail", "stmt-supplier@bbp.com",
                "stateCode", "27",
                "creditLimit", new BigDecimal("10000.00")
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

        return (Map<String, Object>) activateResp.getBody().get("data");
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

        Map<String, Object> grLine = new HashMap<>(line);
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
        Long goodsReceiptId = ((Number) grData.get("id")).longValue();

        return new PurchaseWorkflowIds(purchaseOrderId, goodsReceiptId);
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

    private BigDecimal safeAmount(Object value) {
        if (value == null) {
            return BigDecimal.ZERO;
        }
        return new BigDecimal(value.toString());
    }

    private String shortSuffix() {
        String token = Long.toString(System.nanoTime());
        return token.length() > 8 ? token.substring(token.length() - 8) : token;
    }
}
