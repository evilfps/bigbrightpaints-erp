package com.bigbrightpaints.erp.modules.hr.service;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.core.security.CryptoService;
import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.core.util.CompanyEntityLookup;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.hr.domain.AttendanceRepository;
import com.bigbrightpaints.erp.modules.hr.domain.EmployeeRepository;
import com.bigbrightpaints.erp.modules.hr.domain.LeaveBalanceRepository;
import com.bigbrightpaints.erp.modules.hr.domain.LeaveRequestRepository;
import com.bigbrightpaints.erp.modules.hr.domain.LeaveTypePolicyRepository;
import com.bigbrightpaints.erp.modules.hr.domain.SalaryStructureTemplateRepository;
import com.bigbrightpaints.erp.modules.hr.dto.AttendanceBulkImportRequest;
import com.bigbrightpaints.erp.modules.hr.dto.AttendanceDto;
import com.bigbrightpaints.erp.modules.hr.dto.AttendanceSummaryDto;
import com.bigbrightpaints.erp.modules.hr.dto.BulkMarkAttendanceRequest;
import com.bigbrightpaints.erp.modules.hr.dto.EmployeeDto;
import com.bigbrightpaints.erp.modules.hr.dto.EmployeeRequest;
import com.bigbrightpaints.erp.modules.hr.dto.LeaveBalanceDto;
import com.bigbrightpaints.erp.modules.hr.dto.LeaveRequestDto;
import com.bigbrightpaints.erp.modules.hr.dto.LeaveRequestRequest;
import com.bigbrightpaints.erp.modules.hr.dto.LeaveStatusUpdateRequest;
import com.bigbrightpaints.erp.modules.hr.dto.LeaveTypePolicyDto;
import com.bigbrightpaints.erp.modules.hr.dto.MarkAttendanceRequest;
import com.bigbrightpaints.erp.modules.hr.dto.MonthlyAttendanceSummaryDto;
import com.bigbrightpaints.erp.modules.hr.dto.PayrollRunDto;
import com.bigbrightpaints.erp.modules.hr.dto.PayrollRunRequest;
import com.bigbrightpaints.erp.modules.hr.dto.SalaryStructureTemplateDto;
import com.bigbrightpaints.erp.modules.hr.dto.SalaryStructureTemplateRequest;
import java.time.LocalDate;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class HrService {

    private final EmployeeService employeeService;
    private final LeaveService leaveService;
    private final AttendanceService attendanceService;
    private final SalaryStructureTemplateService salaryStructureTemplateService;

    @Autowired
    public HrService(EmployeeService employeeService,
                     LeaveService leaveService,
                     AttendanceService attendanceService,
                     SalaryStructureTemplateService salaryStructureTemplateService) {
        this.employeeService = employeeService;
        this.leaveService = leaveService;
        this.attendanceService = attendanceService;
        this.salaryStructureTemplateService = salaryStructureTemplateService;
    }


    @SuppressWarnings("unused")
    public HrService(CompanyContextService companyContextService,
                     EmployeeRepository employeeRepository,
                     LeaveRequestRepository leaveRequestRepository,
                     AttendanceRepository attendanceRepository,
                     CompanyEntityLookup companyEntityLookup,
                     CompanyClock companyClock,
                     SalaryStructureTemplateRepository salaryStructureTemplateRepository,
                     LeaveTypePolicyRepository leaveTypePolicyRepository,
                     LeaveBalanceRepository leaveBalanceRepository,
                     CryptoService cryptoService) {
        this(
                new EmployeeService(
                        companyContextService,
                        employeeRepository,
                        companyEntityLookup,
                        salaryStructureTemplateRepository,
                        cryptoService),
                new LeaveService(
                        companyContextService,
                        employeeRepository,
                        leaveRequestRepository,
                        companyEntityLookup,
                        leaveTypePolicyRepository,
                        leaveBalanceRepository),
                new AttendanceService(companyContextService, attendanceRepository, employeeRepository, companyEntityLookup, companyClock),
                new SalaryStructureTemplateService(companyContextService, salaryStructureTemplateRepository)
        );
    }

    public List<EmployeeDto> listEmployees() {
        return employeeService.listEmployees();
    }

    @Transactional
    public EmployeeDto createEmployee(EmployeeRequest request) {
        return employeeService.createEmployee(request);
    }

    @Transactional
    public EmployeeDto updateEmployee(Long id, EmployeeRequest request) {
        return employeeService.updateEmployee(id, request);
    }

    @Transactional
    public void deleteEmployee(Long id) {
        employeeService.deleteEmployee(id);
    }

    public List<SalaryStructureTemplateDto> listSalaryStructureTemplates() {
        return salaryStructureTemplateService.listTemplates();
    }

    @Transactional
    public SalaryStructureTemplateDto createSalaryStructureTemplate(SalaryStructureTemplateRequest request) {
        return salaryStructureTemplateService.createTemplate(request);
    }

    @Transactional
    public SalaryStructureTemplateDto updateSalaryStructureTemplate(Long id, SalaryStructureTemplateRequest request) {
        return salaryStructureTemplateService.updateTemplate(id, request);
    }

    public List<LeaveRequestDto> listLeaveRequests() {
        return leaveService.listLeaveRequests();
    }

    public List<LeaveTypePolicyDto> listLeaveTypePolicies() {
        return leaveService.listLeaveTypePolicies();
    }

    public List<LeaveBalanceDto> getLeaveBalances(Long employeeId, Integer year) {
        return leaveService.getLeaveBalances(employeeId, year);
    }

    @Transactional
    public LeaveRequestDto createLeaveRequest(LeaveRequestRequest request) {
        return leaveService.createLeaveRequest(request);
    }

    @Transactional
    public LeaveRequestDto updateLeaveStatus(Long id, LeaveStatusUpdateRequest request) {
        return leaveService.updateLeaveStatus(id, request);
    }

    @Transactional
    public LeaveRequestDto updateLeaveStatus(Long id, String status) {
        return leaveService.updateLeaveStatus(id, new LeaveStatusUpdateRequest(status, null));
    }

    @Deprecated
    @Transactional
    public PayrollRunDto createPayrollRun(PayrollRunRequest request) {
        throw new ApplicationException(ErrorCode.BUSINESS_CONSTRAINT_VIOLATION,
                "Legacy payroll run creation is deprecated; use /api/v1/payroll/runs")
                .withDetail("canonicalPath", "/api/v1/payroll/runs");
    }

    public List<AttendanceDto> listAttendanceByDate(LocalDate date) {
        return attendanceService.listAttendanceByDate(date);
    }

    public List<AttendanceDto> listEmployeeAttendance(Long employeeId,
                                                      LocalDate startDate,
                                                      LocalDate endDate) {
        return attendanceService.listEmployeeAttendance(employeeId, startDate, endDate);
    }

    @Transactional
    public AttendanceDto markAttendance(Long employeeId, MarkAttendanceRequest request) {
        return attendanceService.markAttendance(employeeId, request);
    }

    @Transactional
    public List<AttendanceDto> bulkMarkAttendance(BulkMarkAttendanceRequest request) {
        return attendanceService.bulkMarkAttendance(request);
    }

    @Transactional
    public List<AttendanceDto> bulkImportAttendance(AttendanceBulkImportRequest request) {
        return attendanceService.bulkImportAttendance(request);
    }

    public AttendanceSummaryDto getTodayAttendanceSummary() {
        return attendanceService.getTodayAttendanceSummary();
    }

    public List<MonthlyAttendanceSummaryDto> getMonthlyAttendanceSummary(int year, int month) {
        return attendanceService.getMonthlyAttendanceSummary(year, month);
    }
}
