package com.bigbrightpaints.erp.modules.hr.service;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.core.security.CryptoService;
import com.bigbrightpaints.erp.core.util.CompanyEntityLookup;
import com.bigbrightpaints.erp.core.validation.ValidationUtils;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.hr.domain.Employee;
import com.bigbrightpaints.erp.modules.hr.domain.EmployeeRepository;
import com.bigbrightpaints.erp.modules.hr.domain.SalaryStructureTemplate;
import com.bigbrightpaints.erp.modules.hr.domain.SalaryStructureTemplateRepository;
import com.bigbrightpaints.erp.modules.hr.dto.EmployeeDto;
import com.bigbrightpaints.erp.modules.hr.dto.EmployeeRequest;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class EmployeeService {

    private final CompanyContextService companyContextService;
    private final EmployeeRepository employeeRepository;
    private final CompanyEntityLookup companyEntityLookup;
    private final SalaryStructureTemplateRepository salaryStructureTemplateRepository;
    private final CryptoService cryptoService;

    public EmployeeService(CompanyContextService companyContextService,
                           EmployeeRepository employeeRepository,
                           CompanyEntityLookup companyEntityLookup,
                           SalaryStructureTemplateRepository salaryStructureTemplateRepository,
                           CryptoService cryptoService) {
        this.companyContextService = companyContextService;
        this.employeeRepository = employeeRepository;
        this.companyEntityLookup = companyEntityLookup;
        this.salaryStructureTemplateRepository = salaryStructureTemplateRepository;
        this.cryptoService = cryptoService;
    }

    public List<EmployeeDto> listEmployees() {
        Company company = companyContextService.requireCurrentCompany();
        return employeeRepository.findByCompanyOrderByFirstNameAsc(company)
                .stream()
                .map(this::toDto)
                .toList();
    }

    @Transactional
    public EmployeeDto createEmployee(EmployeeRequest request) {
        if (request == null) {
            throw new ApplicationException(ErrorCode.VALIDATION_MISSING_REQUIRED_FIELD,
                    "Employee request is required");
        }

        Company company = companyContextService.requireCurrentCompany();
        Employee employee = new Employee();
        employee.setCompany(company);
        applyMutableFields(employee, request, company);
        return toDto(employeeRepository.save(employee));
    }

    @Transactional
    public EmployeeDto updateEmployee(Long id, EmployeeRequest request) {
        if (request == null) {
            throw new ApplicationException(ErrorCode.VALIDATION_MISSING_REQUIRED_FIELD,
                    "Employee request is required");
        }

        Company company = companyContextService.requireCurrentCompany();
        Employee employee = employeeRepository.lockByCompanyAndId(company, id)
                .orElseThrow(() -> new ApplicationException(
                        ErrorCode.VALIDATION_INVALID_REFERENCE,
                        "Employee not found"));

        applyMutableFields(employee, request, company);
        return toDto(employeeRepository.save(employee));
    }

    @Transactional
    public void deleteEmployee(Long id) {
        Company company = companyContextService.requireCurrentCompany();
        Employee employee = companyEntityLookup.requireEmployee(company, id);
        employeeRepository.delete(employee);
    }

    private void applyMutableFields(Employee employee, EmployeeRequest request, Company company) {
        employee.setFirstName(request.firstName());
        employee.setLastName(request.lastName());
        employee.setEmail(request.email());
        employee.setRole(request.role());

        if (request.phone() != null) {
            employee.setPhone(request.phone());
        }
        if (request.hiredDate() != null) {
            employee.setHiredDate(request.hiredDate());
        }
        if (request.dateOfBirth() != null) {
            employee.setDateOfBirth(request.dateOfBirth());
        }
        if (request.gender() != null) {
            employee.setGender(parseGender(request.gender()));
        }
        if (request.emergencyContactName() != null) {
            employee.setEmergencyContactName(request.emergencyContactName());
        }
        if (request.emergencyContactPhone() != null) {
            employee.setEmergencyContactPhone(request.emergencyContactPhone());
        }
        if (request.department() != null) {
            employee.setDepartment(request.department());
        }
        if (request.designation() != null) {
            employee.setDesignation(request.designation());
        }
        if (request.dateOfJoining() != null) {
            employee.setDateOfJoining(request.dateOfJoining());
        }
        if (request.employmentType() != null) {
            employee.setEmploymentType(parseEmploymentType(request.employmentType()));
        }
        if (request.employeeType() != null) {
            employee.setEmployeeType(parseEmployeeType(request.employeeType()));
        }
        if (request.paymentSchedule() != null) {
            employee.setPaymentSchedule(parsePaymentSchedule(request.paymentSchedule()));
        }
        if (request.salaryStructureTemplateId() != null) {
            SalaryStructureTemplate template = salaryStructureTemplateRepository
                    .findByCompanyAndId(company, request.salaryStructureTemplateId())
                    .orElseThrow(() -> new ApplicationException(
                            ErrorCode.VALIDATION_INVALID_REFERENCE,
                            "Salary structure template not found"));
            employee.setSalaryStructureTemplate(template);
            if (request.monthlySalary() == null
                    && employee.getEmployeeType() == Employee.EmployeeType.STAFF
                    && template.totalEarnings().compareTo(BigDecimal.ZERO) > 0) {
                employee.setMonthlySalary(template.totalEarnings());
            }
        }
        if (request.monthlySalary() != null) {
            employee.setMonthlySalary(request.monthlySalary());
        }
        if (request.dailyWage() != null) {
            employee.setDailyWage(request.dailyWage());
        }
        if (request.workingDaysPerMonth() != null) {
            employee.setWorkingDaysPerMonth(request.workingDaysPerMonth());
        }
        if (request.weeklyOffDays() != null) {
            employee.setWeeklyOffDays(request.weeklyOffDays());
        }
        if (request.standardHoursPerDay() != null) {
            employee.setStandardHoursPerDay(request.standardHoursPerDay());
        }
        if (request.overtimeRateMultiplier() != null) {
            employee.setOvertimeRateMultiplier(request.overtimeRateMultiplier());
        }
        if (request.doubleOtRateMultiplier() != null) {
            employee.setDoubleOtRateMultiplier(request.doubleOtRateMultiplier());
        }
        if (request.pfNumber() != null) {
            employee.setPfNumber(normalizeToken(request.pfNumber()));
        }
        if (request.esiNumber() != null) {
            employee.setEsiNumber(normalizeToken(request.esiNumber()));
        }
        if (request.panNumber() != null) {
            String pan = normalizeToken(request.panNumber());
            validatePan(pan);
            employee.setPanNumber(pan);
        }
        if (request.taxRegime() != null) {
            employee.setTaxRegime(parseTaxRegime(request.taxRegime()));
        }

        if (request.hiredDate() == null && employee.getDateOfJoining() != null && employee.getHiredDate() == null) {
            employee.setHiredDate(employee.getDateOfJoining());
        }
        if (request.dateOfJoining() == null && employee.getHiredDate() != null && employee.getDateOfJoining() == null) {
            employee.setDateOfJoining(employee.getHiredDate());
        }

        applyEncryptedBankDetails(employee,
                request.bankAccountNumber(),
                request.bankName(),
                request.ifscCode(),
                request.bankBranch());

        validateDateChronology(employee.getDateOfBirth(), employee.getDateOfJoining());
        validateCompensation(employee);
    }

    private void applyEncryptedBankDetails(Employee employee,
                                           String bankAccountNumber,
                                           String bankName,
                                           String ifscCode,
                                           String bankBranch) {
        if (bankAccountNumber != null) {
            employee.setBankAccountNumberEncrypted(encryptOrNull(bankAccountNumber));
            employee.setBankAccountNumber(null);
        }
        if (bankName != null) {
            employee.setBankNameEncrypted(encryptOrNull(bankName));
            employee.setBankName(null);
        }
        if (ifscCode != null) {
            employee.setIfscCodeEncrypted(encryptOrNull(ifscCode));
            employee.setIfscCode(null);
        }
        if (bankBranch != null) {
            employee.setBankBranchEncrypted(encryptOrNull(bankBranch));
        }
    }

    private String encryptOrNull(String value) {
        String normalized = normalizeToken(value);
        if (!StringUtils.hasText(normalized)) {
            return null;
        }
        if (cryptoService == null) {
            return normalized;
        }
        if (cryptoService.isEncrypted(normalized)) {
            return normalized;
        }
        return cryptoService.encrypt(normalized);
    }

    private String decryptIfPresent(String encrypted, String fallbackPlain) {
        if (StringUtils.hasText(encrypted)) {
            if (cryptoService == null) {
                return encrypted;
            }
            if (!cryptoService.isEncrypted(encrypted)) {
                return encrypted;
            }
            return cryptoService.decrypt(encrypted);
        }
        return StringUtils.hasText(fallbackPlain) ? fallbackPlain : null;
    }

    private void validatePan(String pan) {
        if (!StringUtils.hasText(pan)) {
            return;
        }
        if (!pan.matches("^[A-Z]{5}[0-9]{4}[A-Z]$")) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_FORMAT,
                    "PAN must match format AAAAA9999A")
                    .withDetail("panNumber", pan);
        }
    }

    private void validateDateChronology(LocalDate dateOfBirth, LocalDate dateOfJoining) {
        if (dateOfBirth == null || dateOfJoining == null) {
            return;
        }
        try {
            ValidationUtils.validateDateRange(dateOfBirth, dateOfJoining, "dateOfBirth", "dateOfJoining");
        } catch (ApplicationException ex) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_DATE,
                    "dateOfJoining cannot be before dateOfBirth")
                    .withDetail("dateOfBirth", dateOfBirth)
                    .withDetail("dateOfJoining", dateOfJoining);
        }
    }

    private void validateCompensation(Employee employee) {
        if (employee.getEmployeeType() == Employee.EmployeeType.STAFF) {
            BigDecimal salary = employee.getMonthlySalary();
            if ((salary == null || salary.compareTo(BigDecimal.ZERO) <= 0)
                    && employee.getSalaryStructureTemplate() == null) {
                throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                        "Staff employee requires either monthlySalary or salaryStructureTemplateId");
            }
        }
        if (employee.getEmployeeType() == Employee.EmployeeType.LABOUR) {
            BigDecimal dailyWage = employee.getDailyWage();
            if (dailyWage == null || dailyWage.compareTo(BigDecimal.ZERO) <= 0) {
                throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                        "Labour employee requires positive dailyWage");
            }
        }
    }

    private Employee.EmployeeType parseEmployeeType(String rawEmployeeType) {
        return parseEnum(rawEmployeeType, Employee.EmployeeType.class, "employeeType");
    }

    private Employee.PaymentSchedule parsePaymentSchedule(String rawPaymentSchedule) {
        return parseEnum(rawPaymentSchedule, Employee.PaymentSchedule.class, "paymentSchedule");
    }

    private Employee.Gender parseGender(String rawGender) {
        return parseEnum(rawGender, Employee.Gender.class, "gender");
    }

    private Employee.EmploymentType parseEmploymentType(String rawEmploymentType) {
        return parseEnum(rawEmploymentType, Employee.EmploymentType.class, "employmentType");
    }

    private Employee.TaxRegime parseTaxRegime(String rawTaxRegime) {
        return parseEnum(rawTaxRegime, Employee.TaxRegime.class, "taxRegime");
    }

    private <E extends Enum<E>> E parseEnum(String rawValue, Class<E> enumClass, String fieldName) {
        try {
            return ValidationUtils.parseEnum(enumClass, rawValue, fieldName);
        } catch (ApplicationException ex) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                    "Invalid " + fieldName + ". Allowed values: " + Arrays.toString(enumClass.getEnumConstants()))
                    .withDetail(fieldName, rawValue);
        }
    }

    private String normalizeToken(String value) {
        return value == null ? null : value.trim();
    }

    private EmployeeDto toDto(Employee employee) {
        SalaryStructureTemplate template = employee.getSalaryStructureTemplate();
        return new EmployeeDto(
                employee.getId(),
                employee.getPublicId(),
                employee.getFirstName(),
                employee.getLastName(),
                employee.getEmail(),
                employee.getPhone(),
                employee.getRole(),
                employee.getStatus(),
                employee.getHiredDate(),
                employee.getDateOfBirth(),
                enumName(employee.getGender()),
                employee.getEmergencyContactName(),
                employee.getEmergencyContactPhone(),
                employee.getDepartment(),
                employee.getDesignation(),
                employee.getDateOfJoining(),
                enumName(employee.getEmploymentType()),
                enumName(employee.getEmployeeType()),
                enumName(employee.getPaymentSchedule()),
                template != null ? template.getId() : null,
                template != null ? template.getCode() : null,
                template != null ? template.getName() : null,
                template != null ? template.getBasicPay() : null,
                template != null ? template.getHra() : null,
                template != null ? template.getDa() : null,
                template != null ? template.getSpecialAllowance() : null,
                template != null ? template.getEsiEligibilityThreshold() : null,
                template != null ? template.getProfessionalTax() : null,
                employee.getMonthlySalary(),
                employee.getDailyWage(),
                employee.getPfNumber(),
                employee.getEsiNumber(),
                employee.getPanNumber(),
                enumName(employee.getTaxRegime()),
                decryptIfPresent(employee.getBankAccountNumberEncrypted(), employee.getBankAccountNumber()),
                decryptIfPresent(employee.getBankNameEncrypted(), employee.getBankName()),
                decryptIfPresent(employee.getIfscCodeEncrypted(), employee.getIfscCode()),
                decryptIfPresent(employee.getBankBranchEncrypted(), null),
                employee.getWorkingDaysPerMonth(),
                employee.getWeeklyOffDays(),
                employee.getConfiguredStandardHoursPerDay(),
                employee.getOvertimeRateMultiplier(),
                employee.getDoubleOtRateMultiplier(),
                employee.getAdvanceBalance());
    }

    private String enumName(Enum<?> value) {
        return value == null ? null : value.name();
    }
}
