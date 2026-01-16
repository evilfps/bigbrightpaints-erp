package com.bigbrightpaints.erp.modules.hr.service;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.core.util.CompanyEntityLookup;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.hr.domain.*;
import com.bigbrightpaints.erp.modules.hr.dto.*;
import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

@Service
public class HrService {

    private final CompanyContextService companyContextService;
    private final EmployeeRepository employeeRepository;
    private final LeaveRequestRepository leaveRequestRepository;
    private final PayrollRunRepository payrollRunRepository;
    private final AttendanceRepository attendanceRepository;
    private final CompanyEntityLookup companyEntityLookup;

    public HrService(CompanyContextService companyContextService,
                     EmployeeRepository employeeRepository,
                     LeaveRequestRepository leaveRequestRepository,
                     PayrollRunRepository payrollRunRepository,
                     AttendanceRepository attendanceRepository,
                     CompanyEntityLookup companyEntityLookup) {
        this.companyContextService = companyContextService;
        this.employeeRepository = employeeRepository;
        this.leaveRequestRepository = leaveRequestRepository;
        this.payrollRunRepository = payrollRunRepository;
        this.attendanceRepository = attendanceRepository;
        this.companyEntityLookup = companyEntityLookup;
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
        
        // Payroll fields
        if (request.phone() != null) employee.setPhone(request.phone());
        if (request.employeeType() != null) {
            employee.setEmployeeType(Employee.EmployeeType.valueOf(request.employeeType()));
        }
        if (request.paymentSchedule() != null) {
            employee.setPaymentSchedule(Employee.PaymentSchedule.valueOf(request.paymentSchedule()));
        }
        if (request.monthlySalary() != null) employee.setMonthlySalary(request.monthlySalary());
        if (request.dailyWage() != null) employee.setDailyWage(request.dailyWage());
        if (request.workingDaysPerMonth() != null) employee.setWorkingDaysPerMonth(request.workingDaysPerMonth());
        if (request.weeklyOffDays() != null) employee.setWeeklyOffDays(request.weeklyOffDays());
        if (request.standardHoursPerDay() != null) employee.setStandardHoursPerDay(request.standardHoursPerDay());
        if (request.overtimeRateMultiplier() != null) employee.setOvertimeRateMultiplier(request.overtimeRateMultiplier());
        if (request.doubleOtRateMultiplier() != null) employee.setDoubleOtRateMultiplier(request.doubleOtRateMultiplier());
        
        // Bank details
        if (request.bankAccountNumber() != null) employee.setBankAccountNumber(request.bankAccountNumber());
        if (request.bankName() != null) employee.setBankName(request.bankName());
        if (request.ifscCode() != null) employee.setIfscCode(request.ifscCode());
        
        return toDto(employeeRepository.save(employee));
    }

    @Transactional
    public EmployeeDto updateEmployee(Long id, EmployeeRequest request) {
        Company company = companyContextService.requireCurrentCompany();
        Employee employee = employeeRepository.lockByCompanyAndId(company, id)
                .orElseThrow(() -> new ApplicationException(ErrorCode.VALIDATION_INVALID_REFERENCE, "Employee not found"));
        employee.setFirstName(request.firstName());
        employee.setLastName(request.lastName());
        employee.setEmail(request.email());
        employee.setRole(request.role());
        employee.setHiredDate(request.hiredDate());
        
        // Payroll fields
        if (request.phone() != null) employee.setPhone(request.phone());
        if (request.employeeType() != null) {
            employee.setEmployeeType(Employee.EmployeeType.valueOf(request.employeeType()));
        }
        if (request.paymentSchedule() != null) {
            employee.setPaymentSchedule(Employee.PaymentSchedule.valueOf(request.paymentSchedule()));
        }
        if (request.monthlySalary() != null) employee.setMonthlySalary(request.monthlySalary());
        if (request.dailyWage() != null) employee.setDailyWage(request.dailyWage());
        if (request.workingDaysPerMonth() != null) employee.setWorkingDaysPerMonth(request.workingDaysPerMonth());
        if (request.weeklyOffDays() != null) employee.setWeeklyOffDays(request.weeklyOffDays());
        if (request.standardHoursPerDay() != null) employee.setStandardHoursPerDay(request.standardHoursPerDay());
        if (request.overtimeRateMultiplier() != null) employee.setOvertimeRateMultiplier(request.overtimeRateMultiplier());
        if (request.doubleOtRateMultiplier() != null) employee.setDoubleOtRateMultiplier(request.doubleOtRateMultiplier());
        
        // Bank details
        if (request.bankAccountNumber() != null) employee.setBankAccountNumber(request.bankAccountNumber());
        if (request.bankName() != null) employee.setBankName(request.bankName());
        if (request.ifscCode() != null) employee.setIfscCode(request.ifscCode());
        
        return toDto(employeeRepository.save(employee));
    }

    public void deleteEmployee(Long id) {
        employeeRepository.delete(requireEmployee(id));
    }

