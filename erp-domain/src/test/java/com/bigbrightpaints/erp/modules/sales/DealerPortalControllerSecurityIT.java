package com.bigbrightpaints.erp.modules.sales;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

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

import com.bigbrightpaints.erp.modules.auth.domain.UserAccount;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.modules.invoice.domain.Invoice;
import com.bigbrightpaints.erp.modules.invoice.domain.InvoiceRepository;
import com.bigbrightpaints.erp.modules.sales.domain.Dealer;
import com.bigbrightpaints.erp.modules.sales.domain.DealerRepository;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrder;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrderRepository;
import com.bigbrightpaints.erp.test.AbstractIntegrationTest;

@DisplayName("Dealer Portal Security")
class DealerPortalControllerSecurityIT extends AbstractIntegrationTest {

  private static final String COMPANY_CODE = "DEALER-PORTAL-SEC";
  private static final String DEALER_A_EMAIL = "portal-dealer-a@bbp.com";
  private static final String DEALER_B_EMAIL = "portal-dealer-b@bbp.com";
  private static final String DEALER_ORPHAN_EMAIL = "portal-dealer-orphan@bbp.com";
  private static final String ADMIN_EMAIL = "portal-admin@bbp.com";
  private static final String PASSWORD = "changeme";

  @Autowired private TestRestTemplate rest;
  @Autowired private CompanyRepository companyRepository;
  @Autowired private DealerRepository dealerRepository;
  @Autowired private InvoiceRepository invoiceRepository;
  @Autowired private SalesOrderRepository salesOrderRepository;

  private Dealer dealerA;
  private Dealer dealerB;
  private Invoice invoiceA;
  private Invoice invoiceB;
  private SalesOrder orderA;

  @BeforeEach
  void setup() {
    UserAccount dealerAUser =
        dataSeeder.ensureUser(
            DEALER_A_EMAIL, PASSWORD, "Dealer A User", COMPANY_CODE, List.of("ROLE_DEALER"));
    UserAccount dealerBUser =
        dataSeeder.ensureUser(
            DEALER_B_EMAIL, PASSWORD, "Dealer B User", COMPANY_CODE, List.of("ROLE_DEALER"));
    dataSeeder.ensureUser(
        DEALER_ORPHAN_EMAIL, PASSWORD, "Dealer Orphan User", COMPANY_CODE, List.of("ROLE_DEALER"));
    dataSeeder.ensureUser(ADMIN_EMAIL, PASSWORD, "Admin User", COMPANY_CODE, List.of("ROLE_ADMIN"));

    Company company = companyRepository.findByCodeIgnoreCase(COMPANY_CODE).orElseThrow();
    dealerA = upsertDealer(company, "PORTAL-A", "Portal Dealer A", dealerAUser);
    dealerB = upsertDealer(company, "PORTAL-B", "Portal Dealer B", dealerBUser);
    invoiceA = upsertInvoice(company, dealerA, "INV-PORTAL-A");
    invoiceB = upsertInvoice(company, dealerB, "INV-PORTAL-B");
    orderA =
        upsertOrder(
            company, dealerA, "SO-PORTAL-A-OPEN", "PENDING_PRODUCTION", new BigDecimal("5000.00"));
  }

