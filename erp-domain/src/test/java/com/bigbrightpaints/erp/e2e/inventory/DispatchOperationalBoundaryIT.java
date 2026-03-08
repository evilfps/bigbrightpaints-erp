package com.bigbrightpaints.erp.e2e.inventory;

import com.bigbrightpaints.erp.core.security.CompanyContextHolder;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountType;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGood;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryMovementRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryReference;
import com.bigbrightpaints.erp.modules.inventory.domain.PackagingSlip;
import com.bigbrightpaints.erp.modules.inventory.domain.PackagingSlipRepository;
import com.bigbrightpaints.erp.modules.inventory.dto.FinishedGoodBatchRequest;
import com.bigbrightpaints.erp.modules.inventory.dto.FinishedGoodRequest;
import com.bigbrightpaints.erp.modules.inventory.service.FinishedGoodsService;
import com.bigbrightpaints.erp.modules.production.domain.ProductionBrand;
import com.bigbrightpaints.erp.modules.production.domain.ProductionBrandRepository;
import com.bigbrightpaints.erp.modules.production.domain.ProductionProduct;
import com.bigbrightpaints.erp.modules.production.domain.ProductionProductRepository;
import com.bigbrightpaints.erp.modules.sales.domain.Dealer;
import com.bigbrightpaints.erp.modules.sales.domain.DealerRepository;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrder;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrderRepository;
import com.bigbrightpaints.erp.modules.sales.dto.SalesOrderItemRequest;
import com.bigbrightpaints.erp.modules.sales.dto.SalesOrderRequest;
import com.bigbrightpaints.erp.modules.sales.service.SalesService;
import com.bigbrightpaints.erp.test.AbstractIntegrationTest;
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
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@TestPropertySource(properties = "erp.auto-approval.enabled=false")
class DispatchOperationalBoundaryIT extends AbstractIntegrationTest {

    private static final String COMPANY_CODE = "DISPATCH-OPS";
    private static final String FACTORY_EMAIL = "factory.ops@test.com";
    private static final String FACTORY_PASSWORD = "factory123";

    @Autowired private TestRestTemplate rest;
    @Autowired private CompanyRepository companyRepository;
    @Autowired private DealerRepository dealerRepository;
    @Autowired private SalesOrderRepository salesOrderRepository;
    @Autowired private SalesService salesService;
    @Autowired private FinishedGoodsService finishedGoodsService;
    @Autowired private FinishedGoodRepository finishedGoodRepository;
    @Autowired private PackagingSlipRepository packagingSlipRepository;
    @Autowired private InventoryMovementRepository inventoryMovementRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private ProductionBrandRepository productionBrandRepository;
    @Autowired private ProductionProductRepository productionProductRepository;

    private Company company;
    private Dealer dealer;
    private Map<String, Account> accounts;

    @BeforeEach
    void setup() {
        dataSeeder.ensureUser(FACTORY_EMAIL, FACTORY_PASSWORD, "Factory Ops", COMPANY_CODE,
                List.of("ROLE_FACTORY", "dispatch.confirm"));
        CompanyContextHolder.setCompanyId(COMPANY_CODE);
        company = companyRepository.findByCodeIgnoreCase(COMPANY_CODE).orElseThrow();
        company.setBaseCurrency("INR");
        company.setTimezone("UTC");
        company.setStateCode("27");
        company = companyRepository.save(company);

        accounts = ensureAccounts(company);
        company = companyRepository.findById(company.getId()).orElseThrow();
        company.setDefaultInventoryAccountId(accounts.get("INV").getId());
        company.setDefaultCogsAccountId(accounts.get("COGS").getId());
        company.setDefaultRevenueAccountId(accounts.get("REV").getId());
        company.setDefaultDiscountAccountId(accounts.get("DISC").getId());
        company.setDefaultTaxAccountId(accounts.get("TAX").getId());
        company.setGstOutputTaxAccountId(accounts.get("TAX").getId());
        company = companyRepository.save(company);
        dealer = ensureDealer(company, "OPS-DEALER", "Ops Dealer", accounts.get("AR"));
        dealer.setStateCode("27");
        dealerRepository.save(dealer);
    }

    @AfterEach
    void clearCompanyContext() {
        CompanyContextHolder.clear();
    }

