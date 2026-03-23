package com.bigbrightpaints.erp.e2e.sales;

import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.modules.production.domain.ProductionBrand;
import com.bigbrightpaints.erp.modules.production.domain.ProductionBrandRepository;
import com.bigbrightpaints.erp.modules.production.domain.ProductionProduct;
import com.bigbrightpaints.erp.modules.production.domain.ProductionProductRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGood;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodRepository;
import com.bigbrightpaints.erp.test.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
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

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("E2E: GST Inclusive Rounding")
@Tag("critical")
@Tag("reconciliation")
public class GstInclusiveRoundingIT extends AbstractIntegrationTest {

    private static final String COMPANY_CODE = "GSTINC";
    private static final String ADMIN_EMAIL = "gstinc@bbp.com";
    private static final String ADMIN_PASSWORD = "gst123";

    @Autowired private TestRestTemplate rest;
    @Autowired private CompanyRepository companyRepository;
    @Autowired private ProductionBrandRepository productionBrandRepository;
    @Autowired private ProductionProductRepository productionProductRepository;
    @Autowired private FinishedGoodRepository finishedGoodRepository;

    private HttpHeaders headers;
    private Company company;

    @BeforeEach
    void setup() {
        dataSeeder.ensureUser(ADMIN_EMAIL, ADMIN_PASSWORD, "GST Admin", COMPANY_CODE,
                List.of("ROLE_ADMIN", "ROLE_SALES", "ROLE_ACCOUNTING"));
        company = companyRepository.findByCodeIgnoreCase(COMPANY_CODE).orElseThrow();
        ensureProduct("SKU-A", new BigDecimal("100.00"), new BigDecimal("18"));
        ensureProduct("SKU-B", new BigDecimal("50.00"), new BigDecimal("5"));
        headers = authHeaders();
    }

    private HttpHeaders authHeaders() {
        Map<String, Object> req = Map.of(
                "email", ADMIN_EMAIL,
                "password", ADMIN_PASSWORD,
                "companyCode", COMPANY_CODE
        );
        ResponseEntity<Map> login = rest.postForEntity("/api/v1/auth/login", req, Map.class);
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
        String token = (String) login.getBody().get("accessToken");
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        h.setContentType(MediaType.APPLICATION_JSON);
        h.set("X-Company-Code", COMPANY_CODE);
        return h;
    }

    private void ensureProduct(String sku, BigDecimal price, BigDecimal gstRate) {
        ProductionBrand brand = productionBrandRepository.findByCompanyAndCodeIgnoreCase(company, "GST")
                .orElseGet(() -> {
                    ProductionBrand b = new ProductionBrand();
                    b.setCompany(company);
                    b.setCode("GST");
                    b.setName("GST Brand");
                    return productionBrandRepository.save(b);
                });
        ProductionProduct product = productionProductRepository.findByCompanyAndSkuCode(company, sku)
                .orElseGet(() -> {
                    ProductionProduct p = new ProductionProduct();
                    p.setCompany(company);
                    p.setBrand(brand);
                    p.setProductName(sku);
                    p.setCategory("FINISHED_GOOD");
                    p.setUnitOfMeasure("UNIT");
                    p.setSkuCode(sku);
                    return p;
                });
        product.setBasePrice(price);
        product.setGstRate(gstRate);
        productionProductRepository.save(product);

        FinishedGood fg = finishedGoodRepository.findByCompanyAndProductCode(company, sku)
                .orElseGet(() -> {
                    FinishedGood f = new FinishedGood();
                    f.setCompany(company);
                    f.setProductCode(sku);
                    f.setName(sku);
                    f.setCurrentStock(new BigDecimal("100"));
                    f.setReservedStock(BigDecimal.ZERO);
                    return f;
                });
        if (fg.getRevenueAccountId() == null) {
            fg.setRevenueAccountId(1L); // minimal non-null for validation
        }
        if (fg.getTaxAccountId() == null) {
            fg.setTaxAccountId(2L);
        }
        finishedGoodRepository.save(fg);
    }

