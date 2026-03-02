package com.bigbrightpaints.erp.truthsuite.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.core.util.CompanyEntityLookup;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.hr.domain.AttendanceRepository;
import com.bigbrightpaints.erp.modules.hr.domain.Employee;
import com.bigbrightpaints.erp.modules.hr.domain.EmployeeRepository;
import com.bigbrightpaints.erp.modules.hr.domain.LeaveRequest;
import com.bigbrightpaints.erp.modules.hr.domain.LeaveRequestRepository;
import com.bigbrightpaints.erp.modules.hr.dto.BulkMarkAttendanceRequest;
import com.bigbrightpaints.erp.modules.hr.dto.EmployeeRequest;
import com.bigbrightpaints.erp.modules.hr.dto.MarkAttendanceRequest;
import com.bigbrightpaints.erp.modules.hr.service.HrService;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("critical")
class TS_RuntimeHrServiceEnumParsingCoverageTest {

    @Test
    void createEmployee_invalidEmployeeType_returnsApplicationException() {
        CompanyContextService companyContextService = mock(CompanyContextService.class);
        EmployeeRepository employeeRepository = mock(EmployeeRepository.class);
        LeaveRequestRepository leaveRequestRepository = mock(LeaveRequestRepository.class);
        AttendanceRepository attendanceRepository = mock(AttendanceRepository.class);
        CompanyEntityLookup companyEntityLookup = mock(CompanyEntityLookup.class);
        CompanyClock companyClock = mock(CompanyClock.class);
        HrService service = new HrService(
                companyContextService,
                employeeRepository,
                leaveRequestRepository,
                attendanceRepository,
                companyEntityLookup,
                companyClock,
                mock(com.bigbrightpaints.erp.modules.hr.domain.SalaryStructureTemplateRepository.class),
                mock(com.bigbrightpaints.erp.modules.hr.domain.LeaveTypePolicyRepository.class),
                mock(com.bigbrightpaints.erp.modules.hr.domain.LeaveBalanceRepository.class),
                mock(com.bigbrightpaints.erp.core.security.CryptoService.class));

        Company company = new Company();
        when(companyContextService.requireCurrentCompany()).thenReturn(company);

        EmployeeRequest request = new EmployeeRequest(
                "Jane",
                "Doe",
                "jane@bbp.com",
                null,
                "Painter",
                LocalDate.of(2025, 1, 1),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                "INVALID_TYPE",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);

        assertThatThrownBy(() -> service.createEmployee(request))
                .isInstanceOf(ApplicationException.class)
                .satisfies(ex -> assertThat(((ApplicationException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.VALIDATION_INVALID_INPUT));
    }

    @Test
    void createEmployee_invalidPaymentSchedule_returnsApplicationException() {
        CompanyContextService companyContextService = mock(CompanyContextService.class);
        EmployeeRepository employeeRepository = mock(EmployeeRepository.class);
        LeaveRequestRepository leaveRequestRepository = mock(LeaveRequestRepository.class);
        AttendanceRepository attendanceRepository = mock(AttendanceRepository.class);
        CompanyEntityLookup companyEntityLookup = mock(CompanyEntityLookup.class);
        CompanyClock companyClock = mock(CompanyClock.class);
        HrService service = new HrService(
                companyContextService,
                employeeRepository,
                leaveRequestRepository,
                attendanceRepository,
                companyEntityLookup,
                companyClock,
                mock(com.bigbrightpaints.erp.modules.hr.domain.SalaryStructureTemplateRepository.class),
                mock(com.bigbrightpaints.erp.modules.hr.domain.LeaveTypePolicyRepository.class),
                mock(com.bigbrightpaints.erp.modules.hr.domain.LeaveBalanceRepository.class),
                mock(com.bigbrightpaints.erp.core.security.CryptoService.class));

        Company company = new Company();
        when(companyContextService.requireCurrentCompany()).thenReturn(company);

        EmployeeRequest request = new EmployeeRequest(
                "Jane",
                "Doe",
                "jane@bbp.com",
                null,
                "Painter",
                LocalDate.of(2025, 1, 1),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                "STAFF",
                "BIWEEKLY",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);

        assertThatThrownBy(() -> service.createEmployee(request))
                .isInstanceOf(ApplicationException.class)
                .satisfies(ex -> assertThat(((ApplicationException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.VALIDATION_INVALID_INPUT));
    }

    @Test
    void markAttendance_invalidStatus_returnsApplicationException() {
        CompanyContextService companyContextService = mock(CompanyContextService.class);
        EmployeeRepository employeeRepository = mock(EmployeeRepository.class);
        LeaveRequestRepository leaveRequestRepository = mock(LeaveRequestRepository.class);
        AttendanceRepository attendanceRepository = mock(AttendanceRepository.class);
        CompanyEntityLookup companyEntityLookup = mock(CompanyEntityLookup.class);
        CompanyClock companyClock = mock(CompanyClock.class);
        HrService service = new HrService(
                companyContextService,
                employeeRepository,
                leaveRequestRepository,
                attendanceRepository,
                companyEntityLookup,
                companyClock,
                mock(com.bigbrightpaints.erp.modules.hr.domain.SalaryStructureTemplateRepository.class),
                mock(com.bigbrightpaints.erp.modules.hr.domain.LeaveTypePolicyRepository.class),
                mock(com.bigbrightpaints.erp.modules.hr.domain.LeaveBalanceRepository.class),
                mock(com.bigbrightpaints.erp.core.security.CryptoService.class));

        Company company = new Company();
        Employee employee = new Employee();
        when(companyContextService.requireCurrentCompany()).thenReturn(company);
        when(companyEntityLookup.requireEmployee(company, 5L)).thenReturn(employee);
        when(companyClock.today(company)).thenReturn(LocalDate.of(2026, 2, 1));
        when(attendanceRepository.findByCompanyAndEmployeeAndAttendanceDate(
                company, employee, LocalDate.of(2026, 2, 1))).thenReturn(Optional.empty());

        MarkAttendanceRequest request = new MarkAttendanceRequest(
                null,
                "NOT_A_STATUS",
                null,
                null,
                null,
                null,
                null,
                false,
                false,
                null);

        assertThatThrownBy(() -> service.markAttendance(5L, request))
                .isInstanceOf(ApplicationException.class)
                .satisfies(ex -> assertThat(((ApplicationException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.VALIDATION_INVALID_INPUT));
    }

    @Test
    void bulkMarkAttendance_invalidStatus_returnsApplicationException() {
        CompanyContextService companyContextService = mock(CompanyContextService.class);
        EmployeeRepository employeeRepository = mock(EmployeeRepository.class);
        LeaveRequestRepository leaveRequestRepository = mock(LeaveRequestRepository.class);
        AttendanceRepository attendanceRepository = mock(AttendanceRepository.class);
        CompanyEntityLookup companyEntityLookup = mock(CompanyEntityLookup.class);
        CompanyClock companyClock = mock(CompanyClock.class);
        HrService service = new HrService(
                companyContextService,
                employeeRepository,
                leaveRequestRepository,
                attendanceRepository,
                companyEntityLookup,
                companyClock,
                mock(com.bigbrightpaints.erp.modules.hr.domain.SalaryStructureTemplateRepository.class),
                mock(com.bigbrightpaints.erp.modules.hr.domain.LeaveTypePolicyRepository.class),
                mock(com.bigbrightpaints.erp.modules.hr.domain.LeaveBalanceRepository.class),
                mock(com.bigbrightpaints.erp.core.security.CryptoService.class));

        Company company = new Company();
        Employee employee = new Employee();
        LocalDate date = LocalDate.of(2026, 2, 1);
        when(companyContextService.requireCurrentCompany()).thenReturn(company);
        when(companyEntityLookup.requireEmployee(company, 7L)).thenReturn(employee);
        when(attendanceRepository.findByCompanyAndEmployeeAndAttendanceDate(company, employee, date))
                .thenReturn(Optional.empty());

        BulkMarkAttendanceRequest request = new BulkMarkAttendanceRequest(
                List.of(7L),
                date,
                "NOPE",
                null,
                null,
                null,
                null,
                null);

        assertThatThrownBy(() -> service.bulkMarkAttendance(request))
                .isInstanceOf(ApplicationException.class)
                .satisfies(ex -> assertThat(((ApplicationException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.VALIDATION_INVALID_INPUT));
    }

    @Test
    void updateLeaveStatus_invalidStatus_returnsApplicationException() {
        CompanyContextService companyContextService = mock(CompanyContextService.class);
        EmployeeRepository employeeRepository = mock(EmployeeRepository.class);
        LeaveRequestRepository leaveRequestRepository = mock(LeaveRequestRepository.class);
        AttendanceRepository attendanceRepository = mock(AttendanceRepository.class);
        CompanyEntityLookup companyEntityLookup = mock(CompanyEntityLookup.class);
        CompanyClock companyClock = mock(CompanyClock.class);
        HrService service = new HrService(
                companyContextService,
                employeeRepository,
                leaveRequestRepository,
                attendanceRepository,
                companyEntityLookup,
                companyClock,
                mock(com.bigbrightpaints.erp.modules.hr.domain.SalaryStructureTemplateRepository.class),
                mock(com.bigbrightpaints.erp.modules.hr.domain.LeaveTypePolicyRepository.class),
                mock(com.bigbrightpaints.erp.modules.hr.domain.LeaveBalanceRepository.class),
                mock(com.bigbrightpaints.erp.core.security.CryptoService.class));

        Company company = new Company();
        LeaveRequest leaveRequest = new LeaveRequest();
        when(companyContextService.requireCurrentCompany()).thenReturn(company);
        when(companyEntityLookup.requireLeaveRequest(company, 11L)).thenReturn(leaveRequest);

        assertThatThrownBy(() -> service.updateLeaveStatus(11L, "UNSUPPORTED_STATUS"))
                .isInstanceOf(ApplicationException.class)
                .satisfies(ex -> assertThat(((ApplicationException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.VALIDATION_INVALID_INPUT));
    }
}