    @Test
    void factoryDispatchFlow_redactsCommercialFields_persistsLogistics_andReplaysWithoutDuplicateMovements() {
        String sku = "FG-OPS-" + UUID.randomUUID().toString().substring(0, 6);
        FinishedGood fg = createFinishedGood(sku);
        ensureCatalogProduct(fg, new BigDecimal("125.00"), new BigDecimal("18.00"));
        finishedGoodsService.registerBatch(new FinishedGoodBatchRequest(
                fg.getId(), "BATCH-OPS", new BigDecimal("12"), new BigDecimal("70.00"), Instant.now(), null));

        SalesOrderRequest orderReq = new SalesOrderRequest(
                dealer.getId(),
                new BigDecimal("500.00"),
                "INR",
                null,
                List.of(new SalesOrderItemRequest(fg.getProductCode(), "Operational Item", new BigDecimal("4"), new BigDecimal("125.00"), new BigDecimal("18.00"))),
                "EXCLUSIVE",
                null,
                null,
                null
        );
        Long orderId = salesService.createOrder(orderReq).id();
        SalesOrder order = salesOrderRepository.findById(orderId).orElseThrow();
        finishedGoodsService.reserveForOrder(order);

        PackagingSlip slip = packagingSlipRepository.findByCompanyAndSalesOrderId(company, orderId).orElseThrow();

        HttpHeaders headers = authHeaders(loginToken());
        ResponseEntity<Map> previewResponse = rest.exchange(
                "/api/v1/dispatch/preview/" + slip.getId(),
                HttpMethod.GET,
                new HttpEntity<>(headers),
                Map.class);
        assertThat(previewResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> previewData = requireData(previewResponse);
        assertThat(previewData.get("gstBreakdown")).isNull();
        Map<?, ?> previewLine = ((List<Map<?, ?>>) previewData.get("lines")).getFirst();
        assertThat(previewLine.get("unitPrice")).isNull();
        assertThat(previewLine.get("lineTotal")).isNull();

        Map<String, Object> confirmRequest = Map.of(
                "packagingSlipId", slip.getId(),
                "lines", List.of(Map.of(
                        "lineId", slip.getLines().getFirst().getId(),
                        "shippedQuantity", new BigDecimal("4"),
                        "notes", "ship all"
                )),
                "notes", "ready for dispatch",
                "confirmedBy", "factory-user",
                "transporterName", "Rapid Logistics",
                "driverName", "Imran",
                "vehicleNumber", "MH14ZZ1001",
                "challanReference", "CH-7788"
        );

        ResponseEntity<Map> firstResponse = rest.exchange(
                "/api/v1/dispatch/confirm",
                HttpMethod.POST,
                new HttpEntity<>(confirmRequest, headers),
                Map.class);
        assertThat(firstResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> firstData = requireData(firstResponse);
        assertThat(firstData.get("journalEntryId")).isNull();
        assertThat(firstData.get("cogsJournalEntryId")).isNull();
        assertThat(firstData.get("totalShippedAmount")).isNull();
        assertThat(firstData.get("challanReference")).isEqualTo("CH-7788");
        assertThat(firstData.get("deliveryChallanNumber")).isEqualTo("DC-" + slip.getSlipNumber());
        assertThat(firstData.get("deliveryChallanPdfPath"))
                .isEqualTo("/api/v1/dispatch/slip/" + slip.getId() + "/challan/pdf");
        Map<?, ?> firstLine = ((List<Map<?, ?>>) firstData.get("lines")).getFirst();
        assertThat(firstLine.get("unitCost")).isNull();
        assertThat(firstLine.get("lineTotal")).isNull();

        PackagingSlip persisted = packagingSlipRepository.findById(slip.getId()).orElseThrow();
        assertThat(persisted.getTransporterName()).isEqualTo("Rapid Logistics");
        assertThat(persisted.getDriverName()).isEqualTo("Imran");
        assertThat(persisted.getVehicleNumber()).isEqualTo("MH14ZZ1001");
        assertThat(persisted.getChallanReference()).isEqualTo("CH-7788");
        assertThat(persisted.getInvoiceId()).isNotNull();
        assertThat(persisted.getJournalEntryId()).isNotNull();
        assertThat(persisted.getCogsJournalEntryId()).isNotNull();

        long movementCount = inventoryMovementRepository
                .findByFinishedGood_CompanyAndPackingSlipIdAndMovementTypeIgnoreCaseOrderByCreatedAtAsc(
                        company,
                        slip.getId(),
                        "DISPATCH")
                .size();

        ResponseEntity<Map> replayResponse = rest.exchange(
                "/api/v1/dispatch/confirm",
                HttpMethod.POST,
                new HttpEntity<>(confirmRequest, headers),
                Map.class);
        assertThat(replayResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> replayData = requireData(replayResponse);
        assertThat(replayData.get("deliveryChallanPdfPath"))
                .isEqualTo(firstData.get("deliveryChallanPdfPath"));

        PackagingSlip replayed = packagingSlipRepository.findById(slip.getId()).orElseThrow();
        assertThat(replayed.getInvoiceId()).isEqualTo(persisted.getInvoiceId());
        assertThat(replayed.getJournalEntryId()).isEqualTo(persisted.getJournalEntryId());
        assertThat(replayed.getCogsJournalEntryId()).isEqualTo(persisted.getCogsJournalEntryId());
        assertThat(inventoryMovementRepository
                .findByFinishedGood_CompanyAndPackingSlipIdAndMovementTypeIgnoreCaseOrderByCreatedAtAsc(
                        company,
                        slip.getId(),
                        "DISPATCH")
                .size()).isEqualTo(movementCount);

        ResponseEntity<byte[]> challanResponse = rest.exchange(
                "/api/v1/dispatch/slip/" + slip.getId() + "/challan/pdf",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                byte[].class);
        assertThat(challanResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(challanResponse.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PDF);
        assertThat(challanResponse.getBody()).isNotNull();
        assertThat(challanResponse.getBody().length).isGreaterThan(100);
    }

    @SuppressWarnings("unchecked")
    private Map<?, ?> requireData(ResponseEntity<Map> response) {
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("success")).isEqualTo(Boolean.TRUE);
        return (Map<?, ?>) response.getBody().get("data");
    }

    private String loginToken() {
        Map<String, Object> req = Map.of(
                "email", FACTORY_EMAIL,
                "password", FACTORY_PASSWORD,
                "companyCode", COMPANY_CODE
        );
        return (String) rest.postForEntity("/api/v1/auth/login", req, Map.class).getBody().get("accessToken");
    }

    private HttpHeaders authHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.add("X-Company-Code", COMPANY_CODE);
        return headers;
    }

    private Map<String, Account> ensureAccounts(Company company) {
        return Map.of(
                "AR", ensureAccount(company, "AR", "Accounts Receivable", AccountType.ASSET),
                "INV", ensureAccount(company, "INV", "Inventory", AccountType.ASSET),
                "COGS", ensureAccount(company, "COGS", "Cost of Goods Sold", AccountType.EXPENSE),
                "REV", ensureAccount(company, "REV", "Revenue", AccountType.REVENUE),
                "DISC", ensureAccount(company, "DISC", "Discounts", AccountType.EXPENSE),
                "TAX", ensureAccount(company, "TAX", "Tax Payable", AccountType.LIABILITY)
        );
    }

    private Account ensureAccount(Company company, String code, String name, AccountType type) {
        return accountRepository.findByCompanyAndCodeIgnoreCase(company, code)
                .orElseGet(() -> {
                    Account account = new Account();
                    account.setCompany(company);
                    account.setCode(code);
                    account.setName(name);
                    account.setType(type);
                    account.setActive(true);
                    account.setBalance(BigDecimal.ZERO);
                    return accountRepository.save(account);
                });
    }

    private Dealer ensureDealer(Company company, String code, String name, Account arAccount) {
        return dealerRepository.findByCompanyAndCodeIgnoreCase(company, code)
                .orElseGet(() -> {
                    Dealer d = new Dealer();
                    d.setCompany(company);
                    d.setCode(code);
                    d.setName(name);
                    d.setStatus("ACTIVE");
                    d.setReceivableAccount(arAccount);
                    return dealerRepository.save(d);
                });
    }

    private FinishedGood createFinishedGood(String productCode) {
        FinishedGoodRequest req = new FinishedGoodRequest(
                productCode,
                productCode + " Name",
                "UNIT",
                "FIFO",
                accounts.get("INV").getId(),
                accounts.get("COGS").getId(),
                accounts.get("REV").getId(),
                accounts.get("DISC").getId(),
                accounts.get("TAX").getId()
        );
        return finishedGoodRepository.findByCompanyAndProductCode(company, productCode)
                .orElseGet(() -> {
                    var dto = finishedGoodsService.createFinishedGood(req);
                    return finishedGoodRepository.findById(dto.id()).orElseThrow();
                });
    }

    private void ensureCatalogProduct(FinishedGood fg, BigDecimal basePrice, BigDecimal gstRate) {
        ProductionBrand brand = productionBrandRepository.findByCompanyAndCodeIgnoreCase(fg.getCompany(), "OPS-BRAND")
                .orElseGet(() -> {
                    ProductionBrand b = new ProductionBrand();
                    b.setCompany(fg.getCompany());
                    b.setCode("OPS-BRAND");
                    b.setName("Ops Brand");
                    return productionBrandRepository.save(b);
                });
        productionProductRepository.findByCompanyAndSkuCode(fg.getCompany(), fg.getProductCode())
                .orElseGet(() -> {
                    ProductionProduct p = new ProductionProduct();
                    p.setCompany(fg.getCompany());
                    p.setBrand(brand);
                    p.setSkuCode(fg.getProductCode());
                    p.setProductName(fg.getName());
                    p.setBasePrice(basePrice);
                    p.setCategory("GENERAL");
                    p.setSizeLabel("STD");
                    p.setDefaultColour("NA");
                    p.setMinDiscountPercent(BigDecimal.ZERO);
                    p.setMinSellingPrice(BigDecimal.ZERO);
                    p.setMetadata(new java.util.HashMap<>());
                    p.setGstRate(gstRate);
                    p.setUnitOfMeasure("UNIT");
                    p.setActive(true);
                    return productionProductRepository.save(p);
                });
    }
}