    @Test
    @DisplayName("Inclusive GST per line computes base and tax correctly with rounding to paise")
    void gstInclusivePerItem_RoundsPaise() {
        Map<String, Object> item1 = new HashMap<>();
        item1.put("productCode", "SKU-A");
        item1.put("description", "Item A");
        item1.put("quantity", new BigDecimal("1"));
        item1.put("unitPrice", new BigDecimal("100.00")); // inclusive
        item1.put("gstRate", new BigDecimal("18"));

        Map<String, Object> item2 = new HashMap<>();
        item2.put("productCode", "SKU-B");
        item2.put("description", "Item B");
        item2.put("quantity", new BigDecimal("2"));
        item2.put("unitPrice", new BigDecimal("50.00")); // inclusive
        item2.put("gstRate", new BigDecimal("5"));

        BigDecimal expectedTax1 = new BigDecimal("15.25"); // 100 /1.18 = 84.75 base, tax 15.25
        BigDecimal expectedTax2Each = new BigDecimal("2.38"); // 50/1.05=47.62 base, tax 2.38
        BigDecimal expectedTaxTotal = expectedTax1.add(expectedTax2Each.multiply(new BigDecimal("2")));
        BigDecimal expectedTotal = new BigDecimal("200.00");
        BigDecimal expectedSubtotal = expectedTotal.subtract(expectedTaxTotal);

        Map<String, Object> orderReq = new HashMap<>();
        orderReq.put("dealerId", null);
        orderReq.put("totalAmount", expectedTotal);
        orderReq.put("currency", "INR");
        orderReq.put("notes", "Inclusive GST");
        orderReq.put("items", List.of(item1, item2));
        orderReq.put("gstTreatment", "PER_ITEM");
        orderReq.put("gstRate", null);
        orderReq.put("gstInclusive", true);

        ResponseEntity<Map> resp = rest.exchange("/api/v1/sales/orders",
                HttpMethod.POST, new HttpEntity<>(orderReq, headers), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> data = (Map<?, ?>) resp.getBody().get("data");
        BigDecimal subtotal = new BigDecimal(data.get("subtotalAmount").toString());
        BigDecimal tax = new BigDecimal(data.get("gstTotal").toString());
        BigDecimal total = new BigDecimal(data.get("totalAmount").toString());

        assertThat(total).isEqualByComparingTo(expectedTotal);
        assertThat(subtotal).isEqualByComparingTo(expectedSubtotal.setScale(2));
        assertThat(tax).isEqualByComparingTo(expectedTaxTotal.setScale(2));
    }

    @Test
    @DisplayName("Inclusive GST order-total keeps totals consistent")
    void gstInclusiveOrderTotal_RoundsPaise() {
        Map<String, Object> item = new HashMap<>();
        item.put("productCode", "SKU-A");
        item.put("description", "Order total item");
        item.put("quantity", new BigDecimal("1"));
        item.put("unitPrice", new BigDecimal("100.00")); // inclusive

        BigDecimal expectedTotal = new BigDecimal("100.00");
        BigDecimal expectedTax = new BigDecimal("15.25"); // 100 /1.18 = 84.75 base
        BigDecimal expectedSubtotal = expectedTotal.subtract(expectedTax);

        Map<String, Object> orderReq = new HashMap<>();
        orderReq.put("dealerId", null);
        orderReq.put("totalAmount", expectedTotal);
        orderReq.put("currency", "INR");
        orderReq.put("notes", "Inclusive GST order total");
        orderReq.put("items", List.of(item));
        orderReq.put("gstTreatment", "ORDER_TOTAL");
        orderReq.put("gstRate", new BigDecimal("18"));
        orderReq.put("gstInclusive", true);

        ResponseEntity<Map> resp = rest.exchange("/api/v1/sales/orders",
                HttpMethod.POST, new HttpEntity<>(orderReq, headers), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> data = (Map<?, ?>) resp.getBody().get("data");
        BigDecimal subtotal = new BigDecimal(data.get("subtotalAmount").toString());
        BigDecimal tax = new BigDecimal(data.get("gstTotal").toString());
        BigDecimal total = new BigDecimal(data.get("totalAmount").toString());
        BigDecimal rounding = new BigDecimal(data.get("gstRoundingAdjustment").toString());

        assertThat(total).isEqualByComparingTo(expectedTotal);
        assertThat(subtotal).isEqualByComparingTo(expectedSubtotal.setScale(2));
        assertThat(tax).isEqualByComparingTo(expectedTax);
        assertThat(rounding).isEqualByComparingTo(BigDecimal.ZERO.setScale(2));

        List<?> items = (List<?>) data.get("items");
        assertThat(items).hasSize(1);
        Map<?, ?> itemData = (Map<?, ?>) items.get(0);
        BigDecimal lineSubtotal = new BigDecimal(itemData.get("lineSubtotal").toString());
        BigDecimal lineTax = new BigDecimal(itemData.get("gstAmount").toString());
        BigDecimal lineTotal = new BigDecimal(itemData.get("lineTotal").toString());
        assertThat(lineSubtotal).isEqualByComparingTo(expectedSubtotal.setScale(2));
        assertThat(lineTax).isEqualByComparingTo(expectedTax);
        assertThat(lineTotal).isEqualByComparingTo(expectedTotal);
    }

    @Test
    @DisplayName("Exclusive GST NONE ignores per-line tax rates")
    void gstExclusiveNone_IgnoresTaxRates() {
        Map<String, Object> item = new HashMap<>();
        item.put("productCode", "SKU-A");
        item.put("description", "No GST item");
        item.put("quantity", new BigDecimal("2"));
        item.put("unitPrice", new BigDecimal("100.00"));
        item.put("gstRate", new BigDecimal("18"));

        BigDecimal expectedSubtotal = new BigDecimal("200.00");

        Map<String, Object> orderReq = new HashMap<>();
        orderReq.put("dealerId", null);
        orderReq.put("totalAmount", expectedSubtotal);
        orderReq.put("currency", "INR");
        orderReq.put("notes", "GST NONE");
        orderReq.put("items", List.of(item));
        orderReq.put("gstTreatment", "NONE");
        orderReq.put("gstRate", null);
        orderReq.put("gstInclusive", false);

        ResponseEntity<Map> resp = rest.exchange("/api/v1/sales/orders",
                HttpMethod.POST, new HttpEntity<>(orderReq, headers), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> data = (Map<?, ?>) resp.getBody().get("data");
        BigDecimal subtotal = new BigDecimal(data.get("subtotalAmount").toString());
        BigDecimal tax = new BigDecimal(data.get("gstTotal").toString());
        BigDecimal total = new BigDecimal(data.get("totalAmount").toString());

        assertThat(subtotal).isEqualByComparingTo(expectedSubtotal);
        assertThat(tax).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(total).isEqualByComparingTo(expectedSubtotal);

        List<?> items = (List<?>) data.get("items");
        assertThat(items).hasSize(1);
        Map<?, ?> itemData = (Map<?, ?>) items.get(0);
        BigDecimal lineTax = new BigDecimal(itemData.get("gstAmount").toString());
        BigDecimal lineRate = new BigDecimal(itemData.get("gstRate").toString());
        BigDecimal lineTotal = new BigDecimal(itemData.get("lineTotal").toString());
        assertThat(lineTax).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(lineRate).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(lineTotal).isEqualByComparingTo(expectedSubtotal);
    }

    @Test
    @DisplayName("Exclusive GST per-item computes line tax and totals")
    void gstExclusivePerItem_ComputesTaxes() {
        Map<String, Object> item1 = new HashMap<>();
        item1.put("productCode", "SKU-A");
        item1.put("description", "Item A");
        item1.put("quantity", new BigDecimal("1"));
        item1.put("unitPrice", new BigDecimal("100.00"));
        item1.put("gstRate", new BigDecimal("18"));

        Map<String, Object> item2 = new HashMap<>();
        item2.put("productCode", "SKU-B");
        item2.put("description", "Item B");
        item2.put("quantity", new BigDecimal("2"));
        item2.put("unitPrice", new BigDecimal("50.00"));
        item2.put("gstRate", new BigDecimal("5"));

        BigDecimal expectedSubtotal = new BigDecimal("200.00");
        BigDecimal expectedTax = new BigDecimal("23.00");
        BigDecimal expectedTotal = new BigDecimal("223.00");

        Map<String, Object> orderReq = new HashMap<>();
        orderReq.put("dealerId", null);
        orderReq.put("totalAmount", expectedTotal);
        orderReq.put("currency", "INR");
        orderReq.put("notes", "Exclusive GST per item");
        orderReq.put("items", List.of(item1, item2));
        orderReq.put("gstTreatment", "PER_ITEM");
        orderReq.put("gstRate", null);
        orderReq.put("gstInclusive", false);

        ResponseEntity<Map> resp = rest.exchange("/api/v1/sales/orders",
                HttpMethod.POST, new HttpEntity<>(orderReq, headers), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> data = (Map<?, ?>) resp.getBody().get("data");
        BigDecimal subtotal = new BigDecimal(data.get("subtotalAmount").toString());
        BigDecimal tax = new BigDecimal(data.get("gstTotal").toString());
        BigDecimal total = new BigDecimal(data.get("totalAmount").toString());

        assertThat(subtotal).isEqualByComparingTo(expectedSubtotal);
        assertThat(tax).isEqualByComparingTo(expectedTax);
        assertThat(total).isEqualByComparingTo(expectedTotal);
    }

    @Test
    @DisplayName("Exclusive GST order-total distributes tax across lines")
    void gstExclusiveOrderTotal_DistributesTax() {
        Map<String, Object> item1 = new HashMap<>();
        item1.put("productCode", "SKU-A");
        item1.put("description", "Item A");
        item1.put("quantity", new BigDecimal("1"));
        item1.put("unitPrice", new BigDecimal("100.00"));

        Map<String, Object> item2 = new HashMap<>();
        item2.put("productCode", "SKU-B");
        item2.put("description", "Item B");
        item2.put("quantity", new BigDecimal("1"));
        item2.put("unitPrice", new BigDecimal("50.00"));

        BigDecimal expectedSubtotal = new BigDecimal("150.00");
        BigDecimal expectedTax = new BigDecimal("15.00");
        BigDecimal expectedTotal = new BigDecimal("165.00");

        Map<String, Object> orderReq = new HashMap<>();
        orderReq.put("dealerId", null);
        orderReq.put("totalAmount", expectedTotal);
        orderReq.put("currency", "INR");
        orderReq.put("notes", "Exclusive GST order total");
        orderReq.put("items", List.of(item1, item2));
        orderReq.put("gstTreatment", "ORDER_TOTAL");
        orderReq.put("gstRate", new BigDecimal("10"));
        orderReq.put("gstInclusive", false);

        ResponseEntity<Map> resp = rest.exchange("/api/v1/sales/orders",
                HttpMethod.POST, new HttpEntity<>(orderReq, headers), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> data = (Map<?, ?>) resp.getBody().get("data");
        BigDecimal subtotal = new BigDecimal(data.get("subtotalAmount").toString());
        BigDecimal tax = new BigDecimal(data.get("gstTotal").toString());
        BigDecimal total = new BigDecimal(data.get("totalAmount").toString());
        BigDecimal rounding = new BigDecimal(data.get("gstRoundingAdjustment").toString());

        assertThat(subtotal).isEqualByComparingTo(expectedSubtotal);
        assertThat(tax).isEqualByComparingTo(expectedTax);
        assertThat(total).isEqualByComparingTo(expectedTotal);
        assertThat(rounding).isEqualByComparingTo(BigDecimal.ZERO.setScale(2));
    }
}
