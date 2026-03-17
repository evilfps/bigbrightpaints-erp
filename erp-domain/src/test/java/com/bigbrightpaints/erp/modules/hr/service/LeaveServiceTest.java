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
import com.bigbrightpaints.erp.core.util.CompanyTime;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.hr.domain.Employee;
import com.bigbrightpaints.erp.modules.hr.domain.EmployeeRepository;
import com.bigbrightpaints.erp.modules.hr.domain.LeaveBalance;
import com.bigbrightpaints.erp.modules.hr.domain.LeaveBalanceRepository;
import com.bigbrightpaints.erp.modules.hr.domain.LeaveRequest;
import com.bigbrightpaints.erp.modules.hr.domain.LeaveRequestRepository;
import com.bigbrightpaints.erp.modules.hr.domain.LeaveTypePolicy;
import com.bigbrightpaints.erp.modules.hr.domain.LeaveTypePolicyRepository;
import com.bigbrightpaints.erp.modules.hr.dto.LeaveRequestDto;
import com.bigbrightpaints.erp.modules.hr.dto.LeaveRequestRequest;
import com.bigbrightpaints.erp.modules.hr.dto.LeaveStatusUpdateRequest;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@Tag("critical")
class LeaveServiceTest {

    @Mock
    private CompanyContextService companyContextService;
    @Mock
    private EmployeeRepository employeeRepository;
    @Mock
    private LeaveRequestRepository leaveRequestRepository;
    @Mock
    private CompanyEntityLookup companyEntityLookup;
    @Mock
    private LeaveTypePolicyRepository leaveTypePolicyRepository;
    @Mock
    private LeaveBalanceRepository leaveBalanceRepository;

    private CompanyClock companyClock;
    private LeaveService leaveService;
    private Company company;
    private Employee employee;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        leaveService = new LeaveService(
                companyContextService,
                employeeRepository,
                leaveRequestRepository,
                companyEntityLookup,
                leaveTypePolicyRepository,
                leaveBalanceRepository);
        companyClock = mock(CompanyClock.class);
        new CompanyTime(companyClock);

        company = new Company();
        ReflectionTestUtils.setField(company, "id", 77L);
        when(companyContextService.requireCurrentCompany()).thenReturn(company);

