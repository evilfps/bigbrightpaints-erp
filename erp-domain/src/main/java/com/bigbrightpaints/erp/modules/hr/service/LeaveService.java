package com.bigbrightpaints.erp.modules.hr.service;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.core.security.SecurityActorResolver;
import com.bigbrightpaints.erp.core.util.CompanyEntityLookup;
import com.bigbrightpaints.erp.core.util.CompanyTime;
import com.bigbrightpaints.erp.core.validation.ValidationUtils;
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
import com.bigbrightpaints.erp.modules.hr.dto.LeaveBalanceDto;
import com.bigbrightpaints.erp.modules.hr.dto.LeaveRequestDto;
import com.bigbrightpaints.erp.modules.hr.dto.LeaveRequestRequest;
import com.bigbrightpaints.erp.modules.hr.dto.LeaveStatusUpdateRequest;
import com.bigbrightpaints.erp.modules.hr.dto.LeaveTypePolicyDto;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class LeaveService {

    private final CompanyContextService companyContextService;
    private final EmployeeRepository employeeRepository;
    private final LeaveRequestRepository leaveRequestRepository;
    private final CompanyEntityLookup companyEntityLookup;
    private final LeaveTypePolicyRepository leaveTypePolicyRepository;
    private final LeaveBalanceRepository leaveBalanceRepository;

    public LeaveService(CompanyContextService companyContextService,
                        EmployeeRepository employeeRepository,
                        LeaveRequestRepository leaveRequestRepository,
                        CompanyEntityLookup companyEntityLookup,
                        LeaveTypePolicyRepository leaveTypePolicyRepository,
                        LeaveBalanceRepository leaveBalanceRepository) {
        this.companyContextService = companyContextService;
        this.employeeRepository = employeeRepository;
        this.leaveRequestRepository = leaveRequestRepository;
        this.companyEntityLookup = companyEntityLookup;
        this.leaveTypePolicyRepository = leaveTypePolicyRepository;
        this.leaveBalanceRepository = leaveBalanceRepository;
    }

    public List<LeaveRequestDto> listLeaveRequests() {
        Company company = companyContextService.requireCurrentCompany();
        return leaveRequestRepository.findByCompanyOrderByCreatedAtDesc(company)
                .stream()
                .map(this::toDto)
                .toList();
    }

    public List<LeaveTypePolicyDto> listLeaveTypePolicies() {
        Company company = companyContextService.requireCurrentCompany();
        return leaveTypePolicyRepository.findByCompanyAndActiveTrueOrderByDisplayNameAsc(company)
                .stream()
                .map(this::toPolicyDto)
                .toList();
    }

    public List<LeaveBalanceDto> getLeaveBalances(Long employeeId, Integer year) {
        Company company = companyContextService.requireCurrentCompany();
        Employee employee = companyEntityLookup.requireEmployee(company, employeeId);
        int targetYear = year != null ? year : CompanyTime.today(company).getYear();

        return leaveTypePolicyRepository.findByCompanyAndActiveTrueOrderByDisplayNameAsc(company)
                .stream()
                .map(policy -> ensureBalance(company, employee, policy, targetYear))
                .map(this::toBalanceDto)
                .toList();
    }

    @Transactional
    public LeaveRequestDto createLeaveRequest(LeaveRequestRequest request) {
        Company company = companyContextService.requireCurrentCompany();
        if (request.employeeId() == null) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                    "Employee is required for leave request");
        }

        Employee employee = employeeRepository.lockByCompanyAndId(company, request.employeeId())
                .orElseThrow(() -> new ApplicationException(
                        ErrorCode.VALIDATION_INVALID_REFERENCE,
                        "Employee not found"));

        validateDateRange(request.startDate(), request.endDate());
        String leaveType = normalizeLeaveType(request.leaveType());

        if (leaveRequestRepository.existsOverlappingByEmployeeIdAndDates(
                company, request.employeeId(), request.startDate(), request.endDate())) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                    "Overlapping leave request exists for employee");
        }

        LeaveRequest leaveRequest = new LeaveRequest();
        leaveRequest.setCompany(company);
        leaveRequest.setEmployee(employee);
        leaveRequest.setLeaveType(leaveType);
        leaveRequest.setStartDate(request.startDate());
        leaveRequest.setEndDate(request.endDate());
        leaveRequest.setReason(request.reason());

        LeaveStatus status = request.status() != null
                ? parseLeaveStatus(request.status())
                : LeaveStatus.PENDING;
        leaveRequest.setStatus(status.name());

        BigDecimal totalDays = computeLeaveDays(request.startDate(), request.endDate());
        leaveRequest.setTotalDays(totalDays);
        leaveRequest.setDecisionReason(request.decisionReason());

        if (status == LeaveStatus.APPROVED) {
            applyApprovalMetadata(leaveRequest, request.decisionReason());
            applyBalanceDelta(company, employee, leaveType, request.startDate().getYear(), totalDays);
        } else if (status == LeaveStatus.REJECTED) {
            applyRejectionMetadata(leaveRequest, request.decisionReason());
        }

        return toDto(leaveRequestRepository.save(leaveRequest));
    }

    @Transactional
    public LeaveRequestDto updateLeaveStatus(Long id, LeaveStatusUpdateRequest request) {
        if (request == null) {
            throw new ApplicationException(ErrorCode.VALIDATION_MISSING_REQUIRED_FIELD,
                    "Leave status update request is required");
        }
        Company company = companyContextService.requireCurrentCompany();
        LeaveRequest leaveRequest = companyEntityLookup.requireLeaveRequest(company, id);
        LeaveStatus newStatus = parseLeaveStatus(request.status());
        LeaveStatus currentStatus = parseLeaveStatus(leaveRequest.getStatus());

        if (currentStatus == LeaveStatus.APPROVED && newStatus == LeaveStatus.PENDING) {
            throw new ApplicationException(ErrorCode.BUSINESS_INVALID_STATE,
                    "Approved leave cannot move back to PENDING");
        }

        BigDecimal leaveDays = leaveRequest.getTotalDays() != null
                ? leaveRequest.getTotalDays()
                : computeLeaveDays(leaveRequest.getStartDate(), leaveRequest.getEndDate());

        String normalizedLeaveType = normalizeLeaveType(leaveRequest.getLeaveType());
        int leaveYear = leaveRequest.getStartDate().getYear();

        if (newStatus == LeaveStatus.APPROVED && currentStatus != LeaveStatus.APPROVED) {
            applyApprovalMetadata(leaveRequest, request.decisionReason());
            applyBalanceDelta(company,
                    leaveRequest.getEmployee(),
                    normalizedLeaveType,
                    leaveYear,
                    leaveDays);
        }

        if (currentStatus == LeaveStatus.APPROVED && newStatus != LeaveStatus.APPROVED) {
            revertBalanceDelta(company,
                    leaveRequest.getEmployee(),
                    normalizedLeaveType,
                    leaveYear,
                    leaveDays);
        }

        if (newStatus == LeaveStatus.REJECTED && currentStatus != LeaveStatus.REJECTED) {
            applyRejectionMetadata(leaveRequest, request.decisionReason());
        }

        if (newStatus == LeaveStatus.PENDING) {
            leaveRequest.setApprovedBy(null);
            leaveRequest.setApprovedAt(null);
            leaveRequest.setRejectedBy(null);
            leaveRequest.setRejectedAt(null);
        }

        leaveRequest.setStatus(newStatus.name());
        leaveRequest.setDecisionReason(request.decisionReason());
        return toDto(leaveRequestRepository.save(leaveRequest));
    }

    private void validateDateRange(LocalDate startDate, LocalDate endDate) {
        try {
            ValidationUtils.validateDateRange(startDate, endDate, "startDate", "endDate");
        } catch (ApplicationException ex) {
            if (ex.getErrorCode() == ErrorCode.VALIDATION_MISSING_REQUIRED_FIELD) {
                throw new ApplicationException(ErrorCode.VALIDATION_MISSING_REQUIRED_FIELD,
                        "Leave startDate and endDate are required");
            }
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_DATE,
                    "Leave endDate cannot be before startDate")
                    .withDetail("startDate", startDate)
                    .withDetail("endDate", endDate);
        }
    }

    private void applyApprovalMetadata(LeaveRequest leaveRequest, String decisionReason) {
        leaveRequest.setApprovedBy(currentUsername());
        leaveRequest.setApprovedAt(CompanyTime.now(leaveRequest.getCompany()));
        leaveRequest.setRejectedBy(null);
        leaveRequest.setRejectedAt(null);
        leaveRequest.setDecisionReason(decisionReason);
    }

    private void applyRejectionMetadata(LeaveRequest leaveRequest, String decisionReason) {
        leaveRequest.setRejectedBy(currentUsername());
        leaveRequest.setRejectedAt(CompanyTime.now(leaveRequest.getCompany()));
        leaveRequest.setApprovedBy(null);
        leaveRequest.setApprovedAt(null);
        leaveRequest.setDecisionReason(decisionReason);
    }

    private void applyBalanceDelta(Company company,
                                   Employee employee,
                                   String leaveType,
                                   int year,
                                   BigDecimal requestedDays) {
        LeaveTypePolicy policy = leaveTypePolicyRepository
                .findByCompanyAndLeaveTypeIgnoreCase(company, leaveType)
                .orElseThrow(() -> new ApplicationException(
                        ErrorCode.VALIDATION_INVALID_INPUT,
                        "Unknown leave type policy")
                        .withDetail("leaveType", leaveType));

        LeaveBalance balance = ensureBalance(company, employee, policy, year);
        BigDecimal remainingAfter = balance.getRemaining().subtract(requestedDays);
        if (remainingAfter.compareTo(BigDecimal.ZERO) < 0) {
            throw new ApplicationException(ErrorCode.BUSINESS_LIMIT_EXCEEDED,
                    "Insufficient leave balance")
                    .withDetail("leaveType", leaveType)
                    .withDetail("remaining", balance.getRemaining())
                    .withDetail("requested", requestedDays);
        }

        balance.setUsed(balance.getUsed().add(requestedDays));
        balance.setRemaining(remainingAfter);
        balance.setLastRecalculatedAt(CompanyTime.now(company));
        leaveBalanceRepository.save(balance);
    }

    private void revertBalanceDelta(Company company,
                                    Employee employee,
                                    String leaveType,
                                    int year,
                                    BigDecimal requestedDays) {
        leaveBalanceRepository.findByCompanyAndEmployeeAndLeaveTypeAndBalanceYear(company, employee, leaveType, year)
                .ifPresent(balance -> {
                    BigDecimal adjustedUsed = balance.getUsed().subtract(requestedDays);
                    if (adjustedUsed.compareTo(BigDecimal.ZERO) < 0) {
                        adjustedUsed = BigDecimal.ZERO;
                    }
                    balance.setUsed(adjustedUsed);
                    balance.setRemaining(balance.getOpeningBalance().add(balance.getAccrued()).subtract(adjustedUsed));
                    balance.setLastRecalculatedAt(CompanyTime.now(company));
                    leaveBalanceRepository.save(balance);
                });
    }

    private LeaveBalance ensureBalance(Company company,
                                      Employee employee,
                                      LeaveTypePolicy policy,
                                      int year) {
        return leaveBalanceRepository.findByCompanyAndEmployeeAndLeaveTypeAndBalanceYear(
                        company,
                        employee,
                        policy.getLeaveType(),
                        year)
                .orElseGet(() -> createOpeningBalance(company, employee, policy, year));
    }

    private LeaveBalance createOpeningBalance(Company company,
                                              Employee employee,
                                              LeaveTypePolicy policy,
                                              int year) {
        LeaveBalance balance = new LeaveBalance();
        balance.setCompany(company);
        balance.setEmployee(employee);
        balance.setLeaveType(policy.getLeaveType());
        balance.setBalanceYear(year);

        BigDecimal carryForward = leaveBalanceRepository.findByCompanyAndEmployeeAndLeaveTypeAndBalanceYear(
                        company,
                        employee,
                        policy.getLeaveType(),
                        year - 1)
                .map(previous -> previous.getRemaining().min(policy.getCarryForwardLimit()))
                .orElse(BigDecimal.ZERO);

        balance.setCarryForwardApplied(carryForward);
        balance.setOpeningBalance(carryForward);
        balance.setAccrued(policy.getAnnualEntitlement());
        balance.setUsed(BigDecimal.ZERO);
        balance.setRemaining(carryForward.add(policy.getAnnualEntitlement()));
        balance.setLastRecalculatedAt(CompanyTime.now(company));
        return leaveBalanceRepository.save(balance);
    }

    private BigDecimal computeLeaveDays(LocalDate startDate, LocalDate endDate) {
        long inclusiveDays = ChronoUnit.DAYS.between(startDate, endDate) + 1;
        if (inclusiveDays <= 0) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(inclusiveDays).setScale(2, RoundingMode.HALF_UP);
    }

    private String normalizeLeaveType(String leaveType) {
        if (!StringUtils.hasText(leaveType)) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                    "leaveType is required");
        }
        return leaveType.trim().toUpperCase(Locale.ROOT);
    }

    private LeaveStatus parseLeaveStatus(String rawLeaveStatus) {
        try {
            return ValidationUtils.parseEnum(LeaveStatus.class, rawLeaveStatus, "leaveStatus");
        } catch (ApplicationException ex) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                    "Invalid leave status. Allowed values: "
                            + Arrays.toString(LeaveStatus.values()))
                    .withDetail("leaveStatus", rawLeaveStatus);
        }
    }

    private LeaveRequestDto toDto(LeaveRequest leaveRequest) {
        String employeeName = leaveRequest.getEmployee() != null
                ? leaveRequest.getEmployee().getFirstName() + " " + leaveRequest.getEmployee().getLastName()
                : null;
        Long employeeId = leaveRequest.getEmployee() != null ? leaveRequest.getEmployee().getId() : null;
        return new LeaveRequestDto(
                leaveRequest.getId(),
                leaveRequest.getPublicId(),
                employeeId,
                employeeName,
                leaveRequest.getLeaveType(),
                leaveRequest.getStartDate(),
                leaveRequest.getEndDate(),
                leaveRequest.getTotalDays(),
                leaveRequest.getStatus(),
                leaveRequest.getReason(),
                leaveRequest.getDecisionReason(),
                leaveRequest.getApprovedBy(),
                leaveRequest.getApprovedAt(),
                leaveRequest.getRejectedBy(),
                leaveRequest.getRejectedAt(),
                leaveRequest.getCreatedAt());
    }

    private LeaveTypePolicyDto toPolicyDto(LeaveTypePolicy policy) {
        return new LeaveTypePolicyDto(
                policy.getId(),
                policy.getPublicId(),
                policy.getLeaveType(),
                policy.getDisplayName(),
                policy.getAnnualEntitlement(),
                policy.getCarryForwardLimit(),
                policy.isActive());
    }

    private LeaveBalanceDto toBalanceDto(LeaveBalance balance) {
        return new LeaveBalanceDto(
                balance.getEmployee().getId(),
                balance.getLeaveType(),
                balance.getBalanceYear(),
                balance.getOpeningBalance(),
                balance.getAccrued(),
                balance.getUsed(),
                balance.getRemaining(),
                balance.getCarryForwardApplied());
    }

    private String currentUsername() {
        return SecurityActorResolver.resolveActorWithSystemProcessFallback();
    }

    private enum LeaveStatus {
        PENDING,
        APPROVED,
        REJECTED,
        CANCELLED
    }
}
