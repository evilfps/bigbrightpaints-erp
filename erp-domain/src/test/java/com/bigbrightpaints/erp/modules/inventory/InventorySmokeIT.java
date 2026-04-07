package com.bigbrightpaints.erp.modules.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;

import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterial;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialRepository;
import com.bigbrightpaints.erp.test.AbstractIntegrationTest;

public class InventorySmokeIT extends AbstractIntegrationTest {

  @Autowired private TestRestTemplate rest;
  @Autowired private RawMaterialRepository rawMaterialRepository;
  private static final String COMPANY_CODE = "ACME";
  private static final String ADMIN_EMAIL = "admin@bbp.com";
  private static final String ADMIN_PASSWORD = "admin123";

  @org.junit.jupiter.api.BeforeEach
  void seed() {
    dataSeeder.ensureUser(
        ADMIN_EMAIL, ADMIN_PASSWORD, "Admin", COMPANY_CODE, java.util.List.of("ROLE_ADMIN"));
    var company = companyRepository.findByCodeIgnoreCase(COMPANY_CODE).orElseThrow();
    rawMaterialRepository
        .findByCompanyAndSku(company, "RM-SMOKE-STOCK")
        .orElseGet(
            () -> {
              RawMaterial material = new RawMaterial();
              material.setCompany(company);
              material.setName("Smoke Material");
              material.setSku("RM-SMOKE-STOCK");
              material.setUnitType("KG");
              material.setCurrentStock(new BigDecimal("12.00"));
              material.setReorderLevel(new BigDecimal("5.00"));
              material.setMinStock(new BigDecimal("2.00"));
              material.setMaxStock(new BigDecimal("100.00"));
              return rawMaterialRepository.save(material);
            });
  }

  private String loginToken() {
    Map<String, Object> req =
        Map.of(
            "email", ADMIN_EMAIL,
            "password", ADMIN_PASSWORD,
            "companyCode", COMPANY_CODE);
    return (String)
        rest.postForEntity("/api/v1/auth/login", req, Map.class).getBody().get("accessToken");
  }

  @Test
  void stock_summary_is_available_for_inventory_workbench() {
    String token = loginToken();

    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth(token);
    headers.setContentType(MediaType.APPLICATION_JSON);

    ResponseEntity<Map> summaryResp =
        rest.exchange(
            "/api/v1/raw-materials/stock", HttpMethod.GET, new HttpEntity<>(headers), Map.class);
    assertThat(summaryResp.getStatusCode()).isEqualTo(HttpStatus.OK);
    List<Map<String, Object>> data = (List<Map<String, Object>>) summaryResp.getBody().get("data");
    assertThat(data).isNotEmpty();
    assertThat(data.getFirst()).containsKeys("materialId", "quantity");
  }
}
