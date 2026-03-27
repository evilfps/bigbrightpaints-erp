package com.bigbrightpaints.erp.modules.hr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.core.security.CompanyContextHolder;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.hr.domain.PayrollRun;
import com.bigbrightpaints.erp.modules.hr.domain.PayrollRunRepository;
import com.bigbrightpaints.erp.modules.hr.service.PayrollService;
import com.bigbrightpaints.erp.test.AbstractIntegrationTest;

@DisplayName("HR: Payroll run idempotency scope")
public class PayrollRunIdempotencyIT extends AbstractIntegrationTest {

  @Autowired private PayrollService payrollService;

  @Autowired private PayrollRunRepository payrollRunRepository;

  @AfterEach
  void clearContext() {
    CompanyContextHolder.clear();
  }

  @Test
  void idempotency_key_is_scoped_by_company() {
    Company companyA = dataSeeder.ensureCompany("IDEMP-A", "Idempotency A Co");
    Company companyB = dataSeeder.ensureCompany("IDEMP-B", "Idempotency B Co");
    LocalDate start = LocalDate.now().minusDays(7);
    LocalDate end = LocalDate.now().minusDays(1);

    CompanyContextHolder.setCompanyCode(companyA.getCode());
    PayrollService.PayrollRunDto runA1 =
        payrollService.createPayrollRun(
            new PayrollService.CreatePayrollRunRequest(
                PayrollRun.RunType.WEEKLY, start, end, "Payroll run A"));
    PayrollService.PayrollRunDto runA2 =
        payrollService.createPayrollRun(
            new PayrollService.CreatePayrollRunRequest(
                PayrollRun.RunType.WEEKLY, start, end, "Payroll run A"));
    assertThat(runA2.id()).isEqualTo(runA1.id());

    assertThatThrownBy(
            () ->
                payrollService.createPayrollRun(
                    new PayrollService.CreatePayrollRunRequest(
                        PayrollRun.RunType.WEEKLY, start, end, "Payroll run A updated")))
        .isInstanceOf(ApplicationException.class)
        .extracting(ex -> ((ApplicationException) ex).getErrorCode())
        .isEqualTo(ErrorCode.CONCURRENCY_CONFLICT);

    CompanyContextHolder.setCompanyCode(companyB.getCode());
    PayrollService.PayrollRunDto runB1 =
        payrollService.createPayrollRun(
            new PayrollService.CreatePayrollRunRequest(
                PayrollRun.RunType.WEEKLY, start, end, "Payroll run B"));
    assertThat(runB1.id()).isNotEqualTo(runA1.id());

    String idempotencyKey =
        "PAYROLL:%s:%s:%s".formatted(PayrollRun.RunType.WEEKLY.name(), start, end);
    assertThat(payrollRunRepository.findByCompanyAndIdempotencyKey(companyA, idempotencyKey))
        .isPresent();
    assertThat(payrollRunRepository.findByCompanyAndIdempotencyKey(companyB, idempotencyKey))
        .isPresent();
  }

  @Test
  void posted_run_without_journal_link_fails_closed() {
    Company company = dataSeeder.ensureCompany("IDEMP-POST-NOLINK", "Idempotency Posted Co");
    LocalDate periodStart = LocalDate.now().minusDays(14);
    LocalDate periodEnd = LocalDate.now().minusDays(8);

    PayrollRun run = new PayrollRun();
    run.setCompany(company);
    run.setRunType(PayrollRun.RunType.WEEKLY);
    run.setPeriodStart(periodStart);
    run.setPeriodEnd(periodEnd);
    run.setRunDate(periodEnd);
    run.setRunNumber("PR-W-FAIL-CLOSED-" + System.nanoTime());
    run.setStatus(PayrollRun.PayrollStatus.POSTED);
    run.setCreatedBy("SYSTEM");
    PayrollRun saved = payrollRunRepository.save(run);

    CompanyContextHolder.setCompanyCode(company.getCode());
    assertThatThrownBy(() -> payrollService.postPayrollToAccounting(saved.getId()))
        .isInstanceOf(ApplicationException.class)
        .satisfies(
            ex -> {
              ApplicationException appEx = (ApplicationException) ex;
              assertThat(appEx.getErrorCode()).isEqualTo(ErrorCode.BUSINESS_INVALID_STATE);
              assertThat(appEx.getMessage()).contains("missing posting journal linkage");
              assertThat(appEx.getDetails())
                  .containsEntry("payrollRunId", saved.getId())
                  .containsEntry("currentStatus", PayrollRun.PayrollStatus.POSTED.name());
            });
  }
}
