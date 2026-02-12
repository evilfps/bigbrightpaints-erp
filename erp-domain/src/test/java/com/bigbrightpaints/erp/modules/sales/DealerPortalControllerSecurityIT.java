package com.bigbrightpaints.erp.modules.sales;

import com.bigbrightpaints.erp.modules.auth.domain.UserAccount;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.modules.invoice.domain.Invoice;
import com.bigbrightpaints.erp.modules.invoice.domain.InvoiceRepository;
import com.bigbrightpaints.erp.modules.sales.domain.Dealer;
import com.bigbrightpaints.erp.modules.sales.domain.DealerRepository;
import com.bigbrightpaints.erp.test.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Dealer Portal Security")
class DealerPortalControllerSecurityIT extends AbstractIntegrationTest {

    private static final String COMPANY_CODE = "DEALER-PORTAL-SEC";
    private static final String DEALER_A_EMAIL = "portal-dealer-a@bbp.com";
    private static final String DEALER_B_EMAIL = "portal-dealer-b@bbp.com";
    private static final String DEALER_ORPHAN_EMAIL = "portal-dealer-orphan@bbp.com";
    private static final String ADMIN_EMAIL = "portal-admin@bbp.com";
    private static final String PASSWORD = "DealerPass123!";

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private CompanyRepository companyRepository;

    @Autowired
    private DealerRepository dealerRepository;

    @Autowired
    private InvoiceRepository invoiceRepository;

    private Dealer dealerA;
    private Dealer dealerB;
    private Invoice invoiceA;
    private Invoice invoiceB;

    @BeforeEach
    void setup() {
        UserAccount dealerAUser = dataSeeder.ensureUser(
                DEALER_A_EMAIL, PASSWORD, "Dealer A User", COMPANY_CODE, List.of("ROLE_DEALER"));
        UserAccount dealerBUser = dataSeeder.ensureUser(
                DEALER_B_EMAIL, PASSWORD, "Dealer B User", COMPANY_CODE, List.of("ROLE_DEALER"));
        dataSeeder.ensureUser(
                DEALER_ORPHAN_EMAIL, PASSWORD, "Dealer Orphan User", COMPANY_CODE, List.of("ROLE_DEALER"));
        dataSeeder.ensureUser(
                ADMIN_EMAIL, PASSWORD, "Admin User", COMPANY_CODE, List.of("ROLE_ADMIN"));

        Company company = companyRepository.findByCodeIgnoreCase(COMPANY_CODE).orElseThrow();
        dealerA = upsertDealer(company, "PORTAL-A", "Portal Dealer A", dealerAUser);
        dealerB = upsertDealer(company, "PORTAL-B", "Portal Dealer B", dealerBUser);
        invoiceA = upsertInvoice(company, dealerA, "INV-PORTAL-A");
        invoiceB = upsertInvoice(company, dealerB, "INV-PORTAL-B");
    }

    @Test
    @DisplayName("Dealer portal invoices are strictly scoped to authenticated dealer")
    void dealerPortalInvoices_areScopedToCurrentDealer() {
        HttpHeaders headers = authHeaders(DEALER_A_EMAIL, PASSWORD);
        ResponseEntity<Map> response = rest.exchange(
                "/api/v1/dealer-portal/invoices",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                Map.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> data = (Map<?, ?>) response.getBody().get("data");
        assertThat(asLong(data.get("dealerId"))).isEqualTo(dealerA.getId());
        assertThat(data.get("invoiceCount")).isEqualTo(1);
        List<?> invoices = (List<?>) data.get("invoices");
        assertThat(invoices).hasSize(1);
        Map<?, ?> invoice = (Map<?, ?>) invoices.getFirst();
        assertThat(asLong(invoice.get("id"))).isEqualTo(invoiceA.getId());
        assertThat(asLong(invoice.get("id"))).isNotEqualTo(invoiceB.getId());
    }

    @Test
    @DisplayName("Dealer portal aging is strictly scoped to authenticated dealer")
    void dealerPortalAging_isScopedToCurrentDealer() {
        HttpHeaders headers = authHeaders(DEALER_A_EMAIL, PASSWORD);
        ResponseEntity<Map> response = rest.exchange(
                "/api/v1/dealer-portal/aging",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                Map.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> data = (Map<?, ?>) response.getBody().get("data");
        assertThat(asLong(data.get("dealerId"))).isEqualTo(dealerA.getId());
        assertThat(new BigDecimal(String.valueOf(data.get("totalOutstanding"))))
                .isEqualByComparingTo("1180.00");
    }

    @Test
    @DisplayName("Dealer portal invoice PDF endpoint rejects cross-dealer invoice enumeration")
    void dealerPortalInvoicePdf_rejectsCrossDealerInvoiceId() {
        HttpHeaders headers = authHeaders(DEALER_A_EMAIL, PASSWORD);
        ResponseEntity<byte[]> response = rest.exchange(
                "/api/v1/dealer-portal/invoices/" + invoiceB.getId() + "/pdf",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                byte[].class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("Dealer portal rejects token/header company mismatch")
    void dealerPortalRejectsCompanyHeaderMismatch() {
        HttpHeaders headers = authHeaders(DEALER_A_EMAIL, PASSWORD);
        headers.set("X-Company-Code", "EVIL");
        ResponseEntity<Map> response = rest.exchange(
                "/api/v1/dealer-portal/dashboard",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                Map.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("Non dealer role cannot access dealer portal endpoints")
    void nonDealerRoleCannotAccessDealerPortal() {
        HttpHeaders headers = authHeaders(ADMIN_EMAIL, PASSWORD);
        ResponseEntity<Map> response = rest.exchange(
                "/api/v1/dealer-portal/dashboard",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                Map.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("Dealer principal without dealer mapping is denied with forbidden status")
    void dealerWithoutMappingIsForbidden() {
        HttpHeaders headers = authHeaders(DEALER_ORPHAN_EMAIL, PASSWORD);
        ResponseEntity<Map> response = rest.exchange(
                "/api/v1/dealer-portal/dashboard",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                Map.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    private HttpHeaders authHeaders(String email, String password) {
        Map<String, Object> payload = Map.of(
                "email", email,
                "password", password,
                "companyCode", COMPANY_CODE
        );
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

    private Dealer upsertDealer(Company company, String code, String name, UserAccount portalUser) {
        Dealer dealer = dealerRepository.findByCompanyAndCodeIgnoreCase(company, code).orElseGet(Dealer::new);
        dealer.setCompany(company);
        dealer.setCode(code);
        dealer.setName(name);
        dealer.setCompanyName(name + " Pvt Ltd");
        dealer.setEmail(portalUser.getEmail());
        dealer.setCreditLimit(new BigDecimal("100000"));
        dealer.setPortalUser(portalUser);
        return dealerRepository.saveAndFlush(dealer);
    }

    private Invoice upsertInvoice(Company company, Dealer dealer, String invoiceNumber) {
        Invoice invoice = invoiceRepository.findByCompanyAndDealerOrderByIssueDateDesc(company, dealer).stream()
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
}
