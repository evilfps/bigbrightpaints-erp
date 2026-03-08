package com.bigbrightpaints.erp.modules.sales;

import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGood;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodRepository;
import com.bigbrightpaints.erp.modules.production.domain.ProductionBrand;
import com.bigbrightpaints.erp.modules.production.domain.ProductionBrandRepository;
import com.bigbrightpaints.erp.modules.production.domain.ProductionProduct;
import com.bigbrightpaints.erp.modules.production.domain.ProductionProductRepository;
import com.bigbrightpaints.erp.modules.sales.domain.Dealer;
import com.bigbrightpaints.erp.modules.sales.domain.DealerRepository;
import com.bigbrightpaints.erp.test.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

public class SalesControllerIT extends AbstractIntegrationTest {

    private static final String COMPANY_CODE = "ACME";
    private static final String ADMIN_EMAIL = "admin@bbp.com";
    private static final String ADMIN_PASSWORD = "admin123";
    private static final String ADMIN_DISPATCH_EMAIL = "admin-dispatch@bbp.com";
    private static final String ADMIN_DISPATCH_PASSWORD = "admindispatch123";
    private static final String SALES_EMAIL = "sales@bbp.com";
    private static final String SALES_PASSWORD = "sales123";
    private static final String SALES_DISPATCH_EMAIL = "sales-dispatch@bbp.com";
    private static final String SALES_DISPATCH_PASSWORD = "salesdispatch123";
    private static final String FACTORY_DISPATCH_EMAIL = "factory-dispatch@bbp.com";
    private static final String FACTORY_DISPATCH_PASSWORD = "factorydispatch123";
    private static final String TEST_SKU = "SKU-TEST-001";

    @Autowired private TestRestTemplate rest;
    @Autowired private CompanyRepository companyRepository;
    @Autowired private ProductionBrandRepository productionBrandRepository;
    @Autowired private ProductionProductRepository productionProductRepository;
    @Autowired private FinishedGoodRepository finishedGoodRepository;
    @Autowired private DealerRepository dealerRepository;

    @BeforeEach
    void seed() {
        dataSeeder.ensureUser(ADMIN_EMAIL, ADMIN_PASSWORD, "Admin", COMPANY_CODE, List.of("ROLE_ADMIN", "ROLE_SALES"));
        dataSeeder.ensureUser(
                ADMIN_DISPATCH_EMAIL,
                ADMIN_DISPATCH_PASSWORD,
                "Admin Dispatch User",
                COMPANY_CODE,
                List.of("ROLE_ADMIN", "dispatch.confirm"));
        dataSeeder.ensureUser(SALES_EMAIL, SALES_PASSWORD, "Sales User", COMPANY_CODE, List.of("ROLE_SALES"));
        dataSeeder.ensureUser(
                SALES_DISPATCH_EMAIL,
                SALES_DISPATCH_PASSWORD,
                "Sales Dispatch User",
                COMPANY_CODE,
                List.of("ROLE_SALES", "dispatch.confirm"));
        dataSeeder.ensureUser(
                FACTORY_DISPATCH_EMAIL,
                FACTORY_DISPATCH_PASSWORD,
                "Factory Dispatch User",
                COMPANY_CODE,
                List.of("ROLE_FACTORY", "dispatch.confirm"));
        ensureProductAndFinishedGood();
    }

