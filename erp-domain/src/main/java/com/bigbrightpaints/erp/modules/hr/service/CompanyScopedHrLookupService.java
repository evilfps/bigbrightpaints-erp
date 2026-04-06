package com.bigbrightpaints.erp.modules.hr.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.bigbrightpaints.erp.core.util.CompanyEntityLookup;
import com.bigbrightpaints.erp.core.util.CompanyScopedLookupService;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.hr.domain.Employee;
import com.bigbrightpaints.erp.modules.hr.domain.EmployeeRepository;
import com.bigbrightpaints.erp.modules.hr.domain.LeaveRequest;
import com.bigbrightpaints.erp.modules.hr.domain.LeaveRequestRepository;
import com.bigbrightpaints.erp.modules.hr.domain.PayrollRun;
import com.bigbrightpaints.erp.modules.hr.domain.PayrollRunRepository;

@Service
public class CompanyScopedHrLookupService {

  private final CompanyEntityLookup legacyLookup;
  private final CompanyScopedLookupService companyScopedLookupService;
  private final EmployeeRepository employeeRepository;
  private final LeaveRequestRepository leaveRequestRepository;
  private final PayrollRunRepository payrollRunRepository;

  @Autowired
  public CompanyScopedHrLookupService(
      CompanyScopedLookupService companyScopedLookupService,
      EmployeeRepository employeeRepository,
      LeaveRequestRepository leaveRequestRepository,
      PayrollRunRepository payrollRunRepository) {
    this.legacyLookup = null;
    this.companyScopedLookupService = companyScopedLookupService;
    this.employeeRepository = employeeRepository;
    this.leaveRequestRepository = leaveRequestRepository;
    this.payrollRunRepository = payrollRunRepository;
  }

  private CompanyScopedHrLookupService(CompanyEntityLookup legacyLookup) {
    this.legacyLookup = legacyLookup;
    this.companyScopedLookupService = null;
    this.employeeRepository = null;
    this.leaveRequestRepository = null;
    this.payrollRunRepository = null;
  }

  public static CompanyScopedHrLookupService fromLegacy(CompanyEntityLookup legacyLookup) {
    return new CompanyScopedHrLookupService(legacyLookup);
  }

  public Employee requireEmployee(Company company, Long employeeId) {
    if (legacyLookup != null) {
      return legacyLookup.requireEmployee(company, employeeId);
    }
    return companyScopedLookupService.require(
        company, employeeId, employeeRepository::findByCompanyAndId, "Employee");
  }

  public LeaveRequest requireLeaveRequest(Company company, Long leaveRequestId) {
    if (legacyLookup != null) {
      return legacyLookup.requireLeaveRequest(company, leaveRequestId);
    }
    return companyScopedLookupService.require(
        company, leaveRequestId, leaveRequestRepository::findByCompanyAndId, "Leave request");
  }

  public PayrollRun lockPayrollRun(Company company, Long payrollRunId) {
    if (legacyLookup != null) {
      return legacyLookup.lockPayrollRun(company, payrollRunId);
    }
    return companyScopedLookupService.require(
        company, payrollRunId, payrollRunRepository::lockByCompanyAndId, "Payroll run");
  }
}
