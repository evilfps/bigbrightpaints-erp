package com.bigbrightpaints.erp.regression;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.core.security.CompanyContextHolder;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.hr.domain.PayrollRun;
import com.bigbrightpaints.erp.modules.hr.service.PayrollService;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGood;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodRepository;
import com.bigbrightpaints.erp.modules.sales.domain.Dealer;
import com.bigbrightpaints.erp.modules.sales.domain.DealerRepository;
import com.bigbrightpaints.erp.modules.sales.dto.SalesOrderItemRequest;
import com.bigbrightpaints.erp.modules.sales.dto.SalesOrderRequest;
import com.bigbrightpaints.erp.modules.sales.service.SalesService;
import com.bigbrightpaints.erp.test.AbstractIntegrationTest;

@DisplayName("Regression: Idempotency conflicts are rejected")
class IdempotencyConflictRegressionIT extends AbstractIntegrationTest {

  private static final String COMPANY_CODE = "LF-023";

  @Autowired private SalesService salesService;

  @Autowired private DealerRepository dealerRepository;

  @Autowired private FinishedGoodRepository finishedGoodRepository;

  @Autowired private PayrollService payrollService;

  private Company company;
  private Dealer dealer;
  private FinishedGood finishedGood;

  @BeforeEach
  void setUp() {
    company = dataSeeder.ensureCompany(COMPANY_CODE, "LF-023 Ltd");
    CompanyContextHolder.setCompanyCode(COMPANY_CODE);
    dealer = dealerRepository.findByCompanyAndCodeIgnoreCase(company, "FIX-DEALER").orElseThrow();
    finishedGood =
        finishedGoodRepository.findByCompanyAndProductCode(company, "FG-FIXTURE").orElseThrow();
  }

  @AfterEach
  void tearDown() {
    CompanyContextHolder.clear();
  }

  @Test
  void salesOrderConflictingPayloadThrows() {
    SalesOrderRequest base =
        new SalesOrderRequest(
            dealer.getId(),
            new BigDecimal("100.00"),
            "INR",
            "Initial order",
            List.of(
                new SalesOrderItemRequest(
                    finishedGood.getProductCode(),
                    finishedGood.getName(),
                    new BigDecimal("1.00"),
                    new BigDecimal("100.00"),
                    BigDecimal.ZERO)),
            "NONE",
            BigDecimal.ZERO,
            Boolean.FALSE,
            "IDEMP-ORDER-1");
    salesService.createOrder(base);

    SalesOrderRequest conflict =
        new SalesOrderRequest(
            dealer.getId(),
            new BigDecimal("200.00"),
            "INR",
            "Updated order",
            List.of(
                new SalesOrderItemRequest(
                    finishedGood.getProductCode(),
                    finishedGood.getName(),
                    new BigDecimal("2.00"),
                    new BigDecimal("100.00"),
                    BigDecimal.ZERO)),
            "NONE",
            BigDecimal.ZERO,
            Boolean.FALSE,
            "IDEMP-ORDER-1");

    assertThatThrownBy(() -> salesService.createOrder(conflict))
        .isInstanceOf(ApplicationException.class)
        .extracting(ex -> ((ApplicationException) ex).getErrorCode())
        .isEqualTo(ErrorCode.CONCURRENCY_CONFLICT);
  }

  @Test
  void payrollRunConflictingPayloadThrows() {
    LocalDate start = LocalDate.of(2026, 1, 1);
    LocalDate end = LocalDate.of(2026, 1, 31);
    payrollService.createPayrollRun(
        new PayrollService.CreatePayrollRunRequest(
            PayrollRun.RunType.MONTHLY, start, end, "Initial payroll"));

    assertThatThrownBy(
            () ->
                payrollService.createPayrollRun(
                    new PayrollService.CreatePayrollRunRequest(
                        PayrollRun.RunType.MONTHLY, start, end, "Adjusted payroll")))
        .isInstanceOf(ApplicationException.class)
        .extracting(ex -> ((ApplicationException) ex).getErrorCode())
        .isEqualTo(ErrorCode.CONCURRENCY_CONFLICT);
  }
}
