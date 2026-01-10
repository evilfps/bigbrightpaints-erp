package com.bigbrightpaints.erp.e2e.accounting;

import com.bigbrightpaints.erp.core.security.CompanyContextHolder;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountType;
import com.bigbrightpaints.erp.modules.accounting.dto.AccountRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryRequest;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingService;
import com.bigbrightpaints.erp.modules.accounting.service.ReconciliationService;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.modules.reports.dto.InventoryValuationDto;
import com.bigbrightpaints.erp.modules.reports.dto.ReconciliationSummaryDto;
import com.bigbrightpaints.erp.modules.reports.service.ReportService;
import com.bigbrightpaints.erp.test.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

public class ReconciliationControlsIT extends AbstractIntegrationTest {

    private static final String COMPANY_CODE = "RECON-E2E";
    private static final String ADMIN_EMAIL = "recon-admin@bbp.com";
    private static final String ADMIN_PASSWORD = "recon123";

    @Autowired
    private ReportService reportService;

    @Autowired
    private ReconciliationService reconciliationService;

    @Autowired
    private AccountingService accountingService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private CompanyRepository companyRepository;

    @Autowired
    private TestRestTemplate rest;

    private HttpHeaders headers;

    @BeforeEach
    void setUp() {
        CompanyContextHolder.setCompanyId(COMPANY_CODE);
        dataSeeder.ensureCompany(COMPANY_CODE, "Reconciliation Co");
        dataSeeder.ensureUser(ADMIN_EMAIL, ADMIN_PASSWORD, "Recon Admin", COMPANY_CODE,
                List.of("ROLE_ADMIN", "ROLE_ACCOUNTING"));
        headers = authHeaders();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        CompanyContextHolder.clear();
    }

    @Test
    void inventoryReconciliationReturnsZeroVarianceAfterBalancingLedger() {
        Company company = companyRepository.findByCodeIgnoreCase(COMPANY_CODE).orElseThrow();
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken("tester", "n/a"));

        InventoryValuationDto valuation = reportService.inventoryValuation();
        BigDecimal totalValue = valuation.totalValue() == null ? BigDecimal.ZERO : valuation.totalValue();

        if (totalValue.compareTo(BigDecimal.ZERO) > 0) {
            Account inventory = accountRepository.findByCompanyAndCodeIgnoreCase(company, "INV").orElseThrow();
            Account offset = accountRepository.findByCompanyAndCodeIgnoreCase(company, "OPEN-BAL")
                    .orElseGet(() -> accountRepository.findById(
                                    accountingService.createAccount(new AccountRequest(
                                            "OPEN-BAL", "Opening Balance", AccountType.EQUITY)).id())
                            .orElseThrow());

            accountingService.createJournalEntry(new JournalEntryRequest(
                    "OPEN-INV-" + COMPANY_CODE + "-" + System.nanoTime(),
                    LocalDate.now(),
                    "Seed inventory balance",
                    null,
                    null,
                    false,
                    List.of(
                            new JournalEntryRequest.JournalLineRequest(
                                    inventory.getId(), "Opening inventory", totalValue, BigDecimal.ZERO),
                            new JournalEntryRequest.JournalLineRequest(
                                    offset.getId(), "Offset", BigDecimal.ZERO, totalValue)
                    )
            ));
        }

        ReconciliationSummaryDto summary = reportService.inventoryReconciliation();
        assertThat(summary.variance()).as("inventory variance").isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(summary.physicalInventoryValue())
                .as("inventory valuation matches ledger")
                .isEqualByComparingTo(summary.ledgerInventoryBalance());

        ResponseEntity<Map> valuationResp = rest.exchange("/api/v1/reports/inventory-valuation",
                HttpMethod.GET, new HttpEntity<>(headers), Map.class);
        assertThat(valuationResp.getStatusCode().is2xxSuccessful()).isTrue();
        System.out.println("M2 API evidence inventory valuation: " + valuationResp.getBody());
        ResponseEntity<Map> reconResp = rest.exchange("/api/v1/reports/inventory-reconciliation",
                HttpMethod.GET, new HttpEntity<>(headers), Map.class);
        assertThat(reconResp.getStatusCode().is2xxSuccessful()).isTrue();
        System.out.println("M2 API evidence inventory reconciliation: " + reconResp.getBody());
    }

    @Test
    void arAndApReconciliationsReturnZeroVarianceForSeededCompany() {
        ReconciliationService.ReconciliationResult ar = reconciliationService.reconcileArWithDealerLedger();
        assertThat(ar.isReconciled()).isTrue();
        assertThat(ar.variance()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(ar.arAccountCount()).isPositive();
        assertThat(ar.dealerCount()).isPositive();

        ReconciliationService.SupplierReconciliationResult ap = reconciliationService.reconcileApWithSupplierLedger();
        assertThat(ap.isReconciled()).isTrue();
        assertThat(ap.variance()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(ap.apAccountCount()).isPositive();
        assertThat(ap.supplierCount()).isPositive();
    }

    private HttpHeaders authHeaders() {
        Map<String, Object> req = Map.of(
                "email", ADMIN_EMAIL,
                "password", ADMIN_PASSWORD,
                "companyCode", COMPANY_CODE
        );
        ResponseEntity<Map> login = rest.postForEntity("/api/v1/auth/login", req, Map.class);
        String token = (String) login.getBody().get("accessToken");
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        h.setContentType(MediaType.APPLICATION_JSON);
        h.set("X-Company-Id", COMPANY_CODE);
        return h;
    }
}
