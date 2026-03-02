package com.bigbrightpaints.erp.modules.hr.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.core.security.CryptoService;
import com.bigbrightpaints.erp.core.util.CompanyEntityLookup;
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
class EmployeeServiceTest {

    @Mock
    private CompanyContextService companyContextService;
    @Mock
    private EmployeeRepository employeeRepository;
    @Mock
    private CompanyEntityLookup companyEntityLookup;
    @Mock
    private SalaryStructureTemplateRepository salaryStructureTemplateRepository;
    @Mock
    private CryptoService cryptoService;

    private EmployeeService employeeService;
    private Company company;

    @BeforeEach
    void setUp() {
        employeeService = new EmployeeService(
                companyContextService,
                employeeRepository,
                companyEntityLookup,
                salaryStructureTemplateRepository,
                cryptoService);

        company = new Company();
        ReflectionTestUtils.setField(company, "id", 21L);
        when(companyContextService.requireCurrentCompany()).thenReturn(company);
    }

    @Test
    void createEmployee_staffTemplateAndBankDetails_areMappedAndEncrypted() {
        SalaryStructureTemplate template = new SalaryStructureTemplate();
        ReflectionTestUtils.setField(template, "id", 501L);
        template.setCode("STAFF_STD");
        template.setName("Staff Standard");
        template.setBasicPay(new BigDecimal("20000"));
        template.setHra(new BigDecimal("10000"));

        EmployeeRequest request = new EmployeeRequest(
                "Priya",
                "Menon",
                "priya.menon@acme.test",
                "9999911111",
                "HR_SPECIALIST",
                LocalDate.of(2024, 1, 1),
                LocalDate.of(1992, 3, 10),
                "female",
                "Asha",
                "9999988888",
                "People Operations",
                "HR Manager",
                LocalDate.of(2024, 1, 1),
                "full_time",
                "staff",
                "monthly",
                501L,
                null,
                null,
                26,
                1,
                new BigDecimal("8"),
                new BigDecimal("1.5"),
                new BigDecimal("2.0"),
                "PF-7788",
                "ESI-8899",
                "ABCDE1234F",
                "new",
                "123456789012",
                "HDFC Bank",
                "HDFC0001234",
                "MG Road");

        when(salaryStructureTemplateRepository.findByCompanyAndId(company, 501L)).thenReturn(Optional.of(template));
        when(cryptoService.isEncrypted(any())).thenAnswer(invocation -> {
            String value = invocation.getArgument(0, String.class);
            return value != null && value.startsWith("enc:");
        });
        when(cryptoService.decrypt(any())).thenAnswer(invocation -> {
            String value = invocation.getArgument(0, String.class);
            return value != null && value.startsWith("enc:") ? value.substring(4) : value;
        });
        when(cryptoService.encrypt(any())).thenAnswer(invocation -> "enc:" + invocation.getArgument(0));
        when(employeeRepository.save(any(Employee.class))).thenAnswer(invocation -> {
            Employee saved = invocation.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", 111L);
            return saved;
        });

        EmployeeDto dto = employeeService.createEmployee(request);

        assertThat(dto.id()).isEqualTo(111L);
        assertThat(dto.salaryStructureTemplateId()).isEqualTo(501L);
        assertThat(dto.monthlySalary()).isEqualByComparingTo("30000");
        assertThat(dto.employeeType()).isEqualTo("STAFF");
        assertThat(dto.paymentSchedule()).isEqualTo("MONTHLY");
        assertThat(dto.taxRegime()).isEqualTo("NEW");
        assertThat(dto.bankAccountNumber()).isEqualTo("123456789012");

        ArgumentCaptor<Employee> captor = ArgumentCaptor.forClass(Employee.class);
        verify(employeeRepository).save(captor.capture());
        Employee savedEmployee = captor.getValue();
        assertThat(savedEmployee.getCompany()).isSameAs(company);
        assertThat(savedEmployee.getBankAccountNumberEncrypted()).isEqualTo("enc:123456789012");
        assertThat(savedEmployee.getBankNameEncrypted()).isEqualTo("enc:HDFC Bank");
        assertThat(savedEmployee.getIfscCodeEncrypted()).isEqualTo("enc:HDFC0001234");
        assertThat(savedEmployee.getBankBranchEncrypted()).isEqualTo("enc:MG Road");
        assertThat(savedEmployee.getBankAccountNumber()).isNull();
        assertThat(savedEmployee.getMonthlySalary()).isEqualByComparingTo("30000");
    }

    @Test
    void createEmployee_invalidPanRejected() {
        EmployeeRequest request = new EmployeeRequest(
                "A",
                "B",
                "a@b.com",
                null,
                null,
                LocalDate.of(2024, 1, 1),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                "STAFF",
                "MONTHLY",
                null,
                new BigDecimal("10000"),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                "bad-pan",
                null,
                null,
                null,
                null,
                null);

        assertThatThrownBy(() -> employeeService.createEmployee(request))
                .isInstanceOf(ApplicationException.class)
                .satisfies(ex -> assertThat(((ApplicationException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.VALIDATION_INVALID_FORMAT));
    }

    @Test
    void createEmployee_labourWithoutDailyWageRejected() {
        EmployeeRequest request = new EmployeeRequest(
                "A",
                "B",
                "a@b.com",
                null,
                null,
                LocalDate.of(2024, 1, 1),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                "LABOUR",
                "WEEKLY",
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

        assertThatThrownBy(() -> employeeService.createEmployee(request))
                .isInstanceOf(ApplicationException.class)
                .satisfies(ex -> assertThat(((ApplicationException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.VALIDATION_INVALID_INPUT));
    }
}
