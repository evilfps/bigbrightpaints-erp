package com.bigbrightpaints.erp.modules.inventory;

import com.bigbrightpaints.erp.test.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

public class InventorySmokeIT extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate rest;
    private static final String COMPANY_CODE = "ACME";
    private static final String ADMIN_EMAIL = "admin@bbp.com";
    private static final String ADMIN_PASSWORD = "admin123";

    @org.junit.jupiter.api.BeforeEach
    void seed() {
        dataSeeder.ensureUser(ADMIN_EMAIL, ADMIN_PASSWORD, "Admin", COMPANY_CODE, java.util.List.of("ROLE_ADMIN"));
    }

    private String loginToken() {
        Map<String, Object> req = Map.of(
                "email", ADMIN_EMAIL,
                "password", ADMIN_PASSWORD,
                "companyCode", COMPANY_CODE
        );
        return (String) rest.postForEntity("/api/v1/auth/login", req, Map.class).getBody().get("accessToken");
    }

    @Test
    void create_raw_material_and_check_stock_summary() {
        String token = loginToken();

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> request = Map.of(
                "name", "Titanium Dioxide",
                "sku", "RM-001",
                "unitType", "KG",
                "reorderLevel", new BigDecimal("50"),
                "minStock", new BigDecimal("20"),
                "maxStock", new BigDecimal("500")
        );

        ResponseEntity<Map> createResp = rest.exchange("/api/v1/accounting/raw-materials", HttpMethod.POST,
                new HttpEntity<>(request, headers), Map.class);
        assertThat(createResp.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<Map> summaryResp = rest.exchange("/api/v1/raw-materials/stock", HttpMethod.GET,
                new HttpEntity<>(headers), Map.class);
        assertThat(summaryResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map data = (Map) summaryResp.getBody().get("data");
        assertThat(((Number) data.get("totalMaterials")).longValue()).isGreaterThanOrEqualTo(1L);
    }
}