    private void ensureProductAndFinishedGood() {
        Company company = companyRepository.findByCodeIgnoreCase(COMPANY_CODE).orElseThrow();

        ProductionBrand brand = productionBrandRepository.findByCompanyAndCodeIgnoreCase(company, "TEST")
                .orElseGet(() -> {
                    ProductionBrand b = new ProductionBrand();
                    b.setCompany(company);
                    b.setCode("TEST");
                    b.setName("Test Brand");
                    return productionBrandRepository.save(b);
                });

        ProductionProduct product = productionProductRepository.findByCompanyAndSkuCode(company, TEST_SKU)
                .orElseGet(() -> {
                    ProductionProduct p = new ProductionProduct();
                    p.setCompany(company);
                    p.setBrand(brand);
                    p.setProductName("Test Product");
                    p.setCategory("FINISHED_GOOD");
                    p.setUnitOfMeasure("UNIT");
                    p.setSkuCode(TEST_SKU);
                    p.setBasePrice(new BigDecimal("100.00"));
                    p.setGstRate(BigDecimal.ZERO);
                    return p;
                });
        productionProductRepository.save(product);

        FinishedGood finishedGood = finishedGoodRepository.findByCompanyAndProductCode(company, TEST_SKU)
                .orElseGet(() -> {
                    FinishedGood fg = new FinishedGood();
                    fg.setCompany(company);
                    fg.setProductCode(TEST_SKU);
                    fg.setName("Test Finished Good");
                    fg.setCurrentStock(new BigDecimal("100"));
                    fg.setReservedStock(BigDecimal.ZERO);
                    return fg;
                });
        if (finishedGood.getRevenueAccountId() == null) {
            // Any non-null value is enough for SalesService validation
            finishedGood.setRevenueAccountId(1L);
        }
        finishedGoodRepository.save(finishedGood);
    }

    private String loginToken() {
        return loginToken(ADMIN_EMAIL, ADMIN_PASSWORD);
    }

    private String loginToken(String email, String password) {
        Map<String, Object> req = Map.of(
                "email", email,
                "password", password,
                "companyCode", COMPANY_CODE
        );
        return (String) rest.postForEntity("/api/v1/auth/login", req, Map.class).getBody().get("accessToken");
    }

