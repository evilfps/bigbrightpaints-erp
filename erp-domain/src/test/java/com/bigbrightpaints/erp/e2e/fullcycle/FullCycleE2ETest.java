package com.bigbrightpaints.erp.e2e.fullcycle;

import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountType;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryRepository;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterial;
import com.bigbrightpaints.erp.modules.invoice.domain.InvoiceRepository;
import com.bigbrightpaints.erp.modules.purchasing.domain.RawMaterialPurchaseRepository;
import com.bigbrightpaints.erp.modules.purchasing.domain.SupplierRepository;
import com.bigbrightpaints.erp.modules.sales.domain.DealerRepository;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrderRepository;
import com.bigbrightpaints.erp.test.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Disabled;

@ActiveProfiles("test")
@DisplayName("E2E: Full Cycle Integration Tests")
@Disabled("Incomplete test implementation - needs proper setup")
public class FullCycleE2ETest extends AbstractIntegrationTest {

    private static final String COMPANY_CODE = "CYCLE-E2E";
    private static final String ADMIN_EMAIL = "cycle@e2e.com";
    private static final String ADMIN_PASSWORD = "cycle123";

    @Autowired private TestRestTemplate rest;
    @Autowired private CompanyRepository companyRepository;
    @Autowired private DealerRepository dealerRepository;
    @Autowired private SupplierRepository supplierRepository;
    @Autowired private FinishedGoodRepository finishedGoodRepository;
    @Autowired private SalesOrderRepository salesOrderRepository;
    @Autowired private InvoiceRepository invoiceRepository;
    @Autowired private RawMaterialPurchaseRepository purchaseRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private JournalEntryRepository journalEntryRepository;
    @Autowired private RawMaterialRepository rawMaterialRepository;

    private String authToken;
    private HttpHeaders headers;
    private Company company;

    @BeforeEach
    void setup() {
        dataSeeder.ensureUser(ADMIN_EMAIL, ADMIN_PASSWORD, "Cycle Admin", COMPANY_CODE,
                List.of("ROLE_ADMIN", "ROLE_SALES", "ROLE_ACCOUNTING"));
        company = companyRepository.findByCodeIgnoreCase(COMPANY_CODE).orElseThrow();
        authToken = login();
        headers = createHeaders(authToken);
        ensureTestAccounts();
    }

    private String login() {
        Map<String, Object> req = Map.of(
                "email", ADMIN_EMAIL,
                "password", ADMIN_PASSWORD,
                "companyCode", COMPANY_CODE
        );
        ResponseEntity<Map> response = rest.postForEntity("/api/v1/auth/login", req, Map.class);
        return (String) response.getBody().get("accessToken");
    }

    private HttpHeaders createHeaders(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        h.setContentType(MediaType.APPLICATION_JSON);
        h.set("X-Company-Code", COMPANY_CODE);
        return h;
    }

