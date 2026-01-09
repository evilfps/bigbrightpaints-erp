package com.bigbrightpaints.erp.onboarding;

import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountType;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryMovement;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryMovementRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryReference;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterial;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialMovement;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialMovementRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGood;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodRepository;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.modules.production.domain.ProductionBrandRepository;
import com.bigbrightpaints.erp.modules.production.domain.ProductionProductRepository;
import com.bigbrightpaints.erp.modules.purchasing.domain.SupplierRepository;
import com.bigbrightpaints.erp.modules.sales.domain.DealerRepository;
import com.bigbrightpaints.erp.test.AbstractIntegrationTest;
import com.bigbrightpaints.erp.test.support.CanonicalErpDataset;
import com.bigbrightpaints.erp.test.support.CanonicalErpDatasetBuilder;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
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

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Onboarding: Opening Stock and Defaults")
public class OnboardingFlowIT extends AbstractIntegrationTest {

    private static final String PASSWORD = "test123";

    @Autowired private TestRestTemplate rest;
    @Autowired private CompanyRepository companyRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private DealerRepository dealerRepository;
    @Autowired private SupplierRepository supplierRepository;
    @Autowired private ProductionBrandRepository brandRepository;
    @Autowired private ProductionProductRepository productRepository;
    @Autowired private FinishedGoodRepository finishedGoodRepository;
    @Autowired private RawMaterialRepository rawMaterialRepository;
    @Autowired private RawMaterialMovementRepository rawMaterialMovementRepository;
    @Autowired private InventoryMovementRepository inventoryMovementRepository;
    @Autowired private JournalEntryRepository journalEntryRepository;

    private CanonicalErpDataset onboardingDataset;

    @BeforeAll
    void setup() {
        dataSeeder.ensureUser("onboarding@test.com", PASSWORD, "Onboarding Admin", "ERP-ONB", List.of("ROLE_ADMIN"));
        CanonicalErpDatasetBuilder datasetBuilder = new CanonicalErpDatasetBuilder(
                dataSeeder,
                companyRepository,
                accountRepository,
                dealerRepository,
                supplierRepository,
                brandRepository,
                productRepository,
                finishedGoodRepository
        );
        onboardingDataset = datasetBuilder.seedCompany("ERP-ONB");
    }

