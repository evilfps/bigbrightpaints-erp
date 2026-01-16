package com.bigbrightpaints.erp.regression;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.core.security.CompanyContextHolder;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.hr.dto.PayrollRunRequest;
import com.bigbrightpaints.erp.modules.hr.domain.PayrollRun;
import com.bigbrightpaints.erp.modules.hr.domain.PayrollRunRepository;
import com.bigbrightpaints.erp.modules.hr.service.HrService;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGood;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodRepository;
import com.bigbrightpaints.erp.modules.sales.domain.Dealer;
import com.bigbrightpaints.erp.modules.sales.domain.DealerRepository;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrder;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrderItem;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrderRepository;
import com.bigbrightpaints.erp.modules.sales.dto.SalesOrderItemRequest;
import com.bigbrightpaints.erp.modules.sales.dto.SalesOrderRequest;
import com.bigbrightpaints.erp.modules.sales.service.SalesService;
import com.bigbrightpaints.erp.test.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Regression: Idempotency conflicts are rejected")
class IdempotencyConflictRegressionIT extends AbstractIntegrationTest {

    private static final String COMPANY_CODE = "LF-023";

    @Autowired private SalesService salesService;
    @Autowired private DealerRepository dealerRepository;
    @Autowired private FinishedGoodRepository finishedGoodRepository;
    @Autowired private SalesOrderRepository salesOrderRepository;
    @Autowired private HrService hrService;
    @Autowired private PayrollRunRepository payrollRunRepository;

    private Company company;
    private Dealer dealer;
    private FinishedGood finishedGood;

    @BeforeEach
    void setUp() {
        company = dataSeeder.ensureCompany(COMPANY_CODE, "LF-023 Ltd");
        CompanyContextHolder.setCompanyId(COMPANY_CODE);
        dealer = dealerRepository.findByCompanyAndCodeIgnoreCase(company, "FIX-DEALER").orElseThrow();
        finishedGood = finishedGoodRepository.findByCompanyAndProductCode(company, "FG-FIXTURE").orElseThrow();
    }

    @AfterEach
    void tearDown() {
        CompanyContextHolder.clear();
    }

    @Test
    void salesOrderConflictingPayloadThrows() {
        SalesOrderRequest base = new SalesOrderRequest(
                dealer.getId(),
                new BigDecimal("100.00"),
                "INR",
                "Initial order",
                List.of(new SalesOrderItemRequest(
                        finishedGood.getProductCode(),
                        finishedGood.getName(),
                        new BigDecimal("1.00"),
                        new BigDecimal("100.00"),
                        BigDecimal.ZERO)),
                "NONE",
                BigDecimal.ZERO,
                Boolean.FALSE,
                "IDEMP-ORDER-1"
        );
        salesService.createOrder(base);

        SalesOrderRequest conflict = new SalesOrderRequest(
                dealer.getId(),
                new BigDecimal("200.00"),
                "INR",
                "Updated order",
                List.of(new SalesOrderItemRequest(
                        finishedGood.getProductCode(),
                        finishedGood.getName(),
                        new BigDecimal("2.00"),
                        new BigDecimal("100.00"),
                        BigDecimal.ZERO)),
                "NONE",
                BigDecimal.ZERO,
                Boolean.FALSE,
                "IDEMP-ORDER-1"
        );

        assertThatThrownBy(() -> salesService.createOrder(conflict))
                .isInstanceOf(ApplicationException.class)
                .extracting(ex -> ((ApplicationException) ex).getErrorCode())
                .isEqualTo(ErrorCode.CONCURRENCY_CONFLICT);
    }

    @Test
    void payrollRunConflictingPayloadThrows() {
        PayrollRunRequest base = new PayrollRunRequest(
                LocalDate.of(2026, 1, 10),
                new BigDecimal("1500.00"),
                "Initial payroll",
                "IDEMP-PAYROLL-1"
        );
        hrService.createPayrollRun(base);

        PayrollRunRequest conflict = new PayrollRunRequest(
                LocalDate.of(2026, 1, 10),
                new BigDecimal("1750.00"),
                "Adjusted payroll",
                "IDEMP-PAYROLL-1"
        );

        assertThatThrownBy(() -> hrService.createPayrollRun(conflict))
                .isInstanceOf(ApplicationException.class)
                .extracting(ex -> ((ApplicationException) ex).getErrorCode())
                .isEqualTo(ErrorCode.CONCURRENCY_CONFLICT);
    }

    @Test
    void legacySalesOrderMissingHashMatchesAndBackfills() {
        SalesOrder legacy = new SalesOrder();
        legacy.setCompany(company);
        legacy.setDealer(dealer);
        legacy.setOrderNumber("SO-LEG-1");
        legacy.setStatus("BOOKED");
        legacy.setCurrency("INR");
        legacy.setGstTreatment("NONE");
        legacy.setGstRate(BigDecimal.ZERO);
        legacy.setTotalAmount(new BigDecimal("100.00"));
        legacy.setIdempotencyKey("LEGACY-ORDER-1");

        SalesOrderItem item = new SalesOrderItem();
        item.setSalesOrder(legacy);
        item.setProductCode(finishedGood.getProductCode());
        item.setDescription(finishedGood.getName());
        item.setQuantity(new BigDecimal("1.00"));
        item.setUnitPrice(new BigDecimal("100.00"));
        item.setGstRate(BigDecimal.ZERO);
        legacy.getItems().add(item);

        SalesOrder saved = salesOrderRepository.save(legacy);

        SalesOrderRequest request = new SalesOrderRequest(
                dealer.getId(),
                new BigDecimal("100.00"),
                null,
                null,
                List.of(new SalesOrderItemRequest(
                        finishedGood.getProductCode(),
                        finishedGood.getName(),
                        new BigDecimal("1.00"),
                        new BigDecimal("100.00"),
                        null)),
                null,
                null,
                Boolean.FALSE,
                "LEGACY-ORDER-1"
        );

        assertThat(salesService.createOrder(request).id()).isEqualTo(saved.getId());
        SalesOrder reloaded = salesOrderRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getIdempotencyHash()).isNotBlank();