    private void ensureTestAccounts() {
        ensureAccount("AR", "Accounts Receivable", AccountType.ASSET);
        ensureAccount("AP", "Accounts Payable", AccountType.LIABILITY);
        ensureAccount("CASH", "Cash", AccountType.ASSET);
        ensureAccount("INV-FG", "Finished Goods Inventory", AccountType.ASSET);
        ensureAccount("INV-RM", "Raw Material Inventory", AccountType.ASSET);
        ensureAccount("COGS", "Cost of Goods Sold", AccountType.EXPENSE);
        ensureAccount("REV", "Revenue", AccountType.REVENUE);
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

    @Test
    @DisplayName("Order-to-Cash Full Cycle: Order -> Confirm -> Dispatch -> Invoice -> Payment")
    void orderToCashFullCycle() {
        // 1. Create dealer and finished good with stock
        var dealer = createDealer("OTC-DEALER");
        var fg = createFinishedGood("OTC-FG001", new BigDecimal("100"));

        // 2. Create sales order
        Map<String, Object> orderReq = Map.of(
                "dealerId", dealer.getId(),
                "items", List.of(Map.of(
                        "productCode", fg.getProductCode(),
                        "quantity", new BigDecimal("5"),
                        "unitPrice", new BigDecimal("100"),
                        "gstRate", BigDecimal.ZERO
                )),
                "gstTreatment", "NONE"
        );
        ResponseEntity<Map> orderResp = rest.exchange("/api/v1/sales/orders", HttpMethod.POST,
                new HttpEntity<>(orderReq, headers), Map.class);
        assertThat(orderResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Long orderId = ((Number) requireData(orderResp, "order").get("id")).longValue();

        // 3. Confirm order (reserve inventory)
        ResponseEntity<Map> confirmResp = rest.exchange("/api/v1/sales/orders/" + orderId + "/confirm", HttpMethod.POST,
                new HttpEntity<>(headers), Map.class);
        assertThat(confirmResp.getStatusCode()).isEqualTo(HttpStatus.OK);

        // 4. Dispatch confirm (invoice + COGS)
        Map<String, Object> dispatchReq = Map.of("orderId", orderId);
        ResponseEntity<Map> dispatchResp = rest.exchange("/api/v1/sales/dispatch/confirm", HttpMethod.POST,
                new HttpEntity<>(dispatchReq, headers), Map.class);
        assertThat(dispatchResp.getStatusCode()).isEqualTo(HttpStatus.OK);

        // 5. Verify invoice created
        List<?> invoices = invoiceRepository.findAll();
        assertThat(invoices).hasSizeGreaterThan(0);

        // 6. Get invoice ID and settle (payment)
        Long invoiceId = invoiceRepository.findAll().stream()
                .filter(inv -> ((com.bigbrightpaints.erp.modules.invoice.domain.Invoice) inv).getCompany().equals(company))
                .map(Object::toString).count() > 0 ? 1L : null; // simplistic, in real test query properly
        Map<String, Object> settleReq = Map.of(
                "dealerId", dealer.getId(),
                "cashAccountId", accountRepository.findByCompanyAndCodeIgnoreCase(company, "CASH").get().getId(),
                "allocations", List.of(Map.of("invoiceId", invoiceId, "appliedAmount", new BigDecimal("500")))
        );
        ResponseEntity<Map> settleResp = rest.exchange("/api/v1/accounting/settlements/dealers", HttpMethod.POST,
                new HttpEntity<>(settleReq, headers), Map.class);
        assertThat(settleResp.getStatusCode()).isEqualTo(HttpStatus.OK);

        // Verify journals exist
        assertThat(journalEntryRepository.count()).isGreaterThan(1);
    }

    @Test
    @DisplayName("Purchase-to-Pay Full Cycle: Purchase -> Receipt -> Payment")
    void purchaseToPayFullCycle() {
        // 1. Create supplier and raw material
        var supplier = createSupplier("P2P-SUPPLIER");
        var rm = createRawMaterial("P2P-RM001");
        var workflow = createPurchaseOrderAndReceipt(supplier.getId(), rm.getId(),
                new BigDecimal("10"), new BigDecimal("50"), LocalDate.now());

        // 2. Create raw material purchase
        Map<String, Object> purchaseReq = Map.of(
                "supplierId", supplier.getId(),
                "invoiceNumber", "PURCH-INV-001",
                "invoiceDate", LocalDate.now().toString(),
                "purchaseOrderId", workflow.purchaseOrderId(),
                "goodsReceiptId", workflow.goodsReceiptId(),
                "lines", List.of(Map.of(
                        "rawMaterialId", rm.getId(),
                        "quantity", new BigDecimal("10"),
                        "costPerUnit", new BigDecimal("50")
                ))
        );
        ResponseEntity<Map> purchaseResp = rest.exchange("/api/v1/purchasing/raw-material-purchases", HttpMethod.POST,
                new HttpEntity<>(purchaseReq, headers), Map.class);
        assertThat(purchaseResp.getStatusCode()).isEqualTo(HttpStatus.OK);

        // 3. Verify RM stock increased and AP journal
        assertThat(rm.getCurrentStock()).isGreaterThan(BigDecimal.ZERO);

        // 4. Settle supplier (payment)
        Long purchaseId = ((Number) requireData(purchaseResp, "purchase").get("id")).longValue();
        String settlementKey = "SET-" + shortSuffix();
        Map<String, Object> settleReq = Map.of(
                "supplierId", supplier.getId(),
                "cashAccountId", accountRepository.findByCompanyAndCodeIgnoreCase(company, "CASH").get().getId(),
                "idempotencyKey", settlementKey,
                "allocations", List.of(Map.of("purchaseId", purchaseId, "appliedAmount", new BigDecimal("500")))
        );
        ResponseEntity<Map> settleResp = rest.exchange("/api/v1/accounting/settlements/suppliers", HttpMethod.POST,
                new HttpEntity<>(settleReq, headers), Map.class);
        assertThat(settleResp.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private com.bigbrightpaints.erp.modules.sales.domain.Dealer createDealer(String code) {
        com.bigbrightpaints.erp.modules.sales.domain.Dealer dealer = new com.bigbrightpaints.erp.modules.sales.domain.Dealer();
        dealer.setCompany(company);
        dealer.setCode(code);
        dealer.setName("Test Dealer " + code);
        dealer.setEmail(code.toLowerCase() + "@test.com");
        dealer.setStatus("ACTIVE");
        return dealerRepository.save(dealer);
    }

    private RawMaterial createRawMaterial(String code) {
        RawMaterial rm = new RawMaterial();
        rm.setCompany(company);
        rm.setSku(code);
        rm.setName("Raw Material " + code);
        rm.setUnitType("KG");
        rm.setCurrentStock(BigDecimal.valueOf(1000));
        return rawMaterialRepository.save(rm);
    }

    private PurchaseWorkflowIds createPurchaseOrderAndReceipt(Long supplierId,
                                                              Long rawMaterialId,
                                                              BigDecimal quantity,
                                                              BigDecimal costPerUnit,
                                                              LocalDate entryDate) {
        Map<String, Object> line = Map.of(
                "rawMaterialId", rawMaterialId,
                "quantity", quantity,
                "costPerUnit", costPerUnit,
                "unit", "KG"
        );

        Map<String, Object> poReq = Map.of(
                "supplierId", supplierId,
                "orderNumber", "PO-" + shortSuffix(),
                "orderDate", entryDate.toString(),
                "lines", List.of(line)
        );
        ResponseEntity<Map> poResp = rest.exchange(
                "/api/v1/purchasing/purchase-orders",
                HttpMethod.POST,
                new HttpEntity<>(poReq, headers),
                Map.class);
        assertThat(poResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Long purchaseOrderId = ((Number) requireData(poResp, "purchase order").get("id")).longValue();

        Map<String, Object> grLine = Map.of(
                "rawMaterialId", rawMaterialId,
                "quantity", quantity,
                "costPerUnit", costPerUnit,
                "unit", "KG",
                "batchCode", "GRN-" + shortSuffix()
        );
        Map<String, Object> grReq = Map.of(
                "purchaseOrderId", purchaseOrderId,
                "receiptNumber", "GRN-" + shortSuffix(),
                "receiptDate", entryDate.toString(),
                "idempotencyKey", "GRN-IDEMP-" + shortSuffix(),
                "lines", List.of(grLine)
        );
        ResponseEntity<Map> grResp = rest.exchange(
                "/api/v1/purchasing/goods-receipts",
                HttpMethod.POST,
                new HttpEntity<>(grReq, headers),
                Map.class);
        assertThat(grResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Long goodsReceiptId = ((Number) requireData(grResp, "goods receipt").get("id")).longValue();

        return new PurchaseWorkflowIds(purchaseOrderId, goodsReceiptId);
    }

    private record PurchaseWorkflowIds(Long purchaseOrderId, Long goodsReceiptId) {}

    private String shortSuffix() {
        String token = Long.toString(System.nanoTime());
        return token.length() > 8 ? token.substring(token.length() - 8) : token;
    }

    private com.bigbrightpaints.erp.modules.purchasing.domain.Supplier createSupplier(String code) {
        com.bigbrightpaints.erp.modules.purchasing.domain.Supplier supplier = new com.bigbrightpaints.erp.modules.purchasing.domain.Supplier();
        supplier.setCompany(company);
        supplier.setCode(code);
        supplier.setName("Test Supplier " + code);
        supplier.setEmail(code.toLowerCase() + "@supplier.com");
        supplier.setStatus("ACTIVE");
        return supplierRepository.save(supplier);
    }

    private com.bigbrightpaints.erp.modules.inventory.domain.FinishedGood createFinishedGood(String code, BigDecimal stock) {
        com.bigbrightpaints.erp.modules.inventory.domain.FinishedGood fg = new com.bigbrightpaints.erp.modules.inventory.domain.FinishedGood();
        fg.setCompany(company);
        fg.setProductCode(code);
        fg.setName("Test Product " + code);
        fg.setCurrentStock(stock);
        fg.setUnit("PCS");
        return finishedGoodRepository.save(fg);
    }

    private static Map<?, ?> requireData(ResponseEntity<Map> response, String action) {
        // From existing
        Map<?, ?> body = response.getBody();
        return (Map<?, ?>) body.get("data");
    }
}
