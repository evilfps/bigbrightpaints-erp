package com.bigbrightpaints.erp.modules.hr.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.core.util.CompanyEntityLookup;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.hr.domain.Attendance;
import com.bigbrightpaints.erp.modules.hr.domain.AttendanceRepository;
import com.bigbrightpaints.erp.modules.hr.domain.Employee;
import com.bigbrightpaints.erp.modules.hr.domain.EmployeeRepository;
import com.bigbrightpaints.erp.modules.hr.dto.AttendanceBulkImportRequest;
import com.bigbrightpaints.erp.modules.hr.dto.AttendanceDto;
import com.bigbrightpaints.erp.modules.hr.dto.BulkMarkAttendanceRequest;
import com.bigbrightpaints.erp.modules.hr.dto.MonthlyAttendanceSummaryDto;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@Tag("critical")
class AttendanceServiceTest {

    @Mock
    private CompanyContextService companyContextService;
    @Mock
    private AttendanceRepository attendanceRepository;
    @Mock
    private EmployeeRepository employeeRepository;
    @Mock
    private CompanyEntityLookup companyEntityLookup;
    @Mock
    private CompanyClock companyClock;

    private AttendanceService attendanceService;
    private Company company;
    private Employee emp1;
    private Employee emp2;

    @BeforeEach
    void setUp() {
        attendanceService = new AttendanceService(
                companyContextService,
                attendanceRepository,
                employeeRepository,
                companyEntityLookup,
                companyClock);

        company = new Company();
        ReflectionTestUtils.setField(company, "id", 33L);
        when(companyContextService.requireCurrentCompany()).thenReturn(company);

        emp1 = new Employee();
        ReflectionTestUtils.setField(emp1, "id", 1L);
        emp1.setFirstName("Ravi");
        emp1.setLastName("Shah");
        emp1.setEmployeeType(Employee.EmployeeType.STAFF);

        emp2 = new Employee();
        ReflectionTestUtils.setField(emp2, "id", 2L);
        emp2.setFirstName("Neha");
        emp2.setLastName("Iyer");
        emp2.setEmployeeType(Employee.EmployeeType.LABOUR);
    }

    @Test
    void bulkMarkAttendance_usesBatchLookupAndSaveAll() {
        LocalDate date = LocalDate.of(2026, 2, 2);
        BulkMarkAttendanceRequest request = new BulkMarkAttendanceRequest(
                List.of(1L, 2L, 1L),
                date,
                "PRESENT",
                LocalTime.of(9, 0),
                LocalTime.of(18, 0),
                new BigDecimal("8"),
                new BigDecimal("1.5"),
                "bulk-mark");

        Attendance existing = new Attendance();
        existing.setCompany(company);
        existing.setEmployee(emp1);
        existing.setAttendanceDate(date);
        existing.setStatus(Attendance.AttendanceStatus.ABSENT);

        when(employeeRepository.findByCompanyAndIdIn(company, List.of(1L, 2L))).thenReturn(List.of(emp1, emp2));
        when(attendanceRepository.findByCompanyAndEmployeeInAndAttendanceDate(company, List.of(emp1, emp2), date))
                .thenReturn(List.of(existing));
        when(attendanceRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));

        List<AttendanceDto> dtos = attendanceService.bulkMarkAttendance(request);

        assertThat(dtos).hasSize(2);
        assertThat(dtos).extracting(AttendanceDto::employeeId).containsExactly(1L, 2L);
        verify(employeeRepository).findByCompanyAndIdIn(company, List.of(1L, 2L));

