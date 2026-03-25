package com.bigbrightpaints.erp.codered;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import com.bigbrightpaints.erp.core.security.CompanyContextHolder;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryRepository;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryMovementRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryReference;
import com.bigbrightpaints.erp.modules.inventory.domain.PackagingSlip;
import com.bigbrightpaints.erp.modules.inventory.domain.PackagingSlipRepository;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrder;
import com.bigbrightpaints.erp.modules.sales.util.SalesOrderReference;
import com.bigbrightpaints.erp.test.AbstractIntegrationTest;

class CR_DispatchOrderLookupReadOnlyIT extends AbstractIntegrationTest {

  private static final String COMPANY_CODE = "CR-DISPATCH-LOOKUP";
  private static final String USER_EMAIL = "dispatch.lookup@test.com";
  private static final String USER_PASSWORD = "dispatch123";

  @Autowired private TestRestTemplate rest;
  @Autowired private CompanyRepository companyRepository;
  @Autowired private PackagingSlipRepository packagingSlipRepository;
  @Autowired private InventoryMovementRepository inventoryMovementRepository;
  @Autowired private JournalEntryRepository journalEntryRepository;

  @BeforeEach
  void seedUser() {
    dataSeeder.ensureUser(
        USER_EMAIL, USER_PASSWORD, "Dispatch Lookup", COMPANY_CODE, List.of("ROLE_FACTORY"));
  }

  @AfterEach
  void clearCompanyContext() {
    CompanyContextHolder.clear();
  }

  @Test
  void getByOrder_withoutSlip_isReadOnlyAndFailsClosed() {
    SalesOrder order =
        dataSeeder.ensureSalesOrder(COMPANY_CODE, "SO-" + shortId(), new BigDecimal("100.00"));
    Company company = companyRepository.findByCodeIgnoreCase(COMPANY_CODE).orElseThrow();

    HttpHeaders headers = authHeaders(loginToken());
    ResponseEntity<Map> response =
        rest.exchange(
            "/api/v1/dispatch/order/" + order.getId(),
            HttpMethod.GET,
            new HttpEntity<>(headers),
            Map.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(packagingSlipRepository.findAllByCompanyAndSalesOrderId(company, order.getId()))
        .isEmpty();
    assertThat(
            inventoryMovementRepository
                .findByFinishedGood_CompanyAndReferenceTypeAndReferenceIdOrderByCreatedAtAsc(
                    company, InventoryReference.SALES_ORDER, order.getId().toString()))
        .isEmpty();
    assertThat(
            journalEntryRepository.findByCompanyAndReferenceNumber(
                company, SalesOrderReference.invoiceReference(order.getOrderNumber())))
        .isEmpty();
  }

  @Test
  void getByOrder_withMultipleSlips_failsClosedDeterministically() {
    SalesOrder order =
        dataSeeder.ensureSalesOrder(COMPANY_CODE, "SO-" + shortId(), new BigDecimal("250.00"));
    Company company = companyRepository.findByCodeIgnoreCase(COMPANY_CODE).orElseThrow();

    PackagingSlip cancelledA = buildSlip(company, order, "PS-" + shortId());
    cancelledA.setStatus("CANCELLED");
    PackagingSlip cancelledB = buildSlip(company, order, "PS-" + shortId());
    cancelledB.setStatus("CANCELLED");
    packagingSlipRepository.save(cancelledA);
    packagingSlipRepository.save(cancelledB);

    HttpHeaders headers = authHeaders(loginToken());
    ResponseEntity<Map> response =
        rest.exchange(
            "/api/v1/dispatch/order/" + order.getId(),
            HttpMethod.GET,
            new HttpEntity<>(headers),
            Map.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(response.getBody()).containsEntry("success", false);
    assertThat(String.valueOf(response.getBody().get("message")))
        .contains("Multiple packaging slips")
        .contains("packingSlipId");
  }

  private PackagingSlip buildSlip(Company company, SalesOrder order, String slipNumber) {
    PackagingSlip slip = new PackagingSlip();
    slip.setCompany(company);
    slip.setSalesOrder(order);
    slip.setSlipNumber(slipNumber);
    slip.setStatus("PENDING");
    return slip;
  }

  private String loginToken() {
    Map<String, Object> req =
        Map.of(
            "email", USER_EMAIL,
            "password", USER_PASSWORD,
            "companyCode", COMPANY_CODE);
    return (String)
        rest.postForEntity("/api/v1/auth/login", req, Map.class).getBody().get("accessToken");
  }

  private HttpHeaders authHeaders(String token) {
    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth(token);
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.add("X-Company-Code", COMPANY_CODE);
    return headers;
  }

  private String shortId() {
    return UUID.randomUUID().toString().substring(0, 8);
  }
}