    private Employee requireEmployee(Long id) {
        Company company = companyContextService.requireCurrentCompany();
        return companyEntityLookup.requireEmployee(company, id);
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
        if (request.employeeId() != null) {
            Employee employee = employeeRepository.lockByCompanyAndId(company, request.employeeId())
                    .orElseThrow(() -> new ApplicationException(ErrorCode.VALIDATION_INVALID_REFERENCE, "Employee not found"));
            if (leaveRequestRepository.existsOverlappingByEmployeeIdAndDates(request.employeeId(), request.startDate(), request.endDate())) {
                throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT, "Overlapping leave request exists for employee");
            }
        }
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
        return companyEntityLookup.requireLeaveRequest(company, id);
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
        String idempotencyKey = request.idempotencyKey();
        String requestSignature = null;
        if (StringUtils.hasText(idempotencyKey)) {
            requestSignature = buildPayrollRunSignature(request);
            Optional<PayrollRun> existing = payrollRunRepository.findByCompanyAndIdempotencyKey(company, idempotencyKey);
            if (existing.isPresent()) {
                PayrollRun run = existing.get();
                String storedSignature = run.getIdempotencyHash();
                if (!StringUtils.hasText(storedSignature)) {
                    String derivedSignature = buildPayrollRunSignature(run);
                    if (!derivedSignature.equals(requestSignature)) {
                        throw new ApplicationException(ErrorCode.CONCURRENCY_CONFLICT,
                                "Idempotency key already used with different payload")
                                .withDetail("idempotencyKey", idempotencyKey);
                    }
                    run.setIdempotencyHash(requestSignature);
                    payrollRunRepository.save(run);
                    return toDto(run);
                }
                if (!storedSignature.equals(requestSignature)) {
                    throw new ApplicationException(ErrorCode.CONCURRENCY_CONFLICT,
                            "Idempotency key already used with different payload")
                            .withDetail("idempotencyKey", idempotencyKey);
                }
                return toDto(run);
            }
        }
        PayrollRun run = new PayrollRun();
        run.setCompany(company);
        run.setRunDate(request.runDate());
        run.setNotes(request.notes());
        if (request.totalAmount() != null) {
            run.setTotalAmount(request.totalAmount());
        }
        run.setStatus("DRAFT");
        run.setIdempotencyKey(idempotencyKey);
        run.setIdempotencyHash(requestSignature);
        PayrollRun savedRun = payrollRunRepository.save(run);
        return toDto(savedRun);
    }

    private PayrollRunDto toDto(PayrollRun run) {
        Long journalEntryId = run.getJournalEntry() != null
                ? run.getJournalEntry().getId()
                : run.getJournalEntryId();
        return new PayrollRunDto(
                run.getId(),
                run.getPublicId(),
                run.getRunDate(),
                run.getStatusString(),
                run.getProcessedBy(),
                run.getNotes(),
                run.getTotalAmount(),
                journalEntryId,
                run.getIdempotencyKey());
    }

    private String buildPayrollRunSignature(PayrollRunRequest request) {
        StringBuilder signature = new StringBuilder();
        signature.append(request.runDate() != null ? request.runDate() : "")
                .append('|').append(amountToken(request.totalAmount()))
                .append('|').append(normalizeText(request.notes()));
        return DigestUtils.sha256Hex(signature.toString());
    }

    private String buildPayrollRunSignature(PayrollRun run) {
        StringBuilder signature = new StringBuilder();
        signature.append(run.getRunDate() != null ? run.getRunDate() : "")
                .append('|').append(amountToken(run.getTotalAmount()))
                .append('|').append(normalizeText(run.getNotes()));
        return DigestUtils.sha256Hex(signature.toString());
    }

    private String amountToken(BigDecimal value) {
        return value == null ? "0" : value.stripTrailingZeros().toPlainString();
    }

    private String normalizeText(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        return value.trim().toUpperCase();
    }

    /* ===== Attendance Management ===== */

    /**
     * List attendance for a specific date.
     */
    public List<AttendanceDto> listAttendanceByDate(java.time.LocalDate date) {
        Company company = companyContextService.requireCurrentCompany();
        return attendanceRepository.findByCompanyAndAttendanceDateOrderByEmployeeFirstNameAsc(company, date)
                .stream()
                .map(this::toAttendanceDto)
                .toList();
    }

    /**
     * List attendance for an employee in a date range.
     */
    public List<AttendanceDto> listEmployeeAttendance(Long employeeId, java.time.LocalDate startDate, java.time.LocalDate endDate) {
        Company company = companyContextService.requireCurrentCompany();
        Employee employee = companyEntityLookup.requireEmployee(company, employeeId);
        return attendanceRepository.findByCompanyAndEmployeeAndAttendanceDateBetweenOrderByAttendanceDateAsc(
                company, employee, startDate, endDate)
                .stream()
                .map(this::toAttendanceDto)
                .toList();
    }

    /**
     * Mark attendance for a single employee.
     */
    @Transactional
    public AttendanceDto markAttendance(Long employeeId, MarkAttendanceRequest request) {
        Company company = companyContextService.requireCurrentCompany();
        Employee employee = companyEntityLookup.requireEmployee(company, employeeId);
        java.time.LocalDate date = request.date() != null ? request.date() : java.time.LocalDate.now();
        
        // Check if already marked, update if exists
        Attendance attendance = attendanceRepository.findByCompanyAndEmployeeAndAttendanceDate(company, employee, date)
                .orElseGet(() -> {
                    Attendance a = new Attendance();
                    a.setCompany(company);
                    a.setEmployee(employee);
                    a.setAttendanceDate(date);
                    return a;
                });
        
        attendance.setStatus(Attendance.AttendanceStatus.valueOf(request.status()));
        if (request.checkInTime() != null) attendance.setCheckInTime(request.checkInTime());
        if (request.checkOutTime() != null) attendance.setCheckOutTime(request.checkOutTime());
        if (request.regularHours() != null) attendance.setRegularHours(request.regularHours());
        if (request.overtimeHours() != null) attendance.setOvertimeHours(request.overtimeHours());
        if (request.doubleOvertimeHours() != null) attendance.setDoubleOvertimeHours(request.doubleOvertimeHours());
        attendance.setHoliday(request.holiday());
        attendance.setWeekend(request.weekend());
        if (request.remarks() != null) attendance.setRemarks(request.remarks());
        attendance.setMarkedBy(getCurrentUser());
        attendance.setMarkedAt(java.time.Instant.now());
        
        return toAttendanceDto(attendanceRepository.save(attendance));
    }

    /**
     * Bulk mark attendance for multiple employees.
     */
    @Transactional
    public List<AttendanceDto> bulkMarkAttendance(BulkMarkAttendanceRequest request) {
        Company company = companyContextService.requireCurrentCompany();
        String markedBy = getCurrentUser();
        java.time.Instant markedAt = java.time.Instant.now();
        
        return request.employeeIds().stream()
                .map(employeeId -> {
                    Employee employee = companyEntityLookup.requireEmployee(company, employeeId);
                    
                    Attendance attendance = attendanceRepository.findByCompanyAndEmployeeAndAttendanceDate(
                            company, employee, request.date())
                            .orElseGet(() -> {
                                Attendance a = new Attendance();
                                a.setCompany(company);
                                a.setEmployee(employee);
                                a.setAttendanceDate(request.date());
                                return a;
                            });
                    
                    attendance.setStatus(Attendance.AttendanceStatus.valueOf(request.status()));
                    if (request.checkInTime() != null) attendance.setCheckInTime(request.checkInTime());
                    if (request.checkOutTime() != null) attendance.setCheckOutTime(request.checkOutTime());
                    if (request.regularHours() != null) attendance.setRegularHours(request.regularHours());
                    if (request.overtimeHours() != null) attendance.setOvertimeHours(request.overtimeHours());
                    if (request.remarks() != null) attendance.setRemarks(request.remarks());
                    attendance.setMarkedBy(markedBy);
                    attendance.setMarkedAt(markedAt);
                    
                    return toAttendanceDto(attendanceRepository.save(attendance));
                })
                .toList();
    }

    /**
     * Get today's attendance summary.
     */
    public AttendanceSummaryDto getTodayAttendanceSummary() {
        Company company = companyContextService.requireCurrentCompany();
        java.time.LocalDate today = java.time.LocalDate.now();
        
        List<Attendance> attendances = attendanceRepository.findByCompanyAndAttendanceDateOrderByEmployeeFirstNameAsc(company, today);
        long totalEmployees = employeeRepository.countByCompanyAndStatus(company, "ACTIVE");
        long present = attendances.stream().filter(a -> a.getStatus() == Attendance.AttendanceStatus.PRESENT).count();
        long absent = attendances.stream().filter(a -> a.getStatus() == Attendance.AttendanceStatus.ABSENT).count();
        long halfDay = attendances.stream().filter(a -> a.getStatus() == Attendance.AttendanceStatus.HALF_DAY).count();
        long leave = attendances.stream().filter(a -> a.getStatus() == Attendance.AttendanceStatus.LEAVE).count();
        long notMarked = totalEmployees - attendances.size();
        
        return new AttendanceSummaryDto(today, totalEmployees, present, absent, halfDay, leave, notMarked);
    }

    private AttendanceDto toAttendanceDto(Attendance a) {
        Employee emp = a.getEmployee();
        return new AttendanceDto(
                a.getId(),
                emp.getId(),
                emp.getFirstName() + " " + emp.getLastName(),
                emp.getEmployeeType() != null ? emp.getEmployeeType().name() : null,
                a.getAttendanceDate(),
                a.getStatus().name(),
                a.getCheckInTime(),
                a.getCheckOutTime(),
                a.getRegularHours(),
                a.getOvertimeHours(),
                a.getDoubleOvertimeHours(),
                a.isHoliday(),
                a.isWeekend(),
                a.getRemarks(),
                a.getMarkedBy(),
                a.getMarkedAt()
        );
    }

    private String getCurrentUser() {
        var auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        return auth != null ? auth.getName() : "SYSTEM";
    }
}