    private HttpHeaders authenticatedHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Company-Id", COMPANY_CODE);
        return headers;
    }

    private Long createDealer(HttpHeaders headers, String dealerName) {
        String seed = String.valueOf(System.nanoTime());
        Map<String, Object> dealerReq = new HashMap<>();
        dealerReq.put("name", dealerName);
        dealerReq.put("companyName", dealerName + " Paints");
        dealerReq.put("contactEmail", "dealer-" + seed + "@example.com");
        dealerReq.put("contactPhone", "9999999999");
        dealerReq.put("address", "Main Street");
        dealerReq.put("creditLimit", new BigDecimal("100000"));

        ResponseEntity<Map> dResp = rest.exchange("/api/v1/dealers", HttpMethod.POST,
                new HttpEntity<>(dealerReq, headers), Map.class);
        assertThat(dResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> dealerData = (Map<?, ?>) dResp.getBody().get("data");
        return ((Number) dealerData.get("id")).longValue();
    }

    private Long createCreditRequest(HttpHeaders headers, String reason) {
        return createCreditRequest(headers, null, new BigDecimal("1500.00"), reason);
    }

    private Long createCreditRequest(HttpHeaders headers, Long dealerId, BigDecimal amountRequested, String reason) {
        Map<String, Object> request = new HashMap<>();
        request.put("amountRequested", amountRequested);
        request.put("reason", reason);
        if (dealerId != null) {
            request.put("dealerId", dealerId);
        }

        ResponseEntity<Map> createResponse = rest.exchange(
                "/api/v1/sales/credit-requests",
                HttpMethod.POST,
                new HttpEntity<>(request, headers),
                Map.class);
        assertThat(createResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> data = (Map<?, ?>) createResponse.getBody().get("data");
        return ((Number) data.get("id")).longValue();
    }

    private Map<?, ?> salesDashboardData(HttpHeaders headers) {
        ResponseEntity<Map> dashboardResponse = rest.exchange(
                "/api/v1/sales/dashboard",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                Map.class);
        assertThat(dashboardResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        return (Map<?, ?>) dashboardResponse.getBody().get("data");
    }

    private Long createPersistedDealer(String code, BigDecimal creditLimit) {
        Company company = companyRepository.findByCodeIgnoreCase(COMPANY_CODE).orElseThrow();
        Dealer dealer = new Dealer();
        dealer.setCompany(company);
        dealer.setCode(code);
        dealer.setName("Integration Dealer " + code);
        dealer.setStatus("ACTIVE");
        dealer.setCreditLimit(creditLimit);
        return dealerRepository.save(dealer).getId();
    }

    private void createSalesOrder(HttpHeaders headers, Long dealerId) {
        BigDecimal unitPrice = new BigDecimal("100.00");
        BigDecimal quantity = new BigDecimal("2");
        BigDecimal expectedTotal = unitPrice.multiply(quantity);

        Map<String, Object> lineItem = new HashMap<>();
        lineItem.put("productCode", TEST_SKU);
        lineItem.put("description", "Dashboard test line item");
        lineItem.put("quantity", quantity);
        lineItem.put("unitPrice", unitPrice);
        lineItem.put("gstRate", BigDecimal.ZERO);

        Map<String, Object> orderReq = new HashMap<>();
        orderReq.put("dealerId", dealerId);
        orderReq.put("totalAmount", expectedTotal);
        orderReq.put("currency", "INR");
        orderReq.put("notes", "Dashboard test order");
        orderReq.put("items", List.of(lineItem));
        orderReq.put("gstTreatment", "NONE");
        orderReq.put("gstRate", null);

        ResponseEntity<Map> orderResponse = rest.exchange(
                "/api/v1/sales/orders",
                HttpMethod.POST,
                new HttpEntity<>(orderReq, headers),
                Map.class);
        assertThat(orderResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private long bucketCount(Map<?, ?> dashboardData, String bucket) {
        Map<?, ?> buckets = (Map<?, ?>) dashboardData.get("orderStatusBuckets");
        return longValue(buckets.get(bucket));
    }

    private long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(String.valueOf(value));
    }

    private void assertFailureDataMessage(ResponseEntity<Map> response, String expectedMessage) {
        Map<?, ?> data = (Map<?, ?>) response.getBody().get("data");
        assertThat(data).isNotNull();
        assertThat(data.get("message")).isEqualTo(expectedMessage);
    }

    @Test
    void create_dealer_and_sales_order() {
        String token = loginToken();

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Company-Id", COMPANY_CODE);

        Map<String, Object> dealerReq = new HashMap<>();
        dealerReq.put("name", "Prime Dealer");
        dealerReq.put("companyName", "Prime Dealer Paints");
        dealerReq.put("contactEmail", "dealer@example.com");
        dealerReq.put("contactPhone", "9999999999");
        dealerReq.put("address", "Main Street");
        dealerReq.put("creditLimit", new BigDecimal("100000"));

        ResponseEntity<Map> dResp = rest.exchange("/api/v1/dealers", HttpMethod.POST,
                new HttpEntity<>(dealerReq, headers), Map.class);
        assertThat(dResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> dealerData = (Map<?, ?>) dResp.getBody().get("data");
        Long dealerId = ((Number) dealerData.get("id")).longValue();

        BigDecimal unitPrice = new BigDecimal("100.00");
        BigDecimal quantity = new BigDecimal("2");
        BigDecimal expectedTotal = unitPrice.multiply(quantity);

        Map<String, Object> lineItem = new HashMap<>();
        lineItem.put("productCode", TEST_SKU);
        lineItem.put("description", "Test line item");
        lineItem.put("quantity", quantity);
        lineItem.put("unitPrice", unitPrice);
        lineItem.put("gstRate", BigDecimal.ZERO);

        Map<String, Object> orderReq = new HashMap<>();
        orderReq.put("dealerId", dealerId);
        orderReq.put("totalAmount", expectedTotal);
        orderReq.put("currency", "INR");
        orderReq.put("notes", "Test order");
        orderReq.put("items", List.of(lineItem));
        orderReq.put("gstTreatment", "NONE");
        orderReq.put("gstRate", null);

        ResponseEntity<Map> oResp = rest.exchange("/api/v1/sales/orders", HttpMethod.POST,
                new HttpEntity<>(orderReq, headers), Map.class);
        assertThat(oResp.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<Map> listResp = rest.exchange("/api/v1/sales/orders", HttpMethod.GET,
                new HttpEntity<>(headers), Map.class);
        assertThat(listResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<?> list = (List<?>) listResp.getBody().get("data");
        assertThat(list).isNotEmpty();
    }

    @Test
    void sales_dashboard_exposes_metrics_and_tracks_activity() {
        HttpHeaders salesHeaders = authenticatedHeaders(loginToken(SALES_EMAIL, SALES_PASSWORD));
        Map<?, ?> dashboardBefore = salesDashboardData(salesHeaders);
        long pendingBefore = longValue(dashboardBefore.get("pendingCreditRequests"));
        long dealersBefore = longValue(dashboardBefore.get("activeDealers"));
        long ordersBefore = longValue(dashboardBefore.get("totalOrders"));
        long inProgressBefore = bucketCount(dashboardBefore, "in_progress");

        HttpHeaders adminHeaders = authenticatedHeaders(loginToken());
        Long dealerId = createDealer(adminHeaders, "Dashboard Dealer");
        createSalesOrder(adminHeaders, dealerId);
        createCreditRequest(salesHeaders, "Dashboard metric credit request");

        Map<?, ?> dashboardAfter = salesDashboardData(salesHeaders);
        assertThat(longValue(dashboardAfter.get("pendingCreditRequests"))).isEqualTo(pendingBefore + 1);
        assertThat(longValue(dashboardAfter.get("activeDealers"))).isGreaterThanOrEqualTo(dealersBefore + 1);
        assertThat(longValue(dashboardAfter.get("totalOrders"))).isGreaterThanOrEqualTo(ordersBefore + 1);
        assertThat(bucketCount(dashboardAfter, "in_progress")).isGreaterThanOrEqualTo(inProgressBefore + 1);
        @SuppressWarnings("unchecked")
        Map<String, ?> buckets = (Map<String, ?>) dashboardAfter.get("orderStatusBuckets");
        assertThat(buckets).containsKeys(
                "open", "in_progress", "dispatched", "completed", "cancelled", "other");
    }

    @Test
    void dispatch_confirm_denies_sales_even_with_dispatch_confirm_authority() {
        String token = loginToken(SALES_DISPATCH_EMAIL, SALES_DISPATCH_PASSWORD);

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Company-Id", COMPANY_CODE);

        Map<String, Object> line = Map.of(
                "shipQty", new BigDecimal("1.00")
        );
        Map<String, Object> payload = Map.of(
                "packingSlipId", 9999,
                "lines", List.of(line)
        );

        ResponseEntity<Map> response = rest.exchange(
                "/api/v1/sales/dispatch/confirm",
                HttpMethod.POST,
                new HttpEntity<>(payload, headers),
                Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody()).containsEntry(
                "message",
                "Accounting must complete the final dispatch posting after the shipment is confirmed.");
        assertFailureDataMessage(
                response,
                "Accounting must complete the final dispatch posting after the shipment is confirmed.");
    }

    @Test
    void dispatch_confirm_allows_factory_to_reach_business_validation() {
        String token = loginToken(FACTORY_DISPATCH_EMAIL, FACTORY_DISPATCH_PASSWORD);

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Company-Id", COMPANY_CODE);

        Map<String, Object> line = Map.of(
                "shipQty", new BigDecimal("1.00")
        );
        Map<String, Object> payload = Map.of(
                "packingSlipId", 9999,
                "lines", List.of(line)
        );

        ResponseEntity<Map> response = rest.exchange(
                "/api/v1/sales/dispatch/confirm",
                HttpMethod.POST,
                new HttpEntity<>(payload, headers),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat((String) response.getBody().get("message"))
                .contains("transporterName or driverName");
    }

    @Test
    void dispatch_confirm_requires_logistics_metadata_on_admin_endpoint() {
        String token = loginToken(ADMIN_DISPATCH_EMAIL, ADMIN_DISPATCH_PASSWORD);
        HttpHeaders headers = authenticatedHeaders(token);

        Map<String, Object> payload = Map.of(
                "packingSlipId", 9999,
                "lines", List.of(Map.of("shipQty", new BigDecimal("1.00")))
        );

        ResponseEntity<Map> response = rest.exchange(
                "/api/v1/sales/dispatch/confirm",
                HttpMethod.POST,
                new HttpEntity<>(payload, headers),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat((String) response.getBody().get("message"))
                .contains("transporterName or driverName");
    }

    @Test
    void credit_request_approve_requires_decision_reason_metadata() {
        String token = loginToken();
        HttpHeaders headers = authenticatedHeaders(token);
        Long dealerId = createPersistedDealer("CRR" + System.nanoTime(), new BigDecimal("100000.00"));
        Long creditRequestId = createCreditRequest(
                headers,
                dealerId,
                new BigDecimal("1500.00"),
                "Temporary limit extension");

        ResponseEntity<Map> missingReasonResponse = rest.exchange(
                "/api/v1/sales/credit-requests/" + creditRequestId + "/approve",
                HttpMethod.POST,
                new HttpEntity<>(Map.of(), headers),
                Map.class);
        assertThat(missingReasonResponse.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        ResponseEntity<Map> approveResponse = rest.exchange(
                "/api/v1/sales/credit-requests/" + creditRequestId + "/approve",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("reason", "Approved after reviewing ledger exposure"), headers),
                Map.class);
        assertThat(approveResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> approvedData = (Map<?, ?>) approveResponse.getBody().get("data");
        assertThat(approvedData.get("status")).isEqualTo("APPROVED");
    }

    @Test
    void credit_request_approve_increments_dealer_credit_limit() {
        String token = loginToken();
        HttpHeaders headers = authenticatedHeaders(token);
        BigDecimal initialLimit = new BigDecimal("200000.00");
        Long dealerId = createPersistedDealer("CRL" + System.nanoTime(), initialLimit);
        BigDecimal increment = new BigDecimal("1500.00");
        Long creditRequestId = createCreditRequest(
                headers,
                dealerId,
                increment,
                "Temporary increase for seasonal order spike");

        Company company = companyRepository.findByCodeIgnoreCase(COMPANY_CODE).orElseThrow();
        ResponseEntity<Map> approveResponse = rest.exchange(
                "/api/v1/sales/credit-requests/" + creditRequestId + "/approve",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("reason", "Approved after payment behavior review"), headers),
                Map.class);
        assertThat(approveResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        Dealer afterApproval = dealerRepository.findByCompanyAndId(company, dealerId).orElseThrow();
        assertThat(afterApproval.getCreditLimit()).isEqualByComparingTo(initialLimit.add(increment));
    }

    @Test
    void credit_request_reject_requires_decision_reason_metadata() {
        String token = loginToken();
        HttpHeaders headers = authenticatedHeaders(token);
        Long creditRequestId = createCreditRequest(headers, "Overrun request without collateral");

        ResponseEntity<Map> missingReasonResponse = rest.exchange(
                "/api/v1/sales/credit-requests/" + creditRequestId + "/reject",
                HttpMethod.POST,
                new HttpEntity<>(Map.of(), headers),
                Map.class);
        assertThat(missingReasonResponse.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        ResponseEntity<Map> rejectResponse = rest.exchange(
                "/api/v1/sales/credit-requests/" + creditRequestId + "/reject",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("reason", "Rejected due to overdue invoices and no guarantee"), headers),
                Map.class);
        assertThat(rejectResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> rejectedData = (Map<?, ?>) rejectResponse.getBody().get("data");
        assertThat(rejectedData.get("status")).isEqualTo("REJECTED");
    }

    @Test
    void credit_request_list_returns_dealer_name_with_eager_mapping() {
        String token = loginToken();
        HttpHeaders headers = authenticatedHeaders(token);
        Long dealerId = createDealer(headers, "Credit Dealer");
        Long creditRequestId = createCreditRequest(
                headers,
                dealerId,
                new BigDecimal("1500.00"),
                "Need temporary extension with dealer link");

        ResponseEntity<Map> listResponse = rest.exchange(
                "/api/v1/sales/credit-requests",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                Map.class);

        assertThat(listResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> data = (List<Map<String, Object>>) listResponse.getBody().get("data");
        assertThat(data).isNotNull();
        Map<String, Object> target = data.stream()
                .filter(row -> row.get("id") != null
                        && ((Number) row.get("id")).longValue() == creditRequestId)
                .findFirst()
                .orElseThrow();
        assertThat(target.get("dealerName")).isEqualTo("Credit Dealer");
    }
}