  @Test
  @DisplayName("Dealer portal invoices are strictly scoped to authenticated dealer")
  void dealerPortalInvoices_areScopedToCurrentDealer() {
    HttpHeaders headers = authHeaders(DEALER_A_EMAIL, PASSWORD);
    ResponseEntity<Map> response =
        rest.exchange(
            "/api/v1/dealer-portal/invoices", HttpMethod.GET, new HttpEntity<>(headers), Map.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    Map<?, ?> data = (Map<?, ?>) response.getBody().get("data");
    assertThat(asLong(data.get("dealerId"))).isEqualTo(dealerA.getId());
    List<?> invoices = (List<?>) data.get("invoices");
    assertThat(invoices)
        .map(Map.class::cast)
        .extracting(map -> asLong(map.get("id")))
        .contains(invoiceA.getId())
        .doesNotContain(invoiceB.getId());
  }

  @Test
  @DisplayName("Dealer portal ledger stays scoped to the authenticated dealer")
  void dealerPortalLedger_isScopedToCurrentDealer() {
    HttpHeaders headers = authHeaders(DEALER_A_EMAIL, PASSWORD);
    ResponseEntity<Map> response =
        rest.exchange(
            "/api/v1/dealer-portal/ledger", HttpMethod.GET, new HttpEntity<>(headers), Map.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    Map<?, ?> data = (Map<?, ?>) response.getBody().get("data");
    assertThat(asLong(data.get("dealerId"))).isEqualTo(dealerA.getId());
    assertThat(data.get("dealerName")).isEqualTo(dealerA.getName());
    assertThat(data.get("entries")).isInstanceOf(List.class);
  }

  @Test
  @DisplayName(
      "Dealer portal aging is scoped to the authenticated dealer without leaking other dealers")
  void dealerPortalAging_isScopedToCurrentDealer() {
    HttpHeaders headers = authHeaders(DEALER_A_EMAIL, PASSWORD);
    ResponseEntity<Map> response =
        rest.exchange(
            "/api/v1/dealer-portal/aging", HttpMethod.GET, new HttpEntity<>(headers), Map.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    Map<?, ?> data = (Map<?, ?>) response.getBody().get("data");
    assertThat(asLong(data.get("dealerId"))).isEqualTo(dealerA.getId());
    assertThat(new BigDecimal(String.valueOf(data.get("totalOutstanding"))))
        .isEqualByComparingTo("0.00");
    assertThat(((Number) data.get("pendingOrderCount")).longValue()).isEqualTo(1L);
    assertThat(new BigDecimal(String.valueOf(data.get("pendingOrderExposure"))))
        .isEqualByComparingTo("5000.00");
    assertThat(new BigDecimal(String.valueOf(data.get("creditUsed"))))
        .isEqualByComparingTo("5000.00");
    assertThat(new BigDecimal(String.valueOf(data.get("availableCredit"))))
        .isEqualByComparingTo("95000.00");
  }

  @Test
  @DisplayName("Dealer portal invoice PDF endpoint rejects cross-dealer invoice enumeration")
  void dealerPortalInvoicePdf_rejectsCrossDealerInvoiceId() {
    HttpHeaders headers = authHeaders(DEALER_A_EMAIL, PASSWORD);
    ResponseEntity<byte[]> response =
        rest.exchange(
            "/api/v1/dealer-portal/invoices/" + invoiceB.getId() + "/pdf",
            HttpMethod.GET,
            new HttpEntity<>(headers),
            byte[].class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  @DisplayName("Dealer portal invoice PDF endpoint serves the authenticated dealer invoice")
  void dealerPortalInvoicePdf_servesOwnInvoice() {
    HttpHeaders headers = authHeaders(DEALER_A_EMAIL, PASSWORD);
    ResponseEntity<byte[]> response =
        rest.exchange(
            "/api/v1/dealer-portal/invoices/" + invoiceA.getId() + "/pdf",
            HttpMethod.GET,
            new HttpEntity<>(headers),
            byte[].class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getHeaders().getContentType()).isNotNull();
    assertThat(response.getHeaders().getContentType().toString()).contains("application/pdf");
    assertThat(response.getBody()).isNotEmpty();
  }

  @Test
  @DisplayName("Inactive dealer portal mapping is denied even with a valid dealer token")
  void inactiveDealerPortalMapping_isDenied() {
    dealerA.setStatus("INACTIVE");
    dealerRepository.saveAndFlush(dealerA);

    HttpHeaders headers = authHeaders(DEALER_A_EMAIL, PASSWORD);
    ResponseEntity<Map> response =
        rest.exchange(
            "/api/v1/dealer-portal/dashboard",
            HttpMethod.GET,
            new HttpEntity<>(headers),
            Map.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
  }

  @Test
  @DisplayName("Non-active dealer login is limited to finance read-only portal endpoints")
  void nonActiveDealerLogin_isLimitedToFinanceReadOnlyPortalEndpoints() {
    dealerA.setStatus("ON_HOLD");
    dealerRepository.saveAndFlush(dealerA);

    HttpHeaders headers = authHeaders(DEALER_A_EMAIL, PASSWORD);
    ResponseEntity<Map> ledgerResponse =
        rest.exchange(
            "/api/v1/dealer-portal/ledger", HttpMethod.GET, new HttpEntity<>(headers), Map.class);
    ResponseEntity<Map> agingResponse =
        rest.exchange(
            "/api/v1/dealer-portal/aging", HttpMethod.GET, new HttpEntity<>(headers), Map.class);
    ResponseEntity<Map> invoicesResponse =
        rest.exchange(
            "/api/v1/dealer-portal/invoices", HttpMethod.GET, new HttpEntity<>(headers), Map.class);
    ResponseEntity<Map> dashboardResponse =
        rest.exchange(
            "/api/v1/dealer-portal/dashboard",
            HttpMethod.GET,
            new HttpEntity<>(headers),
            Map.class);
    ResponseEntity<Map> ordersResponse =
        rest.exchange(
            "/api/v1/dealer-portal/orders", HttpMethod.GET, new HttpEntity<>(headers), Map.class);
    ResponseEntity<Map> supportTicketsResponse =
        rest.exchange(
            "/api/v1/dealer-portal/support/tickets",
            HttpMethod.GET,
            new HttpEntity<>(headers),
            Map.class);
    HttpHeaders requestHeaders = new HttpHeaders();
    requestHeaders.putAll(headers);
    requestHeaders.setContentType(MediaType.APPLICATION_JSON);
    ResponseEntity<Map> creditLimitRequestResponse =
        rest.exchange(
            "/api/v1/dealer-portal/credit-limit-requests",
            HttpMethod.POST,
            new HttpEntity<>(
                Map.of(
                    "amountRequested", "1500.00",
                    "reason", "need-more-credit"),
                requestHeaders),
            Map.class);

    assertThat(ledgerResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(agingResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(invoicesResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(dashboardResponse.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(ordersResponse.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(supportTicketsResponse.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(creditLimitRequestResponse.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
  }

  @Test
  @DisplayName("Dealer and portal finance hosts stay on one ledger, invoice, and aging truth")
  void dealerAndPortalFinanceHosts_shareCanonicalFinanceTruth() {
    HttpHeaders dealerHeaders = authHeaders(DEALER_A_EMAIL, PASSWORD);
    HttpHeaders adminHeaders = authHeaders(ADMIN_EMAIL, PASSWORD);

    Map<?, ?> dealerLedger =
        (Map<?, ?>)
            rest.exchange(
                    "/api/v1/dealer-portal/ledger",
                    HttpMethod.GET,
                    new HttpEntity<>(dealerHeaders),
                    Map.class)
                .getBody()
                .get("data");
    Map<?, ?> portalLedger =
        (Map<?, ?>)
            rest.exchange(
                    "/api/v1/portal/finance/ledger?dealerId=" + dealerA.getId(),
                    HttpMethod.GET,
                    new HttpEntity<>(adminHeaders),
                    Map.class)
                .getBody()
                .get("data");
    assertThat(portalLedger.get("dealerId")).isEqualTo(dealerLedger.get("dealerId"));
    assertThat(portalLedger.get("dealerName")).isEqualTo(dealerLedger.get("dealerName"));
    assertThat(asBigDecimal(portalLedger.get("currentBalance")))
        .isEqualByComparingTo(asBigDecimal(dealerLedger.get("currentBalance")));
    assertThat(portalLedger.get("entries")).isEqualTo(dealerLedger.get("entries"));

    Map<?, ?> dealerInvoices =
        (Map<?, ?>)
            rest.exchange(
                    "/api/v1/dealer-portal/invoices",
                    HttpMethod.GET,
                    new HttpEntity<>(dealerHeaders),
                    Map.class)
                .getBody()
                .get("data");
    Map<?, ?> portalInvoices =
        (Map<?, ?>)
            rest.exchange(
                    "/api/v1/portal/finance/invoices?dealerId=" + dealerA.getId(),
                    HttpMethod.GET,
                    new HttpEntity<>(adminHeaders),
                    Map.class)
                .getBody()
                .get("data");
    assertThat(portalInvoices.get("dealerId")).isEqualTo(dealerInvoices.get("dealerId"));
    assertThat(portalInvoices.get("dealerName")).isEqualTo(dealerInvoices.get("dealerName"));
    assertThat(asBigDecimal(portalInvoices.get("totalOutstanding")))
        .isEqualByComparingTo(asBigDecimal(dealerInvoices.get("totalOutstanding")));
    assertThat(asLong(portalInvoices.get("invoiceCount")))
        .isEqualTo(asLong(dealerInvoices.get("invoiceCount")));
    assertThat(portalInvoices.get("invoices")).isEqualTo(dealerInvoices.get("invoices"));

    Map<?, ?> dealerAging =
        (Map<?, ?>)
            rest.exchange(
                    "/api/v1/dealer-portal/aging",
                    HttpMethod.GET,
                    new HttpEntity<>(dealerHeaders),
                    Map.class)
                .getBody()
                .get("data");
    Map<?, ?> portalAging =
        (Map<?, ?>)
            rest.exchange(
                    "/api/v1/portal/finance/aging?dealerId=" + dealerA.getId(),
                    HttpMethod.GET,
                    new HttpEntity<>(adminHeaders),
                    Map.class)
                .getBody()
                .get("data");
    assertThat(portalAging.get("dealerId")).isEqualTo(dealerAging.get("dealerId"));
    assertThat(portalAging.get("dealerName")).isEqualTo(dealerAging.get("dealerName"));
    assertThat(asBigDecimal(portalAging.get("totalOutstanding")))
        .isEqualByComparingTo(asBigDecimal(dealerAging.get("totalOutstanding")));
    assertThat(asLong(portalAging.get("pendingOrderCount")))
        .isEqualTo(asLong(dealerAging.get("pendingOrderCount")));
    assertThat(asBigDecimal(portalAging.get("pendingOrderExposure")))
        .isEqualByComparingTo(asBigDecimal(dealerAging.get("pendingOrderExposure")));
    assertThat(asBigDecimal(portalAging.get("creditUsed")))
        .isEqualByComparingTo(asBigDecimal(dealerAging.get("creditUsed")));
    assertThat(asBigDecimal(portalAging.get("availableCredit")))
        .isEqualByComparingTo(asBigDecimal(dealerAging.get("availableCredit")));
    assertThat(portalAging.get("agingBuckets")).isEqualTo(dealerAging.get("agingBuckets"));
    assertThat(portalAging.get("overdueInvoices")).isEqualTo(dealerAging.get("overdueInvoices"));
  }

  @Test
  @DisplayName("Dealer portal rejects token/header company mismatch")
  void dealerPortalRejectsCompanyHeaderMismatch() {
    HttpHeaders headers = authHeaders(DEALER_A_EMAIL, PASSWORD);
    headers.set("X-Company-Code", "EVIL");
    ResponseEntity<Map> response =
        rest.exchange(
            "/api/v1/dealer-portal/dashboard",
            HttpMethod.GET,
            new HttpEntity<>(headers),
            Map.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
  }

  @Test
  @DisplayName("Non dealer role cannot access dealer portal endpoints")
  void nonDealerRoleCannotAccessDealerPortal() {
    HttpHeaders headers = authHeaders(ADMIN_EMAIL, PASSWORD);
    ResponseEntity<Map> response =
        rest.exchange(
            "/api/v1/dealer-portal/dashboard",
            HttpMethod.GET,
            new HttpEntity<>(headers),
            Map.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
  }

  @Test
  @DisplayName("Dealer principal without dealer mapping is denied with forbidden status")
  void dealerWithoutMappingIsForbidden() {
    HttpHeaders headers = authHeaders(DEALER_ORPHAN_EMAIL, PASSWORD);
    ResponseEntity<Map> response =
        rest.exchange(
            "/api/v1/dealer-portal/dashboard",
            HttpMethod.GET,
            new HttpEntity<>(headers),
            Map.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
  }

  @Test
  @DisplayName("Dealer portal orders expose pending-order visibility and exposure totals")
  void dealerPortalOrders_includePendingExposureSignals() {
    HttpHeaders headers = authHeaders(DEALER_A_EMAIL, PASSWORD);
    ResponseEntity<Map> response =
        rest.exchange(
            "/api/v1/dealer-portal/orders", HttpMethod.GET, new HttpEntity<>(headers), Map.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    Map<?, ?> data = (Map<?, ?>) response.getBody().get("data");
    assertThat(asLong(data.get("dealerId"))).isEqualTo(dealerA.getId());
    assertThat(((Number) data.get("pendingOrderCount")).longValue()).isEqualTo(1L);
    assertThat(new BigDecimal(String.valueOf(data.get("pendingOrderExposure"))))
        .isEqualByComparingTo("5000.00");
    List<?> orders = (List<?>) data.get("orders");
    Map<?, ?> pendingOrder =
        orders.stream()
            .map(Map.class::cast)
            .filter(order -> orderA.getOrderNumber().equals(order.get("orderNumber")))
            .findFirst()
            .orElseThrow();
    assertThat((Boolean) pendingOrder.get("pendingCreditExposure")).isTrue();
    assertThat(pendingOrder.get("status")).isEqualTo("PENDING_PRODUCTION");
  }

  @Test
  @DisplayName("Dealer portal excludes invoiced orders from pending-credit exposure totals")
  void dealerPortalOrders_excludesInvoicedOrdersFromPendingExposure() {
    Company company = companyRepository.findByCodeIgnoreCase(COMPANY_CODE).orElseThrow();
    SalesOrder invoicedOrder =
        upsertOrder(
            company,
            dealerA,
            "SO-PORTAL-A-INVOICED",
            "PENDING_PRODUCTION",
            new BigDecimal("4000.00"));
    Invoice linkedInvoice = upsertInvoice(company, dealerA, "INV-PORTAL-A-LINKED");
    linkedInvoice.setStatus("ISSUED");
    linkedInvoice.setSalesOrder(invoicedOrder);
    linkedInvoice.setSubtotal(new BigDecimal("3000.00"));
    linkedInvoice.setTaxTotal(new BigDecimal("540.00"));
    linkedInvoice.setTotalAmount(new BigDecimal("3540.00"));
    linkedInvoice.setOutstandingAmount(new BigDecimal("1200.00"));
    invoiceRepository.saveAndFlush(linkedInvoice);

    HttpHeaders headers = authHeaders(DEALER_A_EMAIL, PASSWORD);
    ResponseEntity<Map> response =
        rest.exchange(
            "/api/v1/dealer-portal/orders", HttpMethod.GET, new HttpEntity<>(headers), Map.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    Map<?, ?> data = (Map<?, ?>) response.getBody().get("data");
    assertThat(((Number) data.get("pendingOrderCount")).longValue()).isEqualTo(1L);
    assertThat(new BigDecimal(String.valueOf(data.get("pendingOrderExposure"))))
        .isEqualByComparingTo("5000.00");
    List<?> orders = (List<?>) data.get("orders");
    Map<?, ?> invoicedOrderMap =
        orders.stream()
            .map(Map.class::cast)
            .filter(order -> invoicedOrder.getOrderNumber().equals(order.get("orderNumber")))
            .findFirst()
            .orElseThrow();
    assertThat((Boolean) invoicedOrderMap.get("pendingCreditExposure")).isFalse();
  }

  @Test
  @DisplayName("Dealer portal excludes cash orders from pending-credit exposure totals")
  void dealerPortalOrders_excludesCashOrdersFromPendingExposure() {
    Company company = companyRepository.findByCodeIgnoreCase(COMPANY_CODE).orElseThrow();
    SalesOrder cashOrder =
        upsertOrder(
            company, dealerA, "SO-PORTAL-A-CASH", "PENDING_PRODUCTION", new BigDecimal("2600.00"));
    cashOrder.setPaymentMode("CASH");
    salesOrderRepository.saveAndFlush(cashOrder);

    HttpHeaders dealerHeaders = authHeaders(DEALER_A_EMAIL, PASSWORD);
    ResponseEntity<Map> dealerOrdersResponse =
        rest.exchange(
            "/api/v1/dealer-portal/orders",
            HttpMethod.GET,
            new HttpEntity<>(dealerHeaders),
            Map.class);

    assertThat(dealerOrdersResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    Map<?, ?> dealerOrdersData = (Map<?, ?>) dealerOrdersResponse.getBody().get("data");
    assertThat(((Number) dealerOrdersData.get("pendingOrderCount")).longValue()).isEqualTo(1L);
    assertThat(new BigDecimal(String.valueOf(dealerOrdersData.get("pendingOrderExposure"))))
        .isEqualByComparingTo("5000.00");
    List<?> orders = (List<?>) dealerOrdersData.get("orders");
    Map<?, ?> cashOrderMap =
        orders.stream()
            .map(Map.class::cast)
            .filter(order -> cashOrder.getOrderNumber().equals(order.get("orderNumber")))
            .findFirst()
            .orElseThrow();
    assertThat((Boolean) cashOrderMap.get("pendingCreditExposure")).isFalse();

    ResponseEntity<Map> dealerDashboardResponse =
        rest.exchange(
            "/api/v1/dealer-portal/dashboard",
            HttpMethod.GET,
            new HttpEntity<>(dealerHeaders),
            Map.class);
    assertThat(dealerDashboardResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    Map<?, ?> dealerDashboardData = (Map<?, ?>) dealerDashboardResponse.getBody().get("data");
    assertThat(((Number) dealerDashboardData.get("pendingOrderCount")).longValue()).isEqualTo(1L);
    assertThat(new BigDecimal(String.valueOf(dealerDashboardData.get("pendingOrderExposure"))))
        .isEqualByComparingTo("5000.00");
  }

  @Test
  @DisplayName("Dealer portal pending exposure follows sales-order invoice linkage")
  void dealerPortalOrders_pendingExposureUsesSalesOrderInvoiceLinkageEvenWhenInvoiceDealerDrifts() {
    Company company = companyRepository.findByCodeIgnoreCase(COMPANY_CODE).orElseThrow();
    SalesOrder linkedOrder =
        upsertOrder(
            company,
            dealerA,
            "SO-PORTAL-A-LINK-DRIFT",
            "PENDING_PRODUCTION",
            new BigDecimal("3600.00"));
    Invoice driftedInvoice = upsertInvoice(company, dealerB, "INV-PORTAL-A-LINK-DRIFT");
    driftedInvoice.setStatus("ISSUED");
    driftedInvoice.setSalesOrder(linkedOrder);
    driftedInvoice.setSubtotal(new BigDecimal("3000.00"));
    driftedInvoice.setTaxTotal(new BigDecimal("540.00"));
    driftedInvoice.setTotalAmount(new BigDecimal("3540.00"));
    driftedInvoice.setOutstandingAmount(new BigDecimal("900.00"));
    invoiceRepository.saveAndFlush(driftedInvoice);

    HttpHeaders headers = authHeaders(DEALER_A_EMAIL, PASSWORD);
    ResponseEntity<Map> ordersResponse =
        rest.exchange(
            "/api/v1/dealer-portal/orders", HttpMethod.GET, new HttpEntity<>(headers), Map.class);

    assertThat(ordersResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    Map<?, ?> ordersData = (Map<?, ?>) ordersResponse.getBody().get("data");
    assertThat(((Number) ordersData.get("pendingOrderCount")).longValue()).isEqualTo(1L);
    assertThat(new BigDecimal(String.valueOf(ordersData.get("pendingOrderExposure"))))
        .isEqualByComparingTo("5000.00");
    List<?> orders = (List<?>) ordersData.get("orders");
    Map<?, ?> linkedOrderMap =
        orders.stream()
            .map(Map.class::cast)
            .filter(order -> linkedOrder.getOrderNumber().equals(order.get("orderNumber")))
            .findFirst()
            .orElseThrow();
    assertThat((Boolean) linkedOrderMap.get("pendingCreditExposure")).isFalse();

    ResponseEntity<Map> dashboardResponse =
        rest.exchange(
            "/api/v1/dealer-portal/dashboard",
            HttpMethod.GET,
            new HttpEntity<>(headers),
            Map.class);
    assertThat(dashboardResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    Map<?, ?> dashboardData = (Map<?, ?>) dashboardResponse.getBody().get("data");
    assertThat(new BigDecimal(String.valueOf(dashboardData.get("pendingOrderExposure"))))
        .isEqualByComparingTo("5000.00");
    assertThat(((Number) dashboardData.get("pendingOrderCount")).longValue()).isEqualTo(1L);
  }

  @Test
  @DisplayName(
      "Pending exposure ignores draft-like invoice status tokens across dealer and admin views")
  void pendingExposure_ignoresDraftLikeInvoiceStatusTokensAcrossDealerAndAdminViews() {
    Company company = companyRepository.findByCodeIgnoreCase(COMPANY_CODE).orElseThrow();
    SalesOrder draftLinkedOrder =
        upsertOrder(
            company,
            dealerA,
            "SO-PORTAL-A-DRAFT-STATUS",
            "PENDING_PRODUCTION",
            new BigDecimal("3600.00"));
    Invoice draftLikeInvoice = upsertInvoice(company, dealerA, "INV-PORTAL-A-DRAFT-STATUS");
    draftLikeInvoice.setStatus(" draft ");
    draftLikeInvoice.setSalesOrder(draftLinkedOrder);
    draftLikeInvoice.setSubtotal(new BigDecimal("3000.00"));
    draftLikeInvoice.setTaxTotal(new BigDecimal("540.00"));
    draftLikeInvoice.setTotalAmount(new BigDecimal("3540.00"));
    draftLikeInvoice.setOutstandingAmount(new BigDecimal("900.00"));
    invoiceRepository.saveAndFlush(draftLikeInvoice);
    try {
      HttpHeaders dealerHeaders = authHeaders(DEALER_A_EMAIL, PASSWORD);
      ResponseEntity<Map> dealerOrdersResponse =
          rest.exchange(
              "/api/v1/dealer-portal/orders",
              HttpMethod.GET,
              new HttpEntity<>(dealerHeaders),
              Map.class);
      assertThat(dealerOrdersResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
      Map<?, ?> dealerOrdersData = (Map<?, ?>) dealerOrdersResponse.getBody().get("data");
      assertThat(((Number) dealerOrdersData.get("pendingOrderCount")).longValue()).isEqualTo(2L);
      assertThat(new BigDecimal(String.valueOf(dealerOrdersData.get("pendingOrderExposure"))))
          .isEqualByComparingTo("8600.00");
      List<?> orders = (List<?>) dealerOrdersData.get("orders");
      Map<?, ?> draftLinkedOrderMap =
          orders.stream()
              .map(Map.class::cast)
              .filter(order -> draftLinkedOrder.getOrderNumber().equals(order.get("orderNumber")))
              .findFirst()
              .orElseThrow();
      assertThat((Boolean) draftLinkedOrderMap.get("pendingCreditExposure")).isTrue();

      ResponseEntity<Map> dealerDashboardResponse =
          rest.exchange(
              "/api/v1/dealer-portal/dashboard",
              HttpMethod.GET,
              new HttpEntity<>(dealerHeaders),
              Map.class);
      assertThat(dealerDashboardResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
      Map<?, ?> dealerDashboardData = (Map<?, ?>) dealerDashboardResponse.getBody().get("data");
      assertThat(((Number) dealerDashboardData.get("pendingOrderCount")).longValue()).isEqualTo(2L);
      assertThat(new BigDecimal(String.valueOf(dealerDashboardData.get("pendingOrderExposure"))))
          .isEqualByComparingTo("8600.00");

      HttpHeaders adminHeaders = authHeaders(ADMIN_EMAIL, PASSWORD);
      ResponseEntity<Map> adminAgingResponse =
          rest.exchange(
              "/api/v1/portal/finance/aging?dealerId=" + dealerA.getId(),
              HttpMethod.GET,
              new HttpEntity<>(adminHeaders),
              Map.class);
      assertThat(adminAgingResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
      Map<?, ?> adminAgingData = (Map<?, ?>) adminAgingResponse.getBody().get("data");
      assertThat(((Number) adminAgingData.get("pendingOrderCount")).longValue()).isEqualTo(2L);
      assertThat(new BigDecimal(String.valueOf(adminAgingData.get("pendingOrderExposure"))))
          .isEqualByComparingTo("8600.00");
    } finally {
      if (draftLikeInvoice.getId() != null) {
        invoiceRepository.deleteById(draftLikeInvoice.getId());
        invoiceRepository.flush();
      }
      if (draftLinkedOrder.getId() != null) {
        salesOrderRepository.deleteById(draftLinkedOrder.getId());
        salesOrderRepository.flush();
      }
    }
  }

  @Test
  @DisplayName("Admin dealer aging pending summary matches dealer pending-order signals")
  void adminDealerAging_pendingSummaryMatchesDealerOrdersEvenWhenInvoiceDealerDrifts() {
    Company company = companyRepository.findByCodeIgnoreCase(COMPANY_CODE).orElseThrow();
    SalesOrder linkedOrder =
        upsertOrder(
            company,
            dealerA,
            "SO-PORTAL-A-ADMIN-DRIFT",
            "PENDING_PRODUCTION",
            new BigDecimal("3600.00"));
    Invoice driftedInvoice = upsertInvoice(company, dealerB, "INV-PORTAL-A-ADMIN-DRIFT");
    driftedInvoice.setStatus("ISSUED");
    driftedInvoice.setSalesOrder(linkedOrder);
    driftedInvoice.setSubtotal(new BigDecimal("3000.00"));
    driftedInvoice.setTaxTotal(new BigDecimal("540.00"));
    driftedInvoice.setTotalAmount(new BigDecimal("3540.00"));
    driftedInvoice.setOutstandingAmount(new BigDecimal("900.00"));
    invoiceRepository.saveAndFlush(driftedInvoice);

    HttpHeaders dealerHeaders = authHeaders(DEALER_A_EMAIL, PASSWORD);
    ResponseEntity<Map> dealerOrdersResponse =
        rest.exchange(
            "/api/v1/dealer-portal/orders",
            HttpMethod.GET,
            new HttpEntity<>(dealerHeaders),
            Map.class);
    assertThat(dealerOrdersResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    Map<?, ?> dealerOrdersData = (Map<?, ?>) dealerOrdersResponse.getBody().get("data");

    HttpHeaders adminHeaders = authHeaders(ADMIN_EMAIL, PASSWORD);
    ResponseEntity<Map> adminAgingResponse =
        rest.exchange(
            "/api/v1/portal/finance/aging?dealerId=" + dealerA.getId(),
            HttpMethod.GET,
            new HttpEntity<>(adminHeaders),
            Map.class);
    assertThat(adminAgingResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    Map<?, ?> adminAgingData = (Map<?, ?>) adminAgingResponse.getBody().get("data");

    assertThat(((Number) adminAgingData.get("pendingOrderCount")).longValue())
        .isEqualTo(((Number) dealerOrdersData.get("pendingOrderCount")).longValue());
    assertThat(new BigDecimal(String.valueOf(adminAgingData.get("pendingOrderExposure"))))
        .isEqualByComparingTo(
            new BigDecimal(String.valueOf(dealerOrdersData.get("pendingOrderExposure"))));
  }

  @Test
  @DisplayName(
      "Dealer/admin pending exposure parity excludes orders when any active invoice exists across"
          + " mixed invoice states")
  void dealerAdminPendingExposureParity_excludesOrderWhenAnyActiveInvoiceExistsAcrossMixedStates() {
    Company company = companyRepository.findByCodeIgnoreCase(COMPANY_CODE).orElseThrow();
    SalesOrder mixedInvoiceOrder =
        upsertOrder(
            company,
            dealerA,
            "SO-PORTAL-A-MIXED-INVOICE",
            "PENDING_PRODUCTION",
            new BigDecimal("2800.00"));
    Invoice reversedInvoice = upsertInvoice(company, dealerA, "INV-PORTAL-A-MIXED-REV");
    reversedInvoice.setStatus(" reversed ");
    reversedInvoice.setSalesOrder(mixedInvoiceOrder);
    reversedInvoice.setSubtotal(new BigDecimal("2400.00"));
    reversedInvoice.setTaxTotal(new BigDecimal("432.00"));
    reversedInvoice.setTotalAmount(new BigDecimal("2832.00"));
    reversedInvoice.setOutstandingAmount(BigDecimal.ZERO);
    invoiceRepository.saveAndFlush(reversedInvoice);

    Invoice activeDriftedInvoice = upsertInvoice(company, dealerB, "INV-PORTAL-A-MIXED-ACTIVE");
    activeDriftedInvoice.setStatus(" issued ");
    activeDriftedInvoice.setSalesOrder(mixedInvoiceOrder);
    activeDriftedInvoice.setSubtotal(new BigDecimal("2400.00"));
    activeDriftedInvoice.setTaxTotal(new BigDecimal("432.00"));
    activeDriftedInvoice.setTotalAmount(new BigDecimal("2832.00"));
    activeDriftedInvoice.setOutstandingAmount(new BigDecimal("600.00"));
    invoiceRepository.saveAndFlush(activeDriftedInvoice);

    try {
      HttpHeaders dealerHeaders = authHeaders(DEALER_A_EMAIL, PASSWORD);
      ResponseEntity<Map> dealerOrdersResponse =
          rest.exchange(
              "/api/v1/dealer-portal/orders",
              HttpMethod.GET,
              new HttpEntity<>(dealerHeaders),
              Map.class);
      assertThat(dealerOrdersResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
      Map<?, ?> dealerOrdersData = (Map<?, ?>) dealerOrdersResponse.getBody().get("data");
      assertThat(((Number) dealerOrdersData.get("pendingOrderCount")).longValue()).isEqualTo(1L);
      assertThat(new BigDecimal(String.valueOf(dealerOrdersData.get("pendingOrderExposure"))))
          .isEqualByComparingTo("5000.00");
      List<?> orders = (List<?>) dealerOrdersData.get("orders");
      Map<?, ?> mixedOrderMap =
          orders.stream()
              .map(Map.class::cast)
              .filter(order -> mixedInvoiceOrder.getOrderNumber().equals(order.get("orderNumber")))
              .findFirst()
              .orElseThrow();
      assertThat((Boolean) mixedOrderMap.get("pendingCreditExposure")).isFalse();

      ResponseEntity<Map> dealerDashboardResponse =
          rest.exchange(
              "/api/v1/dealer-portal/dashboard",
              HttpMethod.GET,
              new HttpEntity<>(dealerHeaders),
              Map.class);
      assertThat(dealerDashboardResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
      Map<?, ?> dealerDashboardData = (Map<?, ?>) dealerDashboardResponse.getBody().get("data");
      assertThat(((Number) dealerDashboardData.get("pendingOrderCount")).longValue()).isEqualTo(1L);
      assertThat(new BigDecimal(String.valueOf(dealerDashboardData.get("pendingOrderExposure"))))
          .isEqualByComparingTo("5000.00");

      HttpHeaders adminHeaders = authHeaders(ADMIN_EMAIL, PASSWORD);
      ResponseEntity<Map> adminAgingResponse =
          rest.exchange(
              "/api/v1/portal/finance/aging?dealerId=" + dealerA.getId(),
              HttpMethod.GET,
              new HttpEntity<>(adminHeaders),
              Map.class);
      assertThat(adminAgingResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
      Map<?, ?> adminAgingData = (Map<?, ?>) adminAgingResponse.getBody().get("data");
      assertThat(((Number) adminAgingData.get("pendingOrderCount")).longValue())
          .isEqualTo(((Number) dealerOrdersData.get("pendingOrderCount")).longValue());
      assertThat(new BigDecimal(String.valueOf(adminAgingData.get("pendingOrderExposure"))))
          .isEqualByComparingTo(
              new BigDecimal(String.valueOf(dealerOrdersData.get("pendingOrderExposure"))));
    } finally {
      if (activeDriftedInvoice.getId() != null) {
        invoiceRepository.deleteById(activeDriftedInvoice.getId());
      }
      if (reversedInvoice.getId() != null) {
        invoiceRepository.deleteById(reversedInvoice.getId());
      }
      invoiceRepository.flush();
      if (mixedInvoiceOrder.getId() != null) {
        salesOrderRepository.deleteById(mixedInvoiceOrder.getId());
        salesOrderRepository.flush();
      }
    }
  }

  @Test
  @DisplayName(
      "Dealer/admin pending exposure parity handles void and malformed sibling invoice status"
          + " tokens")
  void dealerAdminPendingExposureParity_handlesVoidAndMalformedSiblingInvoiceTokens() {
    Company company = companyRepository.findByCodeIgnoreCase(COMPANY_CODE).orElseThrow();
    HttpHeaders dealerHeaders = authHeaders(DEALER_A_EMAIL, PASSWORD);
    ResponseEntity<Map> baselineDealerOrdersResponse =
        rest.exchange(
            "/api/v1/dealer-portal/orders",
            HttpMethod.GET,
            new HttpEntity<>(dealerHeaders),
            Map.class);
    assertThat(baselineDealerOrdersResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    Map<?, ?> baselineDealerOrdersData =
        (Map<?, ?>) baselineDealerOrdersResponse.getBody().get("data");
    long baselinePendingCount =
        ((Number) baselineDealerOrdersData.get("pendingOrderCount")).longValue();
    BigDecimal baselinePendingExposure =
        new BigDecimal(String.valueOf(baselineDealerOrdersData.get("pendingOrderExposure")));
    assertThat(baselinePendingCount).isEqualTo(1L);
    assertThat(baselinePendingExposure).isEqualByComparingTo("5000.00");

    SalesOrder voidOnlyOrder =
        upsertOrder(
            company,
            dealerA,
            "SO-PORTAL-A-VOID-ONLY",
            "PENDING_PRODUCTION",
            new BigDecimal("2600.00"));
    Invoice voidOnlyInvoice = upsertInvoice(company, dealerA, "INV-PORTAL-A-VOID-ONLY");
    voidOnlyInvoice.setStatus(" void ");
    voidOnlyInvoice.setSalesOrder(voidOnlyOrder);
    voidOnlyInvoice.setSubtotal(new BigDecimal("2200.00"));
    voidOnlyInvoice.setTaxTotal(new BigDecimal("396.00"));
    voidOnlyInvoice.setTotalAmount(new BigDecimal("2596.00"));
    voidOnlyInvoice.setOutstandingAmount(BigDecimal.ZERO);
    invoiceRepository.saveAndFlush(voidOnlyInvoice);

    SalesOrder mixedMalformedOrder =
        upsertOrder(
            company,
            dealerA,
            "SO-PORTAL-A-VOID-MALFORMED",
            "PENDING_PRODUCTION",
            new BigDecimal("3100.00"));
    Invoice voidSiblingInvoice = upsertInvoice(company, dealerA, "INV-PORTAL-A-VOID-MALFORMED");
    voidSiblingInvoice.setStatus(" void ");
    voidSiblingInvoice.setSalesOrder(mixedMalformedOrder);
    voidSiblingInvoice.setSubtotal(new BigDecimal("2600.00"));
    voidSiblingInvoice.setTaxTotal(new BigDecimal("468.00"));
    voidSiblingInvoice.setTotalAmount(new BigDecimal("3068.00"));
    voidSiblingInvoice.setOutstandingAmount(BigDecimal.ZERO);
    invoiceRepository.saveAndFlush(voidSiblingInvoice);

    Invoice malformedActiveInvoice =
        upsertInvoice(company, dealerB, "INV-PORTAL-A-MALFORMED-ACTIVE");
    malformedActiveInvoice.setStatus(" ??? ");
    malformedActiveInvoice.setSalesOrder(mixedMalformedOrder);
    malformedActiveInvoice.setSubtotal(new BigDecimal("2600.00"));
    malformedActiveInvoice.setTaxTotal(new BigDecimal("468.00"));
    malformedActiveInvoice.setTotalAmount(new BigDecimal("3068.00"));
    malformedActiveInvoice.setOutstandingAmount(new BigDecimal("700.00"));
    invoiceRepository.saveAndFlush(malformedActiveInvoice);

    try {
      ResponseEntity<Map> dealerOrdersResponse =
          rest.exchange(
              "/api/v1/dealer-portal/orders",
              HttpMethod.GET,
              new HttpEntity<>(dealerHeaders),
              Map.class);
      assertThat(dealerOrdersResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
      Map<?, ?> dealerOrdersData = (Map<?, ?>) dealerOrdersResponse.getBody().get("data");
      assertThat(((Number) dealerOrdersData.get("pendingOrderCount")).longValue())
          .isEqualTo(baselinePendingCount + 1L);
      assertThat(new BigDecimal(String.valueOf(dealerOrdersData.get("pendingOrderExposure"))))
          .isEqualByComparingTo(baselinePendingExposure.add(new BigDecimal("2600.00")));
      List<?> orders = (List<?>) dealerOrdersData.get("orders");
      Map<?, ?> voidOnlyOrderMap =
          orders.stream()
              .map(Map.class::cast)
              .filter(order -> voidOnlyOrder.getOrderNumber().equals(order.get("orderNumber")))
              .findFirst()
              .orElseThrow();
      assertThat((Boolean) voidOnlyOrderMap.get("pendingCreditExposure")).isTrue();
      Map<?, ?> mixedMalformedOrderMap =
          orders.stream()
              .map(Map.class::cast)
              .filter(
                  order -> mixedMalformedOrder.getOrderNumber().equals(order.get("orderNumber")))
              .findFirst()
              .orElseThrow();
      assertThat((Boolean) mixedMalformedOrderMap.get("pendingCreditExposure")).isFalse();

      ResponseEntity<Map> dealerDashboardResponse =
          rest.exchange(
              "/api/v1/dealer-portal/dashboard",
              HttpMethod.GET,
              new HttpEntity<>(dealerHeaders),
              Map.class);
      assertThat(dealerDashboardResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
      Map<?, ?> dealerDashboardData = (Map<?, ?>) dealerDashboardResponse.getBody().get("data");
      assertThat(((Number) dealerDashboardData.get("pendingOrderCount")).longValue())
          .isEqualTo(((Number) dealerOrdersData.get("pendingOrderCount")).longValue());
      assertThat(new BigDecimal(String.valueOf(dealerDashboardData.get("pendingOrderExposure"))))
          .isEqualByComparingTo(
              new BigDecimal(String.valueOf(dealerOrdersData.get("pendingOrderExposure"))));

      HttpHeaders adminHeaders = authHeaders(ADMIN_EMAIL, PASSWORD);
      ResponseEntity<Map> adminAgingResponse =
          rest.exchange(
              "/api/v1/portal/finance/aging?dealerId=" + dealerA.getId(),
              HttpMethod.GET,
              new HttpEntity<>(adminHeaders),
              Map.class);
      assertThat(adminAgingResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
      Map<?, ?> adminAgingData = (Map<?, ?>) adminAgingResponse.getBody().get("data");
      assertThat(((Number) adminAgingData.get("pendingOrderCount")).longValue())
          .isEqualTo(((Number) dealerOrdersData.get("pendingOrderCount")).longValue());
      assertThat(new BigDecimal(String.valueOf(adminAgingData.get("pendingOrderExposure"))))
          .isEqualByComparingTo(
              new BigDecimal(String.valueOf(dealerOrdersData.get("pendingOrderExposure"))));
    } finally {
      if (malformedActiveInvoice.getId() != null) {
        invoiceRepository.deleteById(malformedActiveInvoice.getId());
      }
      if (voidSiblingInvoice.getId() != null) {
        invoiceRepository.deleteById(voidSiblingInvoice.getId());
      }
      if (voidOnlyInvoice.getId() != null) {
        invoiceRepository.deleteById(voidOnlyInvoice.getId());
      }
      invoiceRepository.flush();
      if (mixedMalformedOrder.getId() != null) {
        salesOrderRepository.deleteById(mixedMalformedOrder.getId());
      }
      if (voidOnlyOrder.getId() != null) {
        salesOrderRepository.deleteById(voidOnlyOrder.getId());
      }
      salesOrderRepository.flush();
    }
  }

  private HttpHeaders authHeaders(String email, String password) {
    Map<String, Object> payload =
        Map.of(
            "email", email,
            "password", password,
            "companyCode", COMPANY_CODE);
    ResponseEntity<Map> login = rest.postForEntity("/api/v1/auth/login", payload, Map.class);
    assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);

    String token = (String) login.getBody().get("accessToken");
    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth(token);
    headers.set("X-Company-Code", COMPANY_CODE);
    return headers;
  }

  private long asLong(Object value) {
    return ((Number) value).longValue();
  }

  private BigDecimal asBigDecimal(Object value) {
    return new BigDecimal(String.valueOf(value));
  }

  private Dealer upsertDealer(Company company, String code, String name, UserAccount portalUser) {
    Dealer dealer =
        dealerRepository.findByCompanyAndCodeIgnoreCase(company, code).orElseGet(Dealer::new);
    dealer.setCompany(company);
    dealer.setCode(code);
    dealer.setName(name);
    dealer.setStatus("ACTIVE");
    dealer.setCompanyName(name + " Pvt Ltd");
    dealer.setEmail(portalUser.getEmail());
    dealer.setCreditLimit(new BigDecimal("100000"));
    dealer.setPortalUser(portalUser);
    return dealerRepository.saveAndFlush(dealer);
  }

  private Invoice upsertInvoice(Company company, Dealer dealer, String invoiceNumber) {
    Invoice invoice =
        invoiceRepository.findByCompanyAndDealerOrderByIssueDateDesc(company, dealer).stream()
            .filter(existing -> invoiceNumber.equals(existing.getInvoiceNumber()))
            .findFirst()
            .orElseGet(Invoice::new);
    invoice.setCompany(company);
    invoice.setDealer(dealer);
    invoice.setInvoiceNumber(invoiceNumber);
    invoice.setStatus("OPEN");
    invoice.setIssueDate(LocalDate.now().minusDays(2));
    invoice.setDueDate(LocalDate.now().plusDays(15));
    invoice.setSubtotal(new BigDecimal("1000.00"));
    invoice.setTaxTotal(new BigDecimal("180.00"));
    invoice.setTotalAmount(new BigDecimal("1180.00"));
    invoice.setOutstandingAmount(new BigDecimal("1180.00"));
    invoice.setCurrency("INR");
    return invoiceRepository.saveAndFlush(invoice);
  }

  private SalesOrder upsertOrder(
      Company company, Dealer dealer, String orderNumber, String status, BigDecimal totalAmount) {
    SalesOrder order =
        salesOrderRepository.findByCompanyAndDealerOrderByCreatedAtDesc(company, dealer).stream()
            .filter(existing -> orderNumber.equals(existing.getOrderNumber()))
            .findFirst()
            .orElseGet(SalesOrder::new);
    order.setCompany(company);
    order.setDealer(dealer);
    order.setOrderNumber(orderNumber);
    order.setStatus(status);
    order.setCurrency("INR");
    order.setNotes("portal-seeded");
    order.setSubtotalAmount(totalAmount);
    order.setGstTotal(BigDecimal.ZERO);
    order.setGstTreatment("NONE");
    order.setGstInclusive(false);
    order.setGstRate(BigDecimal.ZERO);
    order.setGstRoundingAdjustment(BigDecimal.ZERO);
    order.setTotalAmount(totalAmount);
    return salesOrderRepository.saveAndFlush(order);
  }
}
