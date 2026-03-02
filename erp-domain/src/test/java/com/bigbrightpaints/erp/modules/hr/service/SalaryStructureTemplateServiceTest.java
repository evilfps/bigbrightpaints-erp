package com.bigbrightpaints.erp.modules.hr.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.hr.domain.SalaryStructureTemplate;
import com.bigbrightpaints.erp.modules.hr.domain.SalaryStructureTemplateRepository;
import com.bigbrightpaints.erp.modules.hr.dto.SalaryStructureTemplateDto;
import com.bigbrightpaints.erp.modules.hr.dto.SalaryStructureTemplateRequest;
import java.math.BigDecimal;
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
class SalaryStructureTemplateServiceTest {

    @Mock
    private CompanyContextService companyContextService;
    @Mock
    private SalaryStructureTemplateRepository salaryStructureTemplateRepository;

    private SalaryStructureTemplateService service;
    private Company company;

    @BeforeEach
    void setUp() {
        service = new SalaryStructureTemplateService(companyContextService, salaryStructureTemplateRepository);
        company = new Company();
        ReflectionTestUtils.setField(company, "id", 88L);
        when(companyContextService.requireCurrentCompany()).thenReturn(company);
    }

    @Test
    void createTemplate_appliesDefaultsAndPersists() {
        SalaryStructureTemplateRequest request = new SalaryStructureTemplateRequest(
                " STAFF_STD ",
                "Staff Standard",
                "Template",
                new BigDecimal("15000"),
                new BigDecimal("6000"),
                new BigDecimal("1000"),
                new BigDecimal("2000"),
                null,
                null,
                null);

        when(salaryStructureTemplateRepository.findByCompanyAndCodeIgnoreCase(company, "STAFF_STD"))
                .thenReturn(Optional.empty());
        when(salaryStructureTemplateRepository.save(any(SalaryStructureTemplate.class)))
                .thenAnswer(invocation -> {
                    SalaryStructureTemplate saved = invocation.getArgument(0);
                    ReflectionTestUtils.setField(saved, "id", 901L);
                    return saved;
                });

        SalaryStructureTemplateDto dto = service.createTemplate(request);

        assertThat(dto.id()).isEqualTo(901L);
        assertThat(dto.code()).isEqualTo("STAFF_STD");
        assertThat(dto.totalEarnings()).isEqualByComparingTo("24000");
        assertThat(dto.employeePfRate()).isEqualByComparingTo("12.00");
        assertThat(dto.employeeEsiRate()).isEqualByComparingTo("0.75");
        assertThat(dto.active()).isTrue();

        ArgumentCaptor<SalaryStructureTemplate> captor = ArgumentCaptor.forClass(SalaryStructureTemplate.class);
        verify(salaryStructureTemplateRepository).save(captor.capture());
        SalaryStructureTemplate saved = captor.getValue();
        assertThat(saved.getCompany()).isSameAs(company);
        assertThat(saved.getCode()).isEqualTo("STAFF_STD");
    }

    @Test
    void createTemplate_duplicateCodeRejected() {
        SalaryStructureTemplateRequest request = new SalaryStructureTemplateRequest(
                "STAFF_STD",
                "Staff",
                null,
                new BigDecimal("10000"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                new BigDecimal("12.00"),
                new BigDecimal("0.75"),
                true);

        when(salaryStructureTemplateRepository.findByCompanyAndCodeIgnoreCase(company, "STAFF_STD"))
                .thenReturn(Optional.of(new SalaryStructureTemplate()));

        assertThatThrownBy(() -> service.createTemplate(request))
                .isInstanceOf(ApplicationException.class)
                .satisfies(ex -> assertThat(((ApplicationException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.BUSINESS_DUPLICATE_ENTRY));
    }

    @Test
    void createTemplate_negativeComponentRejected() {
        SalaryStructureTemplateRequest request = new SalaryStructureTemplateRequest(
                "STAFF_STD",
                "Staff",
                null,
                new BigDecimal("-1"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                new BigDecimal("12.00"),
                new BigDecimal("0.75"),
                true);

        when(salaryStructureTemplateRepository.findByCompanyAndCodeIgnoreCase(company, "STAFF_STD"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createTemplate(request))
                .isInstanceOf(ApplicationException.class)
                .satisfies(ex -> assertThat(((ApplicationException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.VALIDATION_OUT_OF_RANGE));
    }

    @Test
    void listTemplates_returnsSortedDtos() {
        SalaryStructureTemplate first = new SalaryStructureTemplate();
        ReflectionTestUtils.setField(first, "id", 1L);
        first.setCode("A");
        first.setName("A name");
        first.setBasicPay(new BigDecimal("100"));

        SalaryStructureTemplate second = new SalaryStructureTemplate();
        ReflectionTestUtils.setField(second, "id", 2L);
        second.setCode("B");
        second.setName("B name");
        second.setBasicPay(new BigDecimal("200"));

        when(salaryStructureTemplateRepository.findByCompanyOrderByNameAsc(company))
                .thenReturn(List.of(first, second));

        List<SalaryStructureTemplateDto> result = service.listTemplates();

        assertThat(result).hasSize(2);
        assertThat(result.get(0).code()).isEqualTo("A");
        assertThat(result.get(1).code()).isEqualTo("B");
    }
}
