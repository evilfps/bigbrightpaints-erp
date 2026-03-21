package com.bigbrightpaints.erp.performance;

import com.bigbrightpaints.erp.core.security.CompanyContextHolder;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.sales.domain.Dealer;
import com.bigbrightpaints.erp.modules.sales.domain.DealerRepository;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrder;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrderItem;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrderRepository;
import com.bigbrightpaints.erp.modules.sales.dto.SalesOrderDto;
import com.bigbrightpaints.erp.modules.sales.service.SalesService;
import com.bigbrightpaints.erp.test.AbstractIntegrationTest;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
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
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@TestPropertySource(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
class PerformanceBudgetIT extends AbstractIntegrationTest {

    private static final String COMPANY_CODE = "PERF-BUDGET";
    private static final String ADMIN_EMAIL = "perf-admin@bbp.com";
    private static final String ADMIN_PASSWORD = "admin123";

    @Autowired
    private SalesService salesService;

    @Autowired
    private SalesOrderRepository salesOrderRepository;

    @Autowired
    private DealerRepository dealerRepository;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private TestRestTemplate rest;

    @BeforeEach
    void seedFixtures() {
        dataSeeder.ensureUser(ADMIN_EMAIL, ADMIN_PASSWORD, "Perf Admin", COMPANY_CODE, List.of("ROLE_ADMIN"));
    }

    @AfterEach
    void clearCompanyContext() {
        CompanyContextHolder.clear();
    }

    @Test
    void salesOrderListQueryCountIsBounded() {
        Company company = dataSeeder.ensureCompany(COMPANY_CODE, "Perf Budget Co");
        Dealer dealer = dealerRepository.findByCompanyAndCodeIgnoreCase(company, "FIX-DEALER")
                .orElseThrow(() -> new IllegalStateException("Fixture dealer missing for " + COMPANY_CODE));
        seedOrders(company, dealer, 5);

        CompanyContextHolder.setCompanyId(COMPANY_CODE);

        Statistics stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        stats.setStatisticsEnabled(true);
        stats.clear();

        List<SalesOrderDto> orders = salesService.listOrders(null, 0, 25);

        assertThat(orders).hasSizeGreaterThanOrEqualTo(5);
        assertThat(stats.getPrepareStatementCount()).isLessThanOrEqualTo(6L);
    }

    @Test
    void balanceSheetReportCompletesWithinBudget() {
        String token = loginToken();
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);

        long start = System.nanoTime();
        ResponseEntity<Map> response = rest.exchange(
                "/api/v1/reports/balance-sheet/hierarchy",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                Map.class);
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(elapsed).isLessThanOrEqualTo(Duration.ofSeconds(3));
    }

    private String loginToken() {
        Map<String, Object> req = Map.of(
                "email", ADMIN_EMAIL,
                "password", ADMIN_PASSWORD,
                "companyCode", COMPANY_CODE
        );
        ResponseEntity<Map> response = rest.postForEntity("/api/v1/auth/login", req, Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = response.getBody();
        assertThat(body).isNotNull();
        return body.get("accessToken").toString();
    }

    private void seedOrders(Company company, Dealer dealer, int count) {
        for (int i = 0; i < count; i++) {
            SalesOrder order = new SalesOrder();
            order.setCompany(company);
            order.setDealer(dealer);
            order.setOrderNumber("SO-Q-" + i + "-" + System.nanoTime());
            order.setStatus("BOOKED");
            order.setTotalAmount(new BigDecimal("100.00"));
            order.setSubtotalAmount(new BigDecimal("100.00"));
            order.setGstTotal(BigDecimal.ZERO);
            order.setCurrency("INR");

            SalesOrderItem item = new SalesOrderItem();
            item.setSalesOrder(order);
            item.setProductCode("FG-FIXTURE");
            item.setDescription("Fixture item");
            item.setQuantity(BigDecimal.ONE);
            item.setUnitPrice(new BigDecimal("100.00"));
            item.setLineSubtotal(new BigDecimal("100.00"));
            item.setLineTotal(new BigDecimal("100.00"));
            item.setGstRate(BigDecimal.ZERO);
            item.setGstAmount(BigDecimal.ZERO);
            order.getItems().add(item);

            salesOrderRepository.save(order);
        }
    }
}
