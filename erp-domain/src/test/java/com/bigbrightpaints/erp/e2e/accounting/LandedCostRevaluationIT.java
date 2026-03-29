package com.bigbrightpaints.erp.e2e.accounting;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountType;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalLine;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.modules.purchasing.domain.RawMaterialPurchase;
import com.bigbrightpaints.erp.modules.purchasing.domain.RawMaterialPurchaseRepository;
import com.bigbrightpaints.erp.modules.purchasing.domain.Supplier;
import com.bigbrightpaints.erp.modules.purchasing.domain.SupplierRepository;
import com.bigbrightpaints.erp.test.AbstractIntegrationTest;

@DisplayName("E2E: Landed cost, revaluation, audit digest")
public class LandedCostRevaluationIT extends AbstractIntegrationTest {

  private static final String COMPANY = "VALWIP";
  private static final String ADMIN_EMAIL = "valuation@bbp.com";
  private static final String ADMIN_PASSWORD = "val123";

  @Autowired private TestRestTemplate rest;
  @Autowired private CompanyRepository companyRepository;
  @Autowired private AccountRepository accountRepository;
  @Autowired private JournalEntryRepository journalEntryRepository;
  @Autowired private SupplierRepository supplierRepository;
  @Autowired private RawMaterialPurchaseRepository rawMaterialPurchaseRepository;

  private HttpHeaders headers;
  private Company company;
  private Account inventory;
  private Account offset;
  private Account reval;
  private Account payable;

  @BeforeEach
  void setup() {
    dataSeeder.ensureUser(
        ADMIN_EMAIL,
        ADMIN_PASSWORD,
        "Valuation Admin",
        COMPANY,
        java.util.List.of("ROLE_ADMIN", "ROLE_ACCOUNTING"));
    company = companyRepository.findByCodeIgnoreCase(COMPANY).orElseThrow();
    inventory = ensureAccount("INV-VAL", "Inventory", AccountType.ASSET);
    offset = ensureAccount("LC-OFF", "Landed Cost Offset", AccountType.LIABILITY);
    reval = ensureAccount("REVAL", "Revaluation Reserve", AccountType.EQUITY);
    payable = ensureAccount("AP-VAL", "Accounts Payable", AccountType.LIABILITY);
    headers = authHeaders();
  }

  @Test
  void landedCost_and_revaluation_and_digest() {
    RawMaterialPurchase purchase = ensurePurchase();
    String revaluationReference = "REVAL-" + UUID.randomUUID();

    Map<String, Object> landedReq =
        Map.of(
            "rawMaterialPurchaseId", purchase.getId(),
            "amount", new BigDecimal("250.00"),
            "inventoryAccountId", inventory.getId(),
            "offsetAccountId", offset.getId(),
            "memo", "Freight");
    ResponseEntity<Map> landedResp =
        rest.postForEntity(
            "/api/v1/accounting/inventory/landed-cost",
            new org.springframework.http.HttpEntity<>(landedReq, headers),
            Map.class);
    if (!landedResp.getStatusCode().is2xxSuccessful()) {
      System.out.println("Landed cost response: " + landedResp);
      System.out.println("Body: " + landedResp.getBody());
    }
    assertThat(landedResp.getStatusCode()).isEqualTo(HttpStatus.OK);

    Map<String, Object> revalReq =
        Map.of(
            "inventoryAccountId",
            inventory.getId(),
            "revaluationAccountId",
            reval.getId(),
            "deltaAmount",
            new BigDecimal("-50.00"),
            "referenceNumber",
            revaluationReference,
            "memo",
            "Manual reval");
    ResponseEntity<Map> revalResp =
        rest.postForEntity(
            "/api/v1/accounting/inventory/revaluation",
            new org.springframework.http.HttpEntity<>(revalReq, headers),
            Map.class);
    assertThat(revalResp.getStatusCode()).isEqualTo(HttpStatus.OK);
    JournalEntry revaluationEntry =
        journalEntryRepository
            .findByCompanyAndReferenceNumber(company, revaluationReference)
            .orElseThrow();
    assertThat(revaluationEntry.getLines()).hasSize(2);
    JournalLine inventoryLine =
        revaluationEntry.getLines().stream()
            .filter(line -> line.getAccount().getId().equals(inventory.getId()))
            .findFirst()
            .orElseThrow();
    JournalLine revaluationLine =
        revaluationEntry.getLines().stream()
            .filter(line -> line.getAccount().getId().equals(reval.getId()))
            .findFirst()
            .orElseThrow();
    assertThat(inventoryLine.getDebit()).isEqualByComparingTo("0.00");
    assertThat(inventoryLine.getCredit()).isEqualByComparingTo("50.00");
    assertThat(revaluationLine.getDebit()).isEqualByComparingTo("50.00");
    assertThat(revaluationLine.getCredit()).isEqualByComparingTo("0.00");

    ResponseEntity<Map> digest =
        rest.exchange(
            "/api/v1/accounting/audit/digest",
            org.springframework.http.HttpMethod.GET,
            new org.springframework.http.HttpEntity<>(headers),
            Map.class);
    assertThat(digest.getStatusCode()).isEqualTo(HttpStatus.OK);
    Map<String, ?> body = digest.getBody();
    assertThat(body).isNotNull();
  }

  private RawMaterialPurchase ensurePurchase() {
    Supplier supplier =
        supplierRepository
            .findByCompanyAndCodeIgnoreCase(company, "VSUP")
            .orElseGet(
                () -> {
                  Supplier s = new Supplier();
                  s.setCompany(company);
                  s.setName("Val Supplier");
                  s.setCode("VSUP");
                  s.setEmail("val-supplier@bbp.com");
                  s.setPayableAccount(payable);
                  return supplierRepository.save(s);
                });
    RawMaterialPurchase purchase = new RawMaterialPurchase();
    purchase.setCompany(company);
    purchase.setSupplier(supplier);
    purchase.setInvoiceNumber("VAL-" + UUID.randomUUID());
    purchase.setInvoiceDate(LocalDate.now());
    purchase.setTotalAmount(new BigDecimal("1000"));
    purchase.setStatus("POSTED");
    return rawMaterialPurchaseRepository.save(purchase);
  }

  private Account ensureAccount(String code, String name, AccountType type) {
    return accountRepository
        .findByCompanyAndCodeIgnoreCase(company, code)
        .orElseGet(
            () -> {
              Account a = new Account();
              a.setCompany(company);
              a.setCode(code);
              a.setName(name);
              a.setType(type);
              a.setBalance(BigDecimal.ZERO);
              return accountRepository.save(a);
            });
  }

  private HttpHeaders authHeaders() {
    Map<String, Object> req =
        Map.of(
            "email", ADMIN_EMAIL,
            "password", ADMIN_PASSWORD,
            "companyCode", COMPANY);
    ResponseEntity<Map> login = rest.postForEntity("/api/v1/auth/login", req, Map.class);
    assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
    String token = (String) login.getBody().get("accessToken");
    HttpHeaders h = new HttpHeaders();
    h.setBearerAuth(token);
    h.setContentType(MediaType.APPLICATION_JSON);
    h.set("X-Company-Code", COMPANY);
    return h;
  }
}
