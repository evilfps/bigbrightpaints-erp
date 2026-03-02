package com.bigbrightpaints.erp.modules.hr.service;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.core.util.CompanyEntityLookup;
import com.bigbrightpaints.erp.core.util.CompanyTime;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.hr.domain.Attendance;
import com.bigbrightpaints.erp.modules.hr.domain.AttendanceRepository;
import com.bigbrightpaints.erp.modules.hr.domain.Employee;
import com.bigbrightpaints.erp.modules.hr.domain.EmployeeRepository;
import com.bigbrightpaints.erp.modules.hr.dto.AttendanceBulkImportRequest;
import com.bigbrightpaints.erp.modules.hr.dto.AttendanceDto;
import com.bigbrightpaints.erp.modules.hr.dto.AttendanceSummaryDto;
import com.bigbrightpaints.erp.modules.hr.dto.BulkMarkAttendanceRequest;
import com.bigbrightpaints.erp.modules.hr.dto.MarkAttendanceRequest;
import com.bigbrightpaints.erp.modules.hr.dto.MonthlyAttendanceSummaryDto;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AttendanceService {

    private final CompanyContextService companyContextService;
    private final AttendanceRepository attendanceRepository;
    private final EmployeeRepository employeeRepository;
    private final CompanyEntityLookup companyEntityLookup;
    private final CompanyClock companyClock;

    public AttendanceService(CompanyContextService companyContextService,
                             AttendanceRepository attendanceRepository,
                             EmployeeRepository employeeRepository,
                             CompanyEntityLookup companyEntityLookup,
                             CompanyClock companyClock) {
        this.companyContextService = companyContextService;
        this.attendanceRepository = attendanceRepository;
        this.employeeRepository = employeeRepository;
        this.companyEntityLookup = companyEntityLookup;
        this.companyClock = companyClock;
    }

    public List<AttendanceDto> listAttendanceByDate(LocalDate date) {
        Company company = companyContextService.requireCurrentCompany();
        return attendanceRepository.findByCompanyAndAttendanceDateOrderByEmployeeFirstNameAsc(company, date)
                .stream()
                .map(this::toAttendanceDto)
                .toList();
    }

    public List<AttendanceDto> listEmployeeAttendance(Long employeeId,
                                                      LocalDate startDate,
                                                      LocalDate endDate) {
        validateDateRange(startDate, endDate);

        Company company = companyContextService.requireCurrentCompany();
        Employee employee = companyEntityLookup.requireEmployee(company, employeeId);
        return attendanceRepository.findByCompanyAndEmployeeAndAttendanceDateBetweenOrderByAttendanceDateAsc(
                        company,
                        employee,
                        startDate,
                        endDate)
                .stream()
                .map(this::toAttendanceDto)
                .toList();
    }

    @Transactional
    public AttendanceDto markAttendance(Long employeeId, MarkAttendanceRequest request) {
        if (request == null) {
            throw new ApplicationException(ErrorCode.VALIDATION_MISSING_REQUIRED_FIELD,
                    "Attendance request is required");
        }
        Company company = companyContextService.requireCurrentCompany();
        Employee employee = companyEntityLookup.requireEmployee(company, employeeId);
        LocalDate date = request.date() != null ? request.date() : companyClock.today(company);

        Attendance attendance = attendanceRepository.findByCompanyAndEmployeeAndAttendanceDate(
                        company,
                        employee,
                        date)
                .orElseGet(() -> newAttendance(company, employee, date));

        applyAttendanceRequest(attendance, request.status(), request.checkInTime(), request.checkOutTime(),
                request.regularHours(), request.overtimeHours(), request.doubleOvertimeHours(),
                request.holiday(), request.weekend(), request.remarks(), company);

        return toAttendanceDto(attendanceRepository.save(attendance));
    }

    @Transactional
    public List<AttendanceDto> bulkMarkAttendance(BulkMarkAttendanceRequest request) {
        if (request == null) {
            throw new ApplicationException(ErrorCode.VALIDATION_MISSING_REQUIRED_FIELD,
                    "Bulk attendance request is required");
        }

        Company company = companyContextService.requireCurrentCompany();
        List<Long> originalIds = request.employeeIds();
        if (originalIds == null || originalIds.isEmpty()) {
            return List.of();
        }

        Attendance.AttendanceStatus status = parseAttendanceStatus(request.status());
        LocalDate date = request.date() != null ? request.date() : companyClock.today(company);

        List<Long> uniqueIds = originalIds.stream().distinct().toList();
        List<Employee> employees = employeeRepository.findByCompanyAndIdIn(company, uniqueIds);
        if (employees.size() != uniqueIds.size()) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_REFERENCE,
                    "One or more employees were not found for the current company");
        }

        Map<Long, Employee> employeesById = employees.stream()
                .collect(Collectors.toMap(Employee::getId, employee -> employee));

        List<Attendance> existingRows = attendanceRepository.findByCompanyAndEmployeeInAndAttendanceDate(
                company,
                employees,
                date);

        Map<Long, Attendance> existingByEmployeeId = existingRows.stream()
                .collect(Collectors.toMap(row -> row.getEmployee().getId(), row -> row));

        List<Attendance> toPersist = new ArrayList<>();
        for (Long employeeId : uniqueIds) {
            Employee employee = employeesById.get(employeeId);
            Attendance attendance = existingByEmployeeId.get(employeeId);
            if (attendance == null) {
                attendance = newAttendance(company, employee, date);
            }
            applyAttendanceRequest(attendance, status.name(), request.checkInTime(), request.checkOutTime(),
                    request.regularHours(), request.overtimeHours(), null,
                    false, false, request.remarks(), company);
            toPersist.add(attendance);
        }

        Map<Long, AttendanceDto> dtoByEmployeeId = attendanceRepository.saveAll(toPersist)
                .stream()
                .map(this::toAttendanceDto)
                .collect(Collectors.toMap(AttendanceDto::employeeId, dto -> dto));

        return uniqueIds.stream()
                .map(dtoByEmployeeId::get)
                .toList();
    }

    @Transactional
    public List<AttendanceDto> bulkImportAttendance(AttendanceBulkImportRequest request) {
        if (request == null || request.records() == null || request.records().isEmpty()) {
            throw new ApplicationException(ErrorCode.VALIDATION_MISSING_REQUIRED_FIELD,
                    "Attendance import records are required");
        }

        List<AttendanceDto> imported = new ArrayList<>();
        for (BulkMarkAttendanceRequest record : request.records()) {
            imported.addAll(bulkMarkAttendance(record));
        }
        return imported;
    }

    public AttendanceSummaryDto getTodayAttendanceSummary() {
        Company company = companyContextService.requireCurrentCompany();
        LocalDate today = companyClock.today(company);

        List<Attendance> attendances = attendanceRepository.findByCompanyAndAttendanceDateOrderByEmployeeFirstNameAsc(
                company,
                today);
        long totalEmployees = employeeRepository.countByCompanyAndStatus(company, "ACTIVE");
        long present = attendances.stream()
                .filter(attendance -> attendance.getStatus() == Attendance.AttendanceStatus.PRESENT)
                .count();
        long absent = attendances.stream()
                .filter(attendance -> attendance.getStatus() == Attendance.AttendanceStatus.ABSENT)
                .count();
        long halfDay = attendances.stream()
                .filter(attendance -> attendance.getStatus() == Attendance.AttendanceStatus.HALF_DAY)
                .count();
        long leave = attendances.stream()
                .filter(attendance -> attendance.getStatus() == Attendance.AttendanceStatus.LEAVE)
                .count();
        long notMarked = Math.max(0, totalEmployees - attendances.size());

        return new AttendanceSummaryDto(today, totalEmployees, present, absent, halfDay, leave, notMarked);
    }

    public List<MonthlyAttendanceSummaryDto> getMonthlyAttendanceSummary(int year, int month) {
        Company company = companyContextService.requireCurrentCompany();
        LocalDate start = LocalDate.of(year, month, 1);
        LocalDate end = start.with(TemporalAdjusters.lastDayOfMonth());

        return attendanceRepository.summarizeMonthlyAttendance(company, start, end)
                .stream()
                .map(this::toMonthlySummary)
                .toList();
    }

    private Attendance newAttendance(Company company, Employee employee, LocalDate date) {
        Attendance attendance = new Attendance();
        attendance.setCompany(company);
        attendance.setEmployee(employee);
        attendance.setAttendanceDate(date);
        return attendance;
    }

    private void applyAttendanceRequest(Attendance attendance,
                                        String rawStatus,
                                        java.time.LocalTime checkInTime,
                                        java.time.LocalTime checkOutTime,
                                        BigDecimal regularHours,
                                        BigDecimal overtimeHours,
                                        BigDecimal doubleOvertimeHours,
                                        boolean holiday,
                                        boolean weekend,
                                        String remarks,
                                        Company company) {
        attendance.setStatus(parseAttendanceStatus(rawStatus));
        if (checkInTime != null) {
            attendance.setCheckInTime(checkInTime);
        }
        if (checkOutTime != null) {
            attendance.setCheckOutTime(checkOutTime);
        }
        if (regularHours != null) {
            attendance.setRegularHours(regularHours);
        }
        if (overtimeHours != null) {
            attendance.setOvertimeHours(overtimeHours);
        }
        if (doubleOvertimeHours != null) {
            attendance.setDoubleOvertimeHours(doubleOvertimeHours);
        }
        attendance.setHoliday(holiday);
        attendance.setWeekend(weekend);
        if (remarks != null) {
            attendance.setRemarks(remarks);
        }
        attendance.setMarkedBy(getCurrentUser());
        attendance.setMarkedAt(CompanyTime.now(company));
    }

    private void validateDateRange(LocalDate startDate, LocalDate endDate) {
        if (startDate == null || endDate == null) {
            throw new ApplicationException(ErrorCode.VALIDATION_MISSING_REQUIRED_FIELD,
                    "startDate and endDate are required");
        }
        if (endDate.isBefore(startDate)) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_DATE,
                    "endDate cannot be before startDate")
                    .withDetail("startDate", startDate)
                    .withDetail("endDate", endDate);
        }
    }

    private Attendance.AttendanceStatus parseAttendanceStatus(String rawAttendanceStatus) {
        if (rawAttendanceStatus == null) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                    "attendanceStatus is required");
        }
        try {
            return Attendance.AttendanceStatus.valueOf(rawAttendanceStatus.trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException ex) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                    "Invalid attendance status. Allowed values: "
                            + Arrays.toString(Attendance.AttendanceStatus.values()))
                    .withDetail("attendanceStatus", rawAttendanceStatus);
        }
    }

    private AttendanceDto toAttendanceDto(Attendance attendance) {
        Employee employee = attendance.getEmployee();
        return new AttendanceDto(
                attendance.getId(),
                employee.getId(),
                employee.getFirstName() + " " + employee.getLastName(),
                employee.getEmployeeType() != null ? employee.getEmployeeType().name() : null,
                attendance.getAttendanceDate(),
                attendance.getStatus().name(),
                attendance.getCheckInTime(),
                attendance.getCheckOutTime(),
                attendance.getRegularHours(),
                attendance.getOvertimeHours(),
                attendance.getDoubleOvertimeHours(),
                attendance.isHoliday(),
                attendance.isWeekend(),
                attendance.getRemarks(),
                attendance.getMarkedBy(),
                attendance.getMarkedAt());
    }

    private MonthlyAttendanceSummaryDto toMonthlySummary(Object[] row) {
        Long employeeId = number(row[0]).longValue();
        String firstName = string(row[1]);
        String lastName = string(row[2]);
        String department = string(row[3]);
        String designation = string(row[4]);

        return new MonthlyAttendanceSummaryDto(
                employeeId,
                (firstName + " " + lastName).trim(),
                department,
                designation,
                number(row[5]).longValue(),
                number(row[6]).longValue(),
                number(row[7]).longValue(),
                number(row[8]).longValue(),
                number(row[9]).longValue(),
                decimal(row[10]),
                decimal(row[11]));
    }

    private Number number(Object value) {
        if (value instanceof Number n) {
            return n;
        }
        return 0L;
    }

    private String string(Object value) {
        return value == null ? null : value.toString();
    }

    private BigDecimal decimal(Object value) {
        if (value instanceof BigDecimal bd) {
            return bd;
        }
        if (value instanceof Number n) {
            return BigDecimal.valueOf(n.doubleValue());
        }
        return BigDecimal.ZERO;
    }

    private String getCurrentUser() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null ? authentication.getName() : "SYSTEM";
    }
}