        ArgumentCaptor<List<Attendance>> captor = ArgumentCaptor.forClass(List.class);
        verify(attendanceRepository).saveAll(captor.capture());
        List<Attendance> savedRows = captor.getValue();
        assertThat(savedRows).hasSize(2);
        assertThat(savedRows).allSatisfy(row -> {
            assertThat(row.getStatus()).isEqualTo(Attendance.AttendanceStatus.PRESENT);
            assertThat(row.getRegularHours()).isEqualByComparingTo("8");
            assertThat(row.getOvertimeHours()).isEqualByComparingTo("1.5");
        });
    }

    @Test
    void bulkMarkAttendance_unknownEmployeeRejected() {
        BulkMarkAttendanceRequest request = new BulkMarkAttendanceRequest(
                List.of(1L, 2L),
                LocalDate.of(2026, 2, 2),
                "PRESENT",
                null,
                null,
                null,
                null,
                null);

        when(employeeRepository.findByCompanyAndIdIn(company, List.of(1L, 2L))).thenReturn(List.of(emp1));

        assertThatThrownBy(() -> attendanceService.bulkMarkAttendance(request))
                .isInstanceOf(ApplicationException.class)
                .satisfies(ex -> assertThat(((ApplicationException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.VALIDATION_INVALID_REFERENCE));
    }

    @Test
    void bulkImportAttendance_flattensRecords() {
        BulkMarkAttendanceRequest first = new BulkMarkAttendanceRequest(
                List.of(1L),
                LocalDate.of(2026, 2, 2),
                "PRESENT",
                null,
                null,
                null,
                null,
                null);
        BulkMarkAttendanceRequest second = new BulkMarkAttendanceRequest(
                List.of(2L),
                LocalDate.of(2026, 2, 3),
                "ABSENT",
                null,
                null,
                null,
                null,
                null);

        Attendance a1 = new Attendance();
        a1.setCompany(company);
        a1.setEmployee(emp1);
        a1.setAttendanceDate(first.date());
        a1.setStatus(Attendance.AttendanceStatus.PRESENT);

        Attendance a2 = new Attendance();
        a2.setCompany(company);
        a2.setEmployee(emp2);
        a2.setAttendanceDate(second.date());
        a2.setStatus(Attendance.AttendanceStatus.ABSENT);

        when(employeeRepository.findByCompanyAndIdIn(company, List.of(1L))).thenReturn(List.of(emp1));
        when(employeeRepository.findByCompanyAndIdIn(company, List.of(2L))).thenReturn(List.of(emp2));
        when(attendanceRepository.findByCompanyAndEmployeeInAndAttendanceDate(company, List.of(emp1), first.date()))
                .thenReturn(List.of());
        when(attendanceRepository.findByCompanyAndEmployeeInAndAttendanceDate(company, List.of(emp2), second.date()))
                .thenReturn(List.of());
        when(attendanceRepository.saveAll(any()))
                .thenReturn(List.of(a1))
                .thenReturn(List.of(a2));

        List<AttendanceDto> imported = attendanceService.bulkImportAttendance(new AttendanceBulkImportRequest(List.of(first, second)));

        assertThat(imported).hasSize(2);
        assertThat(imported).extracting(AttendanceDto::employeeId).containsExactly(1L, 2L);
    }

    @Test
    void monthlySummary_mapsRepositoryProjection() {
        when(attendanceRepository.summarizeMonthlyAttendance(company, LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 28)))
                .thenReturn(java.util.Collections.singletonList(new Object[] {
                        1L,
                        "Ravi",
                        "Shah",
                        "Factory",
                        "Painter",
                        20L,
                        2L,
                        1L,
                        1L,
                        4L,
                        new BigDecimal("12.5"),
                        new BigDecimal("3.0")
                }));

        List<MonthlyAttendanceSummaryDto> summary = attendanceService.getMonthlyAttendanceSummary(2026, 2);

        assertThat(summary).hasSize(1);
        MonthlyAttendanceSummaryDto dto = summary.get(0);
        assertThat(dto.employeeId()).isEqualTo(1L);
        assertThat(dto.employeeName()).isEqualTo("Ravi Shah");
        assertThat(dto.presentDays()).isEqualTo(20);
        assertThat(dto.overtimeHours()).isEqualByComparingTo("12.5");
        assertThat(dto.doubleOvertimeHours()).isEqualByComparingTo("3.0");
    }

    @Test
    void markAttendance_invalidStatusRejected() {
        when(companyClock.today(company)).thenReturn(LocalDate.of(2026, 2, 1));
        when(companyEntityLookup.requireEmployee(company, 1L)).thenReturn(emp1);
        when(attendanceRepository.findByCompanyAndEmployeeAndAttendanceDate(company, emp1, LocalDate.of(2026, 2, 1)))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> attendanceService.markAttendance(1L,
                new com.bigbrightpaints.erp.modules.hr.dto.MarkAttendanceRequest(
                        null,
                        "NOT_A_STATUS",
                        null,
                        null,
                        null,
                        null,
                        null,
                        false,
                        false,
                        null)))
                .isInstanceOf(ApplicationException.class)
                .satisfies(ex -> assertThat(((ApplicationException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.VALIDATION_INVALID_INPUT));
    }
}
