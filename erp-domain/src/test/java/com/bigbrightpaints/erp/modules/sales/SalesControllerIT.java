package com.bigbrightpaints.erp.modules.sales;

import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGood;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodRepository;
import com.bigbrightpaints.erp.modules.production.domain.ProductionBrand;
import com.bigbrightpaints.erp.modules.production.domain.ProductionBrandRepository;
import com.bigbrightpaints.erp.modules.production.domain.ProductionProduct;
import com.bigbrightpaints.erp.modules.production.domain.ProductionProductRepository;
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
    private static final String SALES_EMAIL = "sales@bbp.com";
    private static final String SALES_PASSWORD = "sales123";
    private static final String TEST_SKU = "SKU-TEST-001";

    @Autowired private TestRestTemplate rest;
    @Autowired private CompanyRepository companyRepository;
    @Autowired private ProductionBrandRepository productionBrandRepository;
    @Autowired private ProductionProductRepository productionProductRepository;
    @Autowired private FinishedGoodRepository finishedGoodRepository;
    @Autowired private AccountRepository accountRepository;

    @BeforeEach
    void seed() {
        dataSeeder.ensureUser(ADMIN_EMAIL, ADMIN_PASSWORD, "Admin", COMPANY_CODE, List.of("ROLE_ADMIN", "ROLE_SALES"));
        dataSeeder.ensureUser(SALES_EMAIL, SALES_PASSWORD, "Sales User", COMPANY_CODE, List.of("ROLE_SALES"));
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

    private Long createCreditRequest(HttpHeaders headers, String reason) {
        return createCreditRequest(headers, reason, null);
    }

    private Long createCreditRequest(HttpHeaders headers, String reason, Long dealerId) {
        Map<String, Object> request = new HashMap<>();
        request.put("amountRequested", new BigDecimal("1500.00"));
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

    private Long createDealer(HttpHeaders headers, String dealerName) {
        Map<String, Object> dealerReq = new HashMap<>();
        dealerReq.put("name", dealerName);
        dealerReq.put("companyName", dealerName + " Paints");
        dealerReq.put("contactEmail", dealerName.toLowerCase().replace(" ", ".") + "@example.com");
        dealerReq.put("contactPhone", "9999999999");
        dealerReq.put("address", "Main Street");
        dealerReq.put("creditLimit", new BigDecimal("100000"));

        ResponseEntity<Map> dealerResponse = rest.exchange("/api/v1/dealers", HttpMethod.POST,
                new HttpEntity<>(dealerReq, headers), Map.class);
        assertThat(dealerResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> dealerData = (Map<?, ?>) dealerResponse.getBody().get("data");
        return ((Number) dealerData.get("id")).longValue();
    }

    @Test
    void create_dealer_and_sales_order() {
        String token = loginToken();

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Company-Id", COMPANY_CODE);

        Long dealerId = createDealer(headers, "Prime Dealer");

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
    void dispatch_confirm_requires_permission() {
        String token = loginToken(SALES_EMAIL, SALES_PASSWORD);

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
    }

    @Test
    void credit_requests_list_includes_dealer_name() {
        String token = loginToken();
        HttpHeaders headers = authenticatedHeaders(token);
        Long dealerId = createDealer(headers, "Credit Dealer");
        createCreditRequest(headers, "Bridge financing request", dealerId);

        ResponseEntity<Map> listResponse = rest.exchange(
                "/api/v1/sales/credit-requests",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                Map.class);
        assertThat(listResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        List<?> data = (List<?>) listResponse.getBody().get("data");
        assertThat(data)
                .isNotEmpty()
                .anySatisfy(item -> assertThat(((Map<?, ?>) item).get("dealerName")).isEqualTo("Credit Dealer"));
    }

    @Test
    void credit_request_approve_requires_decision_reason_metadata() {
        String token = loginToken();
        HttpHeaders headers = authenticatedHeaders(token);
        Long creditRequestId = createCreditRequest(headers, "Temporary limit extension");

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
}
