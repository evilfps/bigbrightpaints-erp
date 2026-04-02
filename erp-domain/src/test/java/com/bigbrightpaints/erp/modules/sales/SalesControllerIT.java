package com.bigbrightpaints.erp.modules.sales;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import com.bigbrightpaints.erp.core.fixture.E2eFixtureCatalog;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.modules.sales.domain.Dealer;
import com.bigbrightpaints.erp.modules.sales.domain.DealerRepository;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrder;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrderRepository;
import com.bigbrightpaints.erp.test.AbstractIntegrationTest;
import com.bigbrightpaints.erp.test.support.ErpApiRoutes;

@Tag("critical")
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

  @Autowired private TestRestTemplate rest;
  @Autowired private CompanyRepository companyRepository;
  @Autowired private DealerRepository dealerRepository;
  @Autowired private SalesOrderRepository salesOrderRepository;

  @BeforeEach
  void seed() {
    dataSeeder.ensureUser(
        ADMIN_EMAIL, ADMIN_PASSWORD, "Admin", COMPANY_CODE, List.of("ROLE_ADMIN", "ROLE_SALES"));
    dataSeeder.ensureUser(
        ADMIN_DISPATCH_EMAIL,
        ADMIN_DISPATCH_PASSWORD,
        "Admin Dispatch User",
        COMPANY_CODE,
        List.of("ROLE_ADMIN", "dispatch.confirm"));
    dataSeeder.ensureUser(
        SALES_EMAIL, SALES_PASSWORD, "Sales User", COMPANY_CODE, List.of("ROLE_SALES"));
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
  }

  private String loginToken() {
    return loginToken(ADMIN_EMAIL, ADMIN_PASSWORD);
  }

  private String loginToken(String email, String password) {
    Map<String, Object> req =
        Map.of(
            "email", email,
            "password", password,
            "companyCode", COMPANY_CODE);
    return (String)
        rest.postForEntity("/api/v1/auth/login", req, Map.class).getBody().get("accessToken");
  }

  private HttpHeaders authenticatedHeaders(String token) {
    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth(token);
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.set("X-Company-Code", COMPANY_CODE);
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

    ResponseEntity<Map> dResp =
        rest.exchange(
            ErpApiRoutes.DEALER_DIRECTORY,
            HttpMethod.POST,
            new HttpEntity<>(dealerReq, headers),
            Map.class);
    assertThat(dResp.getStatusCode()).isEqualTo(HttpStatus.OK);
    Map<?, ?> dealerData = (Map<?, ?>) dResp.getBody().get("data");
    return ((Number) dealerData.get("id")).longValue();
  }

  private Long createCreditRequest(
      HttpHeaders headers, Long dealerId, BigDecimal amountRequested, String reason) {
    Map<String, Object> request = new HashMap<>();
    request.put("amountRequested", amountRequested);
    request.put("reason", reason);
    request.put("dealerId", dealerId);

    ResponseEntity<Map> createResponse =
        rest.exchange(
            ErpApiRoutes.CREDIT_LIMIT_REQUESTS,
            HttpMethod.POST,
            new HttpEntity<>(request, headers),
            Map.class);
    assertThat(createResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    Map<?, ?> data = (Map<?, ?>) createResponse.getBody().get("data");
    return ((Number) data.get("id")).longValue();
  }

  private Map<?, ?> salesDashboardData(HttpHeaders headers) {
    ResponseEntity<Map> dashboardResponse =
        rest.exchange(
            ErpApiRoutes.SALES_DASHBOARD,
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

  private Map<?, ?> createSalesOrder(HttpHeaders headers, Long dealerId) {
    BigDecimal unitPrice = new BigDecimal("100.00");
    BigDecimal quantity = new BigDecimal("2");
    BigDecimal expectedTotal = unitPrice.multiply(quantity);

    Map<String, Object> lineItem = new HashMap<>();
    lineItem.put("productCode", E2eFixtureCatalog.ORDER_PRIMARY_SKU);
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

    ResponseEntity<Map> orderResponse =
        rest.exchange(
            ErpApiRoutes.SALES_ORDERS,
            HttpMethod.POST,
            new HttpEntity<>(orderReq, headers),
            Map.class);
    assertThat(orderResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    return (Map<?, ?>) orderResponse.getBody().get("data");
  }

  private SalesOrder createPersistedOrder(
      Company company, Dealer dealer, String orderNumber, String status, Instant createdAt) {
    SalesOrder order = new SalesOrder();
    order.setCompany(company);
    order.setDealer(dealer);
    order.setOrderNumber(orderNumber);
    order.setStatus(status);
    order.setTotalAmount(BigDecimal.ZERO);
    order.setCurrency("INR");
    ReflectionTestUtils.setField(order, "createdAt", createdAt);
    ReflectionTestUtils.setField(order, "updatedAt", createdAt);
    return salesOrderRepository.saveAndFlush(order);
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
    HttpHeaders headers = authenticatedHeaders(loginToken());
    Long dealerId = createDealer(headers, "Prime Dealer");
    createSalesOrder(headers, dealerId);

    ResponseEntity<Map> listResp =
        rest.exchange(
            ErpApiRoutes.SALES_ORDERS, HttpMethod.GET, new HttpEntity<>(headers), Map.class);
    assertThat(listResp.getStatusCode()).isEqualTo(HttpStatus.OK);
    List<?> list = (List<?>) listResp.getBody().get("data");
    assertThat(list).isNotEmpty();
  }

  @Test
  void sales_order_search_returns_created_order_by_order_number() {
    HttpHeaders headers = authenticatedHeaders(loginToken());
    Long dealerId = createDealer(headers, "Search Dealer");
    Map<?, ?> orderData = createSalesOrder(headers, dealerId);
    String orderNumber = String.valueOf(orderData.get("orderNumber"));

    ResponseEntity<Map> emptySearchResponse =
        rest.exchange(
            ErpApiRoutes.SALES_ORDER_SEARCH + "?orderNumber=NO-SUCH-ORDER",
            HttpMethod.GET,
            new HttpEntity<>(headers),
            Map.class);
    assertThat(emptySearchResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    Map<?, ?> emptySearchBody = emptySearchResponse.getBody();
    assertThat(emptySearchBody).isNotNull();
    Map<?, ?> emptySearchData = (Map<?, ?>) emptySearchBody.get("data");
    assertThat(emptySearchData).isNotNull();
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> emptyContent = (List<Map<String, Object>>) emptySearchData.get("content");
    assertThat(emptyContent).isEmpty();

    ResponseEntity<Map> searchResponse =
        rest.exchange(
            ErpApiRoutes.SALES_ORDER_SEARCH + "?orderNumber=" + orderNumber,
            HttpMethod.GET,
            new HttpEntity<>(headers),
            Map.class);

    assertThat(searchResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    Map<?, ?> body = searchResponse.getBody();
    assertThat(body).isNotNull();
    Map<?, ?> data = (Map<?, ?>) body.get("data");
    assertThat(data).isNotNull();
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> content = (List<Map<String, Object>>) data.get("content");
    assertThat(content).isNotEmpty();
    assertThat(content)
        .extracting(row -> String.valueOf(row.get("orderNumber")))
        .contains(orderNumber);
  }

  @Test
  void sales_order_search_repository_supports_alias_filters_escaping_and_unpaged_queries() {
    Long primaryDealerId = createPersistedDealer("ALIAS-A" + System.nanoTime(), new BigDecimal("50000"));
    Long secondaryDealerId =
        createPersistedDealer("ALIAS-B" + System.nanoTime(), new BigDecimal("50000"));

    Company company = companyRepository.findByCodeIgnoreCase(COMPANY_CODE).orElseThrow();
    Dealer primaryDealer = dealerRepository.findById(primaryDealerId).orElseThrow();
    Dealer secondaryDealer = dealerRepository.findById(secondaryDealerId).orElseThrow();

    SalesOrder draftAliasOrder =
        createPersistedOrder(
            company,
            primaryDealer,
            "SO-DRAFT-ALIAS",
            "BOOKED",
            Instant.parse("2026-02-01T00:00:00Z"));
    SalesOrder dispatchedAliasOrder =
        createPersistedOrder(
            company,
            primaryDealer,
            "SO-DISPATCHED-ALIAS",
            "SHIPPED",
            Instant.parse("2026-02-02T00:00:00Z"));
    SalesOrder settledAliasOrder =
        createPersistedOrder(
            company,
            secondaryDealer,
            "SO-SETTLED-ALIAS",
            "COMPLETED",
            Instant.parse("2026-02-03T00:00:00Z"));
    SalesOrder pendingOrder =
        createPersistedOrder(
            company,
            secondaryDealer,
            "SO-PENDING",
            "PENDING",
            Instant.parse("2026-02-04T00:00:00Z"));

    Page<Long> draftResults =
        salesOrderRepository.searchIdsByCompany(
            company,
            "DRAFT",
            primaryDealer,
            "SO-DRAFT-ALIAS",
            draftAliasOrder.getCreatedAt().minusSeconds(5),
            draftAliasOrder.getCreatedAt().plusSeconds(5),
            PageRequest.of(0, 10));
    assertThat(draftResults.getContent()).containsExactly(draftAliasOrder.getId());

    Page<Long> dispatchedResults =
        salesOrderRepository.searchIdsByCompany(
            company,
            "DISPATCHED",
            primaryDealer,
            "SO-DISPATCHED-ALIAS",
            null,
            dispatchedAliasOrder.getCreatedAt().plusSeconds(5),
            PageRequest.of(0, 10));
    assertThat(dispatchedResults.getContent()).containsExactly(dispatchedAliasOrder.getId());

    Page<Long> settledResults =
        salesOrderRepository.searchIdsByCompany(
            company,
            "SETTLED",
            secondaryDealer,
            "SO-SETTLED-ALIAS",
            settledAliasOrder.getCreatedAt().minusSeconds(5),
            null,
            PageRequest.of(0, 10));
    assertThat(settledResults.getContent()).containsExactly(settledAliasOrder.getId());

    Page<Long> pendingResults =
        salesOrderRepository.searchIdsByCompany(
            company,
            "PENDING",
            secondaryDealer,
            "SO-PENDING",
            null,
            null,
            Pageable.unpaged());
    assertThat(pendingResults.getContent()).containsExactly(pendingOrder.getId());
    assertThat(pendingResults.getTotalElements()).isEqualTo(1);

    Page<Long> allResults =
        salesOrderRepository.searchIdsByCompany(
            company,
            null,
            null,
            null,
            draftAliasOrder.getCreatedAt().minusSeconds(5),
            pendingOrder.getCreatedAt().plusSeconds(5),
            PageRequest.of(0, 10));
    assertThat(allResults.getContent())
        .contains(
            pendingOrder.getId(),
            settledAliasOrder.getId(),
            dispatchedAliasOrder.getId(),
            draftAliasOrder.getId());

    String percentSuffix = "PCT" + System.nanoTime();
    String literalPercentOrderNumber = "SO%" + percentSuffix;
    String percentDistractorOrderNumber = "SOX" + percentSuffix;
    SalesOrder literalPercentOrder =
        createPersistedOrder(
            company,
            primaryDealer,
            literalPercentOrderNumber,
            "BOOKED",
            Instant.parse("2026-02-05T00:00:00Z"));
    SalesOrder percentDistractorOrder =
        createPersistedOrder(
            company,
            primaryDealer,
            percentDistractorOrderNumber,
            "BOOKED",
            Instant.parse("2026-02-05T00:00:01Z"));
    Page<Long> literalPercentResults =
        salesOrderRepository.searchIdsByCompany(
            company,
            null,
            primaryDealer,
            "%" + percentSuffix,
            null,
            null,
            PageRequest.of(0, 10));
    assertThat(literalPercentResults.getContent())
        .contains(literalPercentOrder.getId())
        .doesNotContain(percentDistractorOrder.getId());

    String underscoreSuffix = "UND" + System.nanoTime();
    String literalUnderscoreOrderNumber = "SO_" + underscoreSuffix;
    String underscoreDistractorOrderNumber = "SOX" + underscoreSuffix;
    SalesOrder literalUnderscoreOrder =
        createPersistedOrder(
            company,
            secondaryDealer,
            literalUnderscoreOrderNumber,
            "BOOKED",
            Instant.parse("2026-02-05T00:00:02Z"));
    SalesOrder underscoreDistractorOrder =
        createPersistedOrder(
            company,
            secondaryDealer,
            underscoreDistractorOrderNumber,
            "BOOKED",
            Instant.parse("2026-02-05T00:00:03Z"));
    Page<Long> literalUnderscoreResults =
        salesOrderRepository.searchIdsByCompany(
            company,
            null,
            secondaryDealer,
            "_" + underscoreSuffix,
            null,
            null,
            PageRequest.of(0, 10));
    assertThat(literalUnderscoreResults.getContent())
        .contains(literalUnderscoreOrder.getId())
        .doesNotContain(underscoreDistractorOrder.getId());

    String backslashSuffix = "BSL" + System.nanoTime();
    String backslashOrderNumber = "SO\\" + backslashSuffix;
    SalesOrder backslashOrder =
        createPersistedOrder(
            company,
            primaryDealer,
            backslashOrderNumber,
            "BOOKED",
            Instant.parse("2026-02-05T00:00:04Z"));
    SalesOrder backslashDistractorOrder =
        createPersistedOrder(
            company,
            primaryDealer,
            "SOX" + backslashSuffix,
            "BOOKED",
            Instant.parse("2026-02-05T00:00:05Z"));
    Page<Long> backslashResults =
        salesOrderRepository.searchIdsByCompany(
            company, null, primaryDealer, "\\" + backslashSuffix, null, null, PageRequest.of(0, 10));
    assertThat(backslashResults.getContent())
        .contains(backslashOrder.getId())
        .doesNotContain(backslashDistractorOrder.getId());
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
    createCreditRequest(
        salesHeaders, dealerId, new BigDecimal("1500.00"), "Dashboard metric credit request");

    Map<?, ?> dashboardAfter = salesDashboardData(salesHeaders);
    assertThat(longValue(dashboardAfter.get("pendingCreditRequests"))).isEqualTo(pendingBefore + 1);
    assertThat(longValue(dashboardAfter.get("activeDealers")))
        .isGreaterThanOrEqualTo(dealersBefore + 1);
    assertThat(longValue(dashboardAfter.get("totalOrders")))
        .isGreaterThanOrEqualTo(ordersBefore + 1);
    assertThat(bucketCount(dashboardAfter, "in_progress"))
        .isGreaterThanOrEqualTo(inProgressBefore + 1);
    @SuppressWarnings("unchecked")
    Map<String, ?> buckets = (Map<String, ?>) dashboardAfter.get("orderStatusBuckets");
    assertThat(buckets)
        .containsKeys("open", "in_progress", "dispatched", "completed", "cancelled", "other");
  }

  @Test
  void sales_dispatch_confirm_route_is_absent_after_hotfix() {
    String token = loginToken(SALES_EMAIL, SALES_PASSWORD);
    HttpHeaders headers = authenticatedHeaders(token);

    Map<String, Object> payload =
        Map.of(
            "packingSlipId",
            9999,
            "lines",
            List.of(
                Map.of("lineId", 1L, "shippedQuantity", new BigDecimal("1.00"), "notes", "test")));

    ResponseEntity<Map> response =
        rest.exchange(
            "/api/v1/sales/dispatch/confirm",
            HttpMethod.POST,
            new HttpEntity<>(payload, headers),
            Map.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  void credit_request_approve_requires_decision_reason_metadata() {
    String token = loginToken();
    HttpHeaders headers = authenticatedHeaders(token);
    Long dealerId = createPersistedDealer("CRR" + System.nanoTime(), new BigDecimal("100000.00"));
    Long creditRequestId =
        createCreditRequest(
            headers, dealerId, new BigDecimal("1500.00"), "Temporary limit extension");

    ResponseEntity<Map> missingReasonResponse =
        rest.exchange(
            ErpApiRoutes.CREDIT_LIMIT_REQUESTS + "/" + creditRequestId + "/approve",
            HttpMethod.POST,
            new HttpEntity<>(Map.of(), headers),
            Map.class);
    assertThat(missingReasonResponse.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

    ResponseEntity<Map> approveResponse =
        rest.exchange(
            ErpApiRoutes.CREDIT_LIMIT_REQUESTS + "/" + creditRequestId + "/approve",
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
    Long creditRequestId =
        createCreditRequest(
            headers, dealerId, increment, "Temporary increase for seasonal order spike");

    Company company = companyRepository.findByCodeIgnoreCase(COMPANY_CODE).orElseThrow();
    ResponseEntity<Map> approveResponse =
        rest.exchange(
            ErpApiRoutes.CREDIT_LIMIT_REQUESTS + "/" + creditRequestId + "/approve",
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
    Long dealerId = createPersistedDealer("CRR" + System.nanoTime(), new BigDecimal("200000.00"));
    Long creditRequestId =
        createCreditRequest(
            headers, dealerId, new BigDecimal("1500.00"), "Overrun request without collateral");

    ResponseEntity<Map> missingReasonResponse =
        rest.exchange(
            ErpApiRoutes.CREDIT_LIMIT_REQUESTS + "/" + creditRequestId + "/reject",
            HttpMethod.POST,
            new HttpEntity<>(Map.of(), headers),
            Map.class);
    assertThat(missingReasonResponse.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

    ResponseEntity<Map> rejectResponse =
        rest.exchange(
            ErpApiRoutes.CREDIT_LIMIT_REQUESTS + "/" + creditRequestId + "/reject",
            HttpMethod.POST,
            new HttpEntity<>(
                Map.of("reason", "Rejected due to overdue invoices and no guarantee"), headers),
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
    Long creditRequestId =
        createCreditRequest(
            headers,
            dealerId,
            new BigDecimal("1500.00"),
            "Need temporary extension with dealer link");

    ResponseEntity<Map> listResponse =
        rest.exchange(
            ErpApiRoutes.CREDIT_LIMIT_REQUESTS,
            HttpMethod.GET,
            new HttpEntity<>(headers),
            Map.class);

    assertThat(listResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> data = (List<Map<String, Object>>) listResponse.getBody().get("data");
    assertThat(data).isNotNull();
    Map<String, Object> target =
        data.stream()
            .filter(
                row ->
                    row.get("id") != null
                        && ((Number) row.get("id")).longValue() == creditRequestId)
            .findFirst()
            .orElseThrow();
    assertThat(target.get("dealerName")).isEqualTo("Credit Dealer");
  }
}