        employee = new Employee();
        ReflectionTestUtils.setField(employee, "id", 10L);
        employee.setFirstName("Asha");
        employee.setLastName("Patel");
    }

    @Test
    void getLeaveBalances_defaultsMissingYearFromCompanyClock() {
        LocalDate tenantToday = LocalDate.of(2026, 4, 2);
        LeaveTypePolicy policy = activePolicy("CASUAL", "Casual leave");
        LeaveBalance balance = balanceForYear(tenantToday.getYear());

        when(companyClock.today(company)).thenReturn(tenantToday);
        when(companyEntityLookup.requireEmployee(company, 10L)).thenReturn(employee);
        when(leaveTypePolicyRepository.findByCompanyAndActiveTrueOrderByDisplayNameAsc(company))
                .thenReturn(java.util.List.of(policy));
        when(leaveBalanceRepository.findByCompanyAndEmployeeAndLeaveTypeAndBalanceYear(
                company, employee, "CASUAL", tenantToday.getYear()))
                .thenReturn(Optional.of(balance));

        assertThat(leaveService.getLeaveBalances(10L, null))
                .singleElement()
                .satisfies(dto -> {
                    assertThat(dto.employeeId()).isEqualTo(10L);
                    assertThat(dto.leaveType()).isEqualTo("CASUAL");
                    assertThat(dto.year()).isEqualTo(2026);
                });
    }

    @Test
    void getLeaveBalances_usesExplicitYearWithoutCompanyClockFallback() {
        LeaveTypePolicy policy = activePolicy("CASUAL", "Casual leave");
        LeaveBalance balance = balanceForYear(2024);

        when(companyEntityLookup.requireEmployee(company, 10L)).thenReturn(employee);
        when(leaveTypePolicyRepository.findByCompanyAndActiveTrueOrderByDisplayNameAsc(company))
                .thenReturn(java.util.List.of(policy));
        when(leaveBalanceRepository.findByCompanyAndEmployeeAndLeaveTypeAndBalanceYear(
                company, employee, "CASUAL", 2024))
                .thenReturn(Optional.of(balance));

        assertThat(leaveService.getLeaveBalances(10L, 2024))
                .singleElement()
                .satisfies(dto -> assertThat(dto.year()).isEqualTo(2024));
    }

    @Test
    void createLeaveRequest_approvedConsumesBalanceAndStoresDays() {
        LocalDate start = LocalDate.of(2026, 2, 10);
        LocalDate end = LocalDate.of(2026, 2, 12);

        LeaveRequestRequest request = new LeaveRequestRequest(
                10L,
                "casual",
                start,
                end,
                "Vacation",
                "APPROVED",
                "Manager approved");

        LeaveTypePolicy policy = new LeaveTypePolicy();
        policy.setLeaveType("CASUAL");
        policy.setAnnualEntitlement(new BigDecimal("12.00"));
        policy.setCarryForwardLimit(new BigDecimal("5.00"));

        LeaveBalance balance = new LeaveBalance();
        balance.setCompany(company);
        balance.setEmployee(employee);
        balance.setLeaveType("CASUAL");
        balance.setBalanceYear(2026);
        balance.setOpeningBalance(BigDecimal.ZERO);
        balance.setAccrued(new BigDecimal("12.00"));
        balance.setUsed(new BigDecimal("1.00"));
        balance.setRemaining(new BigDecimal("11.00"));

        when(employeeRepository.lockByCompanyAndId(company, 10L)).thenReturn(Optional.of(employee));
        when(leaveRequestRepository.existsOverlappingByEmployeeIdAndDates(company, 10L, start, end)).thenReturn(false);
        when(leaveTypePolicyRepository.findByCompanyAndLeaveTypeIgnoreCase(company, "CASUAL")).thenReturn(Optional.of(policy));
        when(leaveBalanceRepository.findByCompanyAndEmployeeAndLeaveTypeAndBalanceYear(company, employee, "CASUAL", 2026))
                .thenReturn(Optional.of(balance));
        when(leaveRequestRepository.save(any(LeaveRequest.class))).thenAnswer(invocation -> {
            LeaveRequest saved = invocation.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", 300L);
            return saved;
        });

        LeaveRequestDto dto = leaveService.createLeaveRequest(request);

        assertThat(dto.id()).isEqualTo(300L);
        assertThat(dto.leaveType()).isEqualTo("CASUAL");
        assertThat(dto.totalDays()).isEqualByComparingTo("3.00");
        assertThat(dto.status()).isEqualTo("APPROVED");
        assertThat(dto.approvedBy()).isNotBlank();

        ArgumentCaptor<LeaveBalance> balanceCaptor = ArgumentCaptor.forClass(LeaveBalance.class);
        verify(leaveBalanceRepository).save(balanceCaptor.capture());
        LeaveBalance savedBalance = balanceCaptor.getValue();
        assertThat(savedBalance.getUsed()).isEqualByComparingTo("4.00");
        assertThat(savedBalance.getRemaining()).isEqualByComparingTo("8.00");
    }

    @Test
    void createLeaveRequest_insufficientBalanceRejected() {
        LocalDate start = LocalDate.of(2026, 2, 10);
        LocalDate end = LocalDate.of(2026, 2, 12);

        LeaveRequestRequest request = new LeaveRequestRequest(
                10L,
                "CASUAL",
                start,
                end,
                "Vacation",
                "APPROVED",
                null);

        LeaveTypePolicy policy = new LeaveTypePolicy();
        policy.setLeaveType("CASUAL");
        policy.setAnnualEntitlement(new BigDecimal("2.00"));
        policy.setCarryForwardLimit(BigDecimal.ZERO);

        LeaveBalance balance = new LeaveBalance();
        balance.setCompany(company);
        balance.setEmployee(employee);
        balance.setLeaveType("CASUAL");
        balance.setBalanceYear(2026);
        balance.setOpeningBalance(BigDecimal.ZERO);
        balance.setAccrued(new BigDecimal("2.00"));
        balance.setUsed(BigDecimal.ZERO);
        balance.setRemaining(new BigDecimal("2.00"));

        when(employeeRepository.lockByCompanyAndId(company, 10L)).thenReturn(Optional.of(employee));
        when(leaveRequestRepository.existsOverlappingByEmployeeIdAndDates(company, 10L, start, end)).thenReturn(false);
        when(leaveTypePolicyRepository.findByCompanyAndLeaveTypeIgnoreCase(company, "CASUAL")).thenReturn(Optional.of(policy));
        when(leaveBalanceRepository.findByCompanyAndEmployeeAndLeaveTypeAndBalanceYear(company, employee, "CASUAL", 2026))
                .thenReturn(Optional.of(balance));

        assertThatThrownBy(() -> leaveService.createLeaveRequest(request))
                .isInstanceOf(ApplicationException.class)
                .satisfies(ex -> assertThat(((ApplicationException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.BUSINESS_LIMIT_EXCEEDED));
    }

    @Test
    void updateLeaveStatus_approvedToRejectedRestoresBalance() {
        LeaveRequest leaveRequest = new LeaveRequest();
        ReflectionTestUtils.setField(leaveRequest, "id", 401L);
        leaveRequest.setCompany(company);
        leaveRequest.setEmployee(employee);
        leaveRequest.setLeaveType("CASUAL");
        leaveRequest.setStartDate(LocalDate.of(2026, 3, 1));
        leaveRequest.setEndDate(LocalDate.of(2026, 3, 1));
        leaveRequest.setStatus("APPROVED");
        leaveRequest.setTotalDays(new BigDecimal("1.00"));

        LeaveBalance balance = new LeaveBalance();
        balance.setCompany(company);
        balance.setEmployee(employee);
        balance.setLeaveType("CASUAL");
        balance.setBalanceYear(2026);
        balance.setOpeningBalance(BigDecimal.ZERO);
        balance.setAccrued(new BigDecimal("12.00"));
        balance.setUsed(new BigDecimal("3.00"));
        balance.setRemaining(new BigDecimal("9.00"));

        when(companyEntityLookup.requireLeaveRequest(company, 401L)).thenReturn(leaveRequest);
        when(leaveBalanceRepository.findByCompanyAndEmployeeAndLeaveTypeAndBalanceYear(company, employee, "CASUAL", 2026))
                .thenReturn(Optional.of(balance));
        when(leaveRequestRepository.save(any(LeaveRequest.class))).thenAnswer(invocation -> invocation.getArgument(0));

        LeaveRequestDto dto = leaveService.updateLeaveStatus(401L, new LeaveStatusUpdateRequest("REJECTED", "Declined"));

        assertThat(dto.status()).isEqualTo("REJECTED");
        assertThat(dto.rejectedBy()).isNotBlank();
        verify(leaveBalanceRepository).save(any(LeaveBalance.class));
        assertThat(balance.getUsed()).isEqualByComparingTo("2.00");
        assertThat(balance.getRemaining()).isEqualByComparingTo("10.00");
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void updateLeaveStatus_invalidTransitionFromApprovedToPendingRejected() {
        LeaveRequest leaveRequest = new LeaveRequest();
        leaveRequest.setCompany(company);
        leaveRequest.setStatus("APPROVED");
        leaveRequest.setLeaveType("CASUAL");
        leaveRequest.setStartDate(LocalDate.of(2026, 3, 1));
        leaveRequest.setEndDate(LocalDate.of(2026, 3, 1));
        leaveRequest.setEmployee(employee);

        when(companyEntityLookup.requireLeaveRequest(company, 501L)).thenReturn(leaveRequest);

        assertThatThrownBy(() -> leaveService.updateLeaveStatus(501L, new LeaveStatusUpdateRequest("PENDING", null)))
                .isInstanceOf(ApplicationException.class)
                .satisfies(ex -> assertThat(((ApplicationException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.BUSINESS_INVALID_STATE));
    }

    private LeaveTypePolicy activePolicy(String leaveType, String displayName) {
        LeaveTypePolicy policy = new LeaveTypePolicy();
        policy.setLeaveType(leaveType);
        policy.setDisplayName(displayName);
        policy.setAnnualEntitlement(new BigDecimal("12.00"));
        policy.setCarryForwardLimit(new BigDecimal("5.00"));
        policy.setActive(true);
        return policy;
    }

    private LeaveBalance balanceForYear(int year) {
        LeaveBalance balance = new LeaveBalance();
        balance.setCompany(company);
        balance.setEmployee(employee);
        balance.setLeaveType("CASUAL");
        balance.setBalanceYear(year);
        balance.setOpeningBalance(BigDecimal.ZERO);
        balance.setAccrued(new BigDecimal("12.00"));
        balance.setUsed(new BigDecimal("2.00"));
        balance.setRemaining(new BigDecimal("10.00"));
        balance.setCarryForwardApplied(BigDecimal.ZERO);
        return balance;
    }
}