        SalesOrderRequest explicitDefaults = new SalesOrderRequest(
                dealer.getId(),
                new BigDecimal("100.00"),
                "INR",
                null,
                List.of(new SalesOrderItemRequest(
                        finishedGood.getProductCode(),
                        finishedGood.getName(),
                        new BigDecimal("1.00"),
                        new BigDecimal("100.00"),
                        BigDecimal.ZERO)),
                "NONE",
                BigDecimal.ZERO,
                Boolean.FALSE,
                "LEGACY-ORDER-1"
        );
        assertThat(salesService.createOrder(explicitDefaults).id()).isEqualTo(saved.getId());
    }

    @Test
    void legacySalesOrderMissingHashMismatchThrows() {
        SalesOrder legacy = new SalesOrder();
        legacy.setCompany(company);
        legacy.setDealer(dealer);
        legacy.setOrderNumber("SO-LEG-2");
        legacy.setStatus("BOOKED");
        legacy.setCurrency("INR");
        legacy.setGstTreatment("NONE");
        legacy.setGstRate(BigDecimal.ZERO);
        legacy.setTotalAmount(new BigDecimal("100.00"));
        legacy.setIdempotencyKey("LEGACY-ORDER-2");

        SalesOrderItem item = new SalesOrderItem();
        item.setSalesOrder(legacy);
        item.setProductCode(finishedGood.getProductCode());
        item.setDescription(finishedGood.getName());
        item.setQuantity(new BigDecimal("1.00"));
        item.setUnitPrice(new BigDecimal("100.00"));
        item.setGstRate(BigDecimal.ZERO);
        legacy.getItems().add(item);

        SalesOrder saved = salesOrderRepository.save(legacy);

        SalesOrderRequest conflict = new SalesOrderRequest(
                dealer.getId(),
                new BigDecimal("200.00"),
                "INR",
                "Conflict",
                List.of(new SalesOrderItemRequest(
                        finishedGood.getProductCode(),
                        finishedGood.getName(),
                        new BigDecimal("2.00"),
                        new BigDecimal("100.00"),
                        BigDecimal.ZERO)),
                "NONE",
                BigDecimal.ZERO,
                Boolean.FALSE,
                "LEGACY-ORDER-2"
        );

        assertThatThrownBy(() -> salesService.createOrder(conflict))
                .isInstanceOf(ApplicationException.class)
                .extracting(ex -> ((ApplicationException) ex).getErrorCode())
                .isEqualTo(ErrorCode.CONCURRENCY_CONFLICT);

        SalesOrder reloaded = salesOrderRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getIdempotencyHash()).isNull();
    }

    @Test
    void legacyPayrollRunMissingHashMatchesAndBackfills() {
        PayrollRun legacy = new PayrollRun();
        legacy.setCompany(company);
        legacy.setRunNumber("PR-LEG-1");
        legacy.setRunType(PayrollRun.RunType.MONTHLY);
        legacy.setPeriodStart(LocalDate.of(2026, 1, 1));
        legacy.setPeriodEnd(LocalDate.of(2026, 1, 31));
        legacy.setRunDate(LocalDate.of(2026, 1, 10));
        legacy.setNotes("Initial payroll");
        legacy.setTotalAmount(new BigDecimal("1500.00"));
        legacy.setIdempotencyKey("LEGACY-PAYROLL-1");
        PayrollRun saved = payrollRunRepository.save(legacy);

        PayrollRunRequest request = new PayrollRunRequest(
                LocalDate.of(2026, 1, 10),
                new BigDecimal("1500.00"),
                "Initial payroll",
                "LEGACY-PAYROLL-1"
        );

        assertThat(hrService.createPayrollRun(request).id()).isEqualTo(saved.getId());
        PayrollRun reloaded = payrollRunRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getIdempotencyHash()).isNotBlank();
    }

    @Test
    void legacyPayrollRunMissingHashMismatchThrows() {
        PayrollRun legacy = new PayrollRun();
        legacy.setCompany(company);
        legacy.setRunNumber("PR-LEG-2");
        legacy.setRunType(PayrollRun.RunType.MONTHLY);
        legacy.setPeriodStart(LocalDate.of(2026, 2, 1));
        legacy.setPeriodEnd(LocalDate.of(2026, 2, 28));
        legacy.setRunDate(LocalDate.of(2026, 2, 10));
        legacy.setNotes("Initial payroll");
        legacy.setTotalAmount(new BigDecimal("1500.00"));
        legacy.setIdempotencyKey("LEGACY-PAYROLL-2");
        PayrollRun saved = payrollRunRepository.save(legacy);

        PayrollRunRequest conflict = new PayrollRunRequest(
                LocalDate.of(2026, 2, 10),
                new BigDecimal("1750.00"),
                "Adjusted payroll",
                "LEGACY-PAYROLL-2"
        );

        assertThatThrownBy(() -> hrService.createPayrollRun(conflict))
                .isInstanceOf(ApplicationException.class)
                .extracting(ex -> ((ApplicationException) ex).getErrorCode())
                .isEqualTo(ErrorCode.CONCURRENCY_CONFLICT);

        PayrollRun reloaded = payrollRunRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getIdempotencyHash()).isNull();
    }
}
