package com.bigbrightpaints.erp.modules.hr.service;

import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.hr.domain.*;
import com.bigbrightpaints.erp.modules.hr.dto.*;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

@Service
public class HrService {

    private final CompanyContextService companyContextService;
    private final EmployeeRepository employeeRepository;
    private final LeaveRequestRepository leaveRequestRepository;
    private final PayrollRunRepository payrollRunRepository;

    public HrService(CompanyContextService companyContextService,
                     EmployeeRepository employeeRepository,
                     LeaveRequestRepository leaveRequestRepository,
                     PayrollRunRepository payrollRunRepository) {
        this.companyContextService = companyContextService;
        this.employeeRepository = employeeRepository;
        this.leaveRequestRepository = leaveRequestRepository;
        this.payrollRunRepository = payrollRunRepository;
    }

    /* Employees */
    public List<EmployeeDto> listEmployees() {
        Company company = companyContextService.requireCurrentCompany();
        return employeeRepository.findByCompanyOrderByFirstNameAsc(company).stream().map(this::toDto).toList();
    }

    @Transactional
    public EmployeeDto createEmployee(EmployeeRequest request) {
        Company company = companyContextService.requireCurrentCompany();
        Employee employee = new Employee();
        employee.setCompany(company);
        employee.setFirstName(request.firstName());
        employee.setLastName(request.lastName());
        employee.setEmail(request.email());
        employee.setRole(request.role());
        employee.setHiredDate(request.hiredDate());
        return toDto(employeeRepository.save(employee));
    }

    @Transactional
    public EmployeeDto updateEmployee(Long id, EmployeeRequest request) {
        Employee employee = requireEmployee(id);
        employee.setFirstName(request.firstName());
        employee.setLastName(request.lastName());
        employee.setEmail(request.email());
        employee.setRole(request.role());
        employee.setHiredDate(request.hiredDate());
        return toDto(employee);
    }

    public void deleteEmployee(Long id) {
        employeeRepository.delete(requireEmployee(id));
    }

    private Employee requireEmployee(Long id) {
        Company company = companyContextService.requireCurrentCompany();
        return employeeRepository.findByCompanyAndId(company, id)
                .orElseThrow(() -> new IllegalArgumentException("Employee not found"));
    }

    private EmployeeDto toDto(Employee employee) {
        return new EmployeeDto(employee.getId(), employee.getPublicId(), employee.getFirstName(), employee.getLastName(),
                employee.getEmail(), employee.getRole(), employee.getStatus(), employee.getHiredDate());
    }

    /* Leave Requests */
    public List<LeaveRequestDto> listLeaveRequests() {
        Company company = companyContextService.requireCurrentCompany();
        return leaveRequestRepository.findByCompanyOrderByCreatedAtDesc(company).stream()
                .map(this::toDto)
                .toList();
    }

    @Transactional
    public LeaveRequestDto createLeaveRequest(LeaveRequestRequest request) {
        Company company = companyContextService.requireCurrentCompany();
        LeaveRequest leaveRequest = new LeaveRequest();
        leaveRequest.setCompany(company);
        if (request.employeeId() != null) {
            leaveRequest.setEmployee(requireEmployee(request.employeeId()));
        }
        leaveRequest.setLeaveType(request.leaveType());
        leaveRequest.setStartDate(request.startDate());
        leaveRequest.setEndDate(request.endDate());
        leaveRequest.setReason(request.reason());
        if (request.status() != null) {
            leaveRequest.setStatus(request.status());
        }
        return toDto(leaveRequestRepository.save(leaveRequest));
    }

    @Transactional
    public LeaveRequestDto updateLeaveStatus(Long id, String status) {
        LeaveRequest leaveRequest = requireLeaveRequest(id);
        leaveRequest.setStatus(status);
        return toDto(leaveRequest);
    }

    private LeaveRequest requireLeaveRequest(Long id) {
        Company company = companyContextService.requireCurrentCompany();
        return leaveRequestRepository.findByCompanyAndId(company, id)
                .orElseThrow(() -> new IllegalArgumentException("Leave request not found"));
    }

    private LeaveRequestDto toDto(LeaveRequest leaveRequest) {
        String employeeName = leaveRequest.getEmployee() != null
                ? leaveRequest.getEmployee().getFirstName() + " " + leaveRequest.getEmployee().getLastName()
                : null;
        Long employeeId = leaveRequest.getEmployee() != null ? leaveRequest.getEmployee().getId() : null;
        return new LeaveRequestDto(leaveRequest.getId(), leaveRequest.getPublicId(), employeeId, employeeName,
                leaveRequest.getLeaveType(), leaveRequest.getStartDate(), leaveRequest.getEndDate(),
                leaveRequest.getStatus(), leaveRequest.getReason(), leaveRequest.getCreatedAt());
    }

    /* Payroll */
    public List<PayrollRunDto> listPayrollRuns() {
        Company company = companyContextService.requireCurrentCompany();
        return payrollRunRepository.findByCompanyOrderByRunDateDesc(company).stream().map(this::toDto).toList();
    }

    @Transactional
    public PayrollRunDto createPayrollRun(PayrollRunRequest request) {
        Company company = companyContextService.requireCurrentCompany();
        PayrollRun run = new PayrollRun();
        run.setCompany(company);
        run.setRunDate(request.runDate());
        run.setNotes(request.notes());
        if (request.totalAmount() != null) {
            run.setTotalAmount(request.totalAmount());
        }
        run.setStatus("COMPLETED");
        return toDto(payrollRunRepository.save(run));
    }

    private PayrollRunDto toDto(PayrollRun run) {
        Long journalEntryId = run.getJournalEntry() != null ? run.getJournalEntry().getId() : null;
        return new PayrollRunDto(
                run.getId(),
                run.getPublicId(),
                run.getRunDate(),
                run.getStatus(),
                run.getProcessedBy(),
                run.getNotes(),
                run.getTotalAmount(),
                journalEntryId);
    }
}