    @Test
    @DisplayName("Opening stock posts balanced journal and links movements")
    void openingStock_postsJournalAndLinksMovements() {
        Company company = onboardingDataset.company();
        HttpHeaders headers = authHeaders("onboarding@test.com", company.getCode());
        Account openingAccount = ensureAccount(company, "OPENING", "Opening Equity", AccountType.EQUITY);

        Map<String, Object> productRequest = new HashMap<>();
        productRequest.put("brandName", "Onboarding Brand");
        productRequest.put("brandCode", "ONB");
        productRequest.put("productName", "Onboarding Paint");
        productRequest.put("category", "FINISHED_GOOD");
        productRequest.put("unitOfMeasure", "UNIT");
        productRequest.put("customSkuCode", "ONB-FG-001");
        productRequest.put("basePrice", new BigDecimal("100.00"));
        productRequest.put("gstRate", BigDecimal.ZERO);
        productRequest.put("minDiscountPercent", BigDecimal.ZERO);
        productRequest.put("minSellingPrice", new BigDecimal("95.00"));
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("wipAccountId", onboardingDataset.requireAccount("WIP").getId());
        metadata.put("semiFinishedAccountId", onboardingDataset.requireAccount("INV").getId());
        productRequest.put("metadata", metadata);

        ResponseEntity<Map> productResp = rest.exchange(
                "/api/v1/accounting/onboarding/products",
                HttpMethod.POST,
                new HttpEntity<>(productRequest, headers),
                Map.class);
        Map<?, ?> productData = requireData(productResp, "create onboarding product");
        String productCode = productData.get("skuCode").toString();

        Map<String, Object> rawMaterialRequest = new HashMap<>();
        rawMaterialRequest.put("name", "Onboarding RM");
        rawMaterialRequest.put("sku", "RM-ONB-001");
        rawMaterialRequest.put("unitType", "KG");
        rawMaterialRequest.put("materialType", "PACKAGING");
        rawMaterialRequest.put("reorderLevel", BigDecimal.ZERO);
        rawMaterialRequest.put("minStock", BigDecimal.ZERO);
        rawMaterialRequest.put("maxStock", BigDecimal.ZERO);

        ResponseEntity<Map> rawMaterialResp = rest.exchange(
                "/api/v1/accounting/onboarding/raw-materials",
                HttpMethod.POST,
                new HttpEntity<>(rawMaterialRequest, headers),
                Map.class);
        requireData(rawMaterialResp, "create onboarding raw material");

        LocalDate entryDate = LocalDate.now();
        Map<String, Object> fgLine = new HashMap<>();
        fgLine.put("productCode", productCode);
        fgLine.put("quantity", new BigDecimal("10"));
        fgLine.put("unitCost", new BigDecimal("12.50"));
        fgLine.put("batchCode", "ONB-FG-OPEN-01");
        fgLine.put("manufacturedDate", entryDate);

        Map<String, Object> rmLine = new HashMap<>();
        rmLine.put("sku", "RM-ONB-001");
        rmLine.put("quantity", new BigDecimal("50"));
        rmLine.put("unitCost", new BigDecimal("2.00"));
        rmLine.put("unit", "KG");
        rmLine.put("batchCode", "RM-OPEN-01");
        rmLine.put("materialType", "PACKAGING");

        Map<String, Object> openingRequest = new HashMap<>();
        openingRequest.put("referenceNumber", "ONB-OPEN-001");
        openingRequest.put("entryDate", entryDate);
        openingRequest.put("offsetAccountId", openingAccount.getId());
        openingRequest.put("memo", "Onboarding opening stock");
        openingRequest.put("finishedGoods", List.of(fgLine));
        openingRequest.put("rawMaterials", List.of(rmLine));

        ResponseEntity<Map> openingResp = rest.exchange(
                "/api/v1/accounting/onboarding/opening-stock",
                HttpMethod.POST,
                new HttpEntity<>(openingRequest, headers),
                Map.class);
        Map<?, ?> openingData = requireData(openingResp, "record opening stock");
        Long journalId = ((Number) openingData.get("journalEntryId")).longValue();

        JournalEntry journalEntry = journalEntryRepository.findById(journalId)
                .orElseThrow(() -> new AssertionError("Journal entry missing"));
        BigDecimal debits = journalEntry.getLines().stream()
                .map(line -> line.getDebit() == null ? BigDecimal.ZERO : line.getDebit())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal credits = journalEntry.getLines().stream()
                .map(line -> line.getCredit() == null ? BigDecimal.ZERO : line.getCredit())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(debits.subtract(credits)).as("opening stock journal balances").isEqualByComparingTo(BigDecimal.ZERO);

        List<InventoryMovement> fgMovements = inventoryMovementRepository
                .findByReferenceTypeAndReferenceIdOrderByCreatedAtAsc(InventoryReference.OPENING_STOCK, "ONB-FG-OPEN-01");
        assertThat(fgMovements).hasSize(1);
        assertThat(fgMovements.get(0).getJournalEntryId()).isEqualTo(journalId);

        List<RawMaterialMovement> rmMovements = rawMaterialMovementRepository
                .findByReferenceTypeAndReferenceId(InventoryReference.OPENING_STOCK, "RM-OPEN-01");
        assertThat(rmMovements).hasSize(1);
        assertThat(rmMovements.get(0).getJournalEntryId()).isEqualTo(journalId);

        FinishedGood finishedGood = finishedGoodRepository
                .findByCompanyAndProductCode(company, productCode)
                .orElseThrow(() -> new AssertionError("Finished good missing"));
        RawMaterial rawMaterial = rawMaterialRepository
                .findByCompanyAndSku(company, "RM-ONB-001")
                .orElseThrow(() -> new AssertionError("Raw material missing"));
        assertThat(finishedGood.getCurrentStock()).isGreaterThanOrEqualTo(BigDecimal.ZERO);
        assertThat(rawMaterial.getCurrentStock()).isGreaterThanOrEqualTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("Opening stock retry rejects idempotency conflicts")
    void openingStock_rejectsIdempotencyConflicts() {
        Company company = onboardingDataset.company();
        HttpHeaders headers = authHeaders("onboarding@test.com", company.getCode());
        Account openingAccount = ensureAccount(company, "OPENING", "Opening Equity", AccountType.EQUITY);

        Map<String, Object> productRequest = new HashMap<>();
        productRequest.put("brandName", "Onboarding Brand 2");
        productRequest.put("brandCode", "ONB2");
        productRequest.put("productName", "Onboarding Paint 2");
        productRequest.put("category", "FINISHED_GOOD");
        productRequest.put("unitOfMeasure", "UNIT");
        productRequest.put("customSkuCode", "ONB-FG-002");
        productRequest.put("basePrice", new BigDecimal("120.00"));
        productRequest.put("gstRate", BigDecimal.ZERO);
        productRequest.put("minDiscountPercent", BigDecimal.ZERO);
        productRequest.put("minSellingPrice", new BigDecimal("110.00"));
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("wipAccountId", onboardingDataset.requireAccount("WIP").getId());
        metadata.put("semiFinishedAccountId", onboardingDataset.requireAccount("INV").getId());
        productRequest.put("metadata", metadata);

        ResponseEntity<Map> productResp = rest.exchange(
                "/api/v1/accounting/onboarding/products",
                HttpMethod.POST,
                new HttpEntity<>(productRequest, headers),
                Map.class);
        Map<?, ?> productData = requireData(productResp, "create onboarding product");
        String productCode = productData.get("skuCode").toString();

        Map<String, Object> fgLine = new HashMap<>();
        fgLine.put("productCode", productCode);
        fgLine.put("quantity", new BigDecimal("10"));
        fgLine.put("unitCost", new BigDecimal("12.50"));
        fgLine.put("batchCode", "ONB-FG-OPEN-02");

        Map<String, Object> openingRequest = new HashMap<>();
        openingRequest.put("referenceNumber", "ONB-OPEN-002");
        openingRequest.put("entryDate", LocalDate.now());
        openingRequest.put("offsetAccountId", openingAccount.getId());
        openingRequest.put("memo", "Opening stock for idempotency test");
        openingRequest.put("finishedGoods", List.of(fgLine));

        ResponseEntity<Map> firstResp = rest.exchange(
                "/api/v1/accounting/onboarding/opening-stock",
                HttpMethod.POST,
                new HttpEntity<>(openingRequest, headers),
                Map.class);
        requireData(firstResp, "record opening stock");

        Map<String, Object> conflictLine = new HashMap<>(fgLine);
        conflictLine.put("quantity", new BigDecimal("9"));
        Map<String, Object> conflictRequest = new HashMap<>(openingRequest);
        conflictRequest.put("finishedGoods", List.of(conflictLine));

        ResponseEntity<Map> conflictResp = rest.exchange(
                "/api/v1/accounting/onboarding/opening-stock",
                HttpMethod.POST,
                new HttpEntity<>(conflictRequest, headers),
                Map.class);
        assertThat(conflictResp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        Map<?, ?> body = conflictResp.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("message").toString().toLowerCase()).contains("idempotency conflict");
    }

    @Test
    @DisplayName("Product creation requires default account mapping")
    void productRequiresDefaultAccounts() {
        dataSeeder.ensureUser("nodef@test.com", PASSWORD, "No Defaults", "ERP-NODEF", List.of("ROLE_ADMIN"));
        Company company = companyRepository.findByCodeIgnoreCase("ERP-NODEF")
                .orElseThrow(() -> new AssertionError("Company missing"));

        HttpHeaders headers = authHeaders("nodef@test.com", company.getCode());
        Map<String, Object> productRequest = new HashMap<>();
        productRequest.put("brandName", "No Default Brand");
        productRequest.put("productName", "No Default Product");
        productRequest.put("category", "FINISHED_GOOD");
        productRequest.put("unitOfMeasure", "UNIT");
        productRequest.put("customSkuCode", "NODEF-FG-001");
        productRequest.put("basePrice", new BigDecimal("100.00"));
        productRequest.put("gstRate", BigDecimal.ZERO);
        productRequest.put("minDiscountPercent", BigDecimal.ZERO);
        productRequest.put("minSellingPrice", new BigDecimal("95.00"));

        ResponseEntity<Map> response = rest.exchange(
                "/api/v1/accounting/onboarding/products",
                HttpMethod.POST,
                new HttpEntity<>(productRequest, headers),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        Map<?, ?> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("message").toString().toLowerCase()).contains("default accounts");
    }

    private Account ensureAccount(Company company, String code, String name, AccountType type) {
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

    private HttpHeaders authHeaders(String email, String companyCode) {
        Map<String, Object> login = Map.of(
                "email", email,
                "password", PASSWORD,
                "companyCode", companyCode
        );
        ResponseEntity<Map> response = rest.postForEntity("/api/v1/auth/login", login, Map.class);
        Map<?, ?> body = response.getBody();
        String token = body == null ? null : (String) body.get("accessToken");
        if (token == null) {
            throw new AssertionError("Login failed for " + email + ": " + body);
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Company-Id", companyCode);
        return headers;
    }

    private Map<?, ?> requireData(ResponseEntity<Map> response, String action) {
        if (!response.getStatusCode().is2xxSuccessful()) {
            throw new AssertionError(action + " failed: status=" + response.getStatusCode() + " body=" + response.getBody());
        }
        Map<?, ?> body = response.getBody();
        if (body == null || body.get("data") == null) {
            throw new AssertionError(action + " response missing data: " + body);
        }
        return (Map<?, ?>) body.get("data");
    }
}
