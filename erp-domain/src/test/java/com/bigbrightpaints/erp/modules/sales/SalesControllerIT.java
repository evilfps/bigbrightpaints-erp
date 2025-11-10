package com.bigbrightpaints.erp.modules.sales;

import com.bigbrightpaints.erp.test.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

public class SalesControllerIT extends AbstractIntegrationTest {

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
    void create_dealer_and_sales_order() {
        String token = loginToken();

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> dealerReq = Map.of(
                "name", "Prime Dealer",
                "code", "DLR-1",
                "email", "dealer@example.com",
                "phone", "9999999999",
                "creditLimit", new BigDecimal("100000")
        );
        ResponseEntity<Map> dResp = rest.exchange("/api/v1/dealers", HttpMethod.POST,
                new HttpEntity<>(dealerReq, headers), Map.class);
        assertThat(dResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map dealerData = (Map) dResp.getBody().get("data");
        Long dealerId = ((Number) dealerData.get("id")).longValue();

        Map<String, Object> orderReq = Map.of(
                "orderNumber", "SO-1001",
                "dealerId", dealerId,
                "totalAmount", new BigDecimal("12345.67"),
                "currency", "INR",
                "notes", "Test order"
        );
        ResponseEntity<Map> oResp = rest.exchange("/api/v1/sales/orders", HttpMethod.POST,
                new HttpEntity<>(orderReq, headers), Map.class);
        assertThat(oResp.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<Map> listResp = rest.exchange("/api/v1/sales/orders", HttpMethod.GET,
                new HttpEntity<>(headers), Map.class);
        assertThat(listResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        List list = (List) listResp.getBody().get("data");
        assertThat(list).isNotEmpty();
    }
}
