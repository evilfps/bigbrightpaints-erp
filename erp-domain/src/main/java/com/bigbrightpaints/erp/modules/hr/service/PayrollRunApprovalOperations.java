package com.bigbrightpaints.erp.modules.hr.service;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.core.util.CompanyTime;
import com.bigbrightpaints.erp.core.validation.ValidationUtils;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.hr.domain.PayrollRun;
import com.bigbrightpaints.erp.modules.hr.domain.PayrollRunLineRepository;
import com.bigbrightpaints.erp.modules.hr.domain.PayrollRunRepository;
import com.bigbrightpaints.erp.modules.hr.service.PayrollService.PayrollRunDto;

final class PayrollRunApprovalOperations {

  private final PayrollRunRepository payrollRunRepository;
  private final PayrollRunLineRepository payrollRunLineRepository;
  private final CompanyContextService companyContextService;

  PayrollRunApprovalOperations(
      PayrollRunRepository payrollRunRepository,
      PayrollRunLineRepository payrollRunLineRepository,
      CompanyContextService companyContextService) {
    this.payrollRunRepository = payrollRunRepository;
    this.payrollRunLineRepository = payrollRunLineRepository;
    this.companyContextService = companyContextService;
  }

  PayrollRunDto approvePayroll(Long payrollRunId, String currentUser) {
    Company company = companyContextService.requireCurrentCompany();
    PayrollRun run =
        payrollRunRepository
            .findByCompanyAndId(company, payrollRunId)
            .orElseThrow(() -> ValidationUtils.invalidInput("Payroll run not found"));

    if (run.getStatus() != PayrollRun.PayrollStatus.CALCULATED) {
      throw new ApplicationException(
              ErrorCode.BUSINESS_INVALID_STATE, "Can only approve payroll in CALCULATED status")
          .withDetail("payrollRunId", payrollRunId)
          .withDetail("currentStatus", run.getStatus().name());
    }
    if (payrollRunLineRepository.findByPayrollRun(run).isEmpty()) {
      throw new ApplicationException(
              ErrorCode.BUSINESS_INVALID_STATE,
              "Cannot approve payroll run with no calculated lines")
          .withDetail("payrollRunId", payrollRunId);
    }

    run.setStatus(PayrollRun.PayrollStatus.APPROVED);
    run.setApprovedBy(currentUser);
    run.setApprovedAt(CompanyTime.now(company));
    run.setProcessedBy(currentUser);

    payrollRunRepository.save(run);
    return PayrollService.toDto(run);
  }
}
