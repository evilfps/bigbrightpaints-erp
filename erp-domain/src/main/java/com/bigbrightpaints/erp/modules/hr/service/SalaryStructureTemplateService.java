package com.bigbrightpaints.erp.modules.hr.service;

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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SalaryStructureTemplateService {

    private final CompanyContextService companyContextService;
    private final SalaryStructureTemplateRepository salaryStructureTemplateRepository;

    public SalaryStructureTemplateService(CompanyContextService companyContextService,
                                         SalaryStructureTemplateRepository salaryStructureTemplateRepository) {
        this.companyContextService = companyContextService;
        this.salaryStructureTemplateRepository = salaryStructureTemplateRepository;
    }

    public List<SalaryStructureTemplateDto> listTemplates() {
        Company company = companyContextService.requireCurrentCompany();
        return salaryStructureTemplateRepository.findByCompanyOrderByNameAsc(company)
                .stream()
                .map(this::toDto)
                .toList();
    }

    @Transactional
    public SalaryStructureTemplateDto createTemplate(SalaryStructureTemplateRequest request) {
        if (request == null) {
            throw new ApplicationException(ErrorCode.VALIDATION_MISSING_REQUIRED_FIELD,
                    "Salary structure template request is required");
        }
        Company company = companyContextService.requireCurrentCompany();

        salaryStructureTemplateRepository.findByCompanyAndCodeIgnoreCase(company, request.code().trim())
                .ifPresent(existing -> {
                    throw new ApplicationException(ErrorCode.BUSINESS_DUPLICATE_ENTRY,
                            "Salary structure template code already exists")
                            .withDetail("code", request.code());
                });

        SalaryStructureTemplate template = new SalaryStructureTemplate();
        template.setCompany(company);
        applyMutableFields(template, request);
        return toDto(salaryStructureTemplateRepository.save(template));
    }

    @Transactional
    public SalaryStructureTemplateDto updateTemplate(Long id, SalaryStructureTemplateRequest request) {
        if (request == null) {
            throw new ApplicationException(ErrorCode.VALIDATION_MISSING_REQUIRED_FIELD,
                    "Salary structure template request is required");
        }
        Company company = companyContextService.requireCurrentCompany();
        SalaryStructureTemplate template = salaryStructureTemplateRepository.findByCompanyAndId(company, id)
                .orElseThrow(() -> new ApplicationException(ErrorCode.VALIDATION_INVALID_REFERENCE,
                        "Salary structure template not found"));

        String incomingCode = request.code().trim();
        if (!incomingCode.equalsIgnoreCase(template.getCode())) {
            salaryStructureTemplateRepository.findByCompanyAndCodeIgnoreCase(company, incomingCode)
                    .ifPresent(existing -> {
                        throw new ApplicationException(ErrorCode.BUSINESS_DUPLICATE_ENTRY,
                                "Salary structure template code already exists")
                                .withDetail("code", request.code());
                    });
        }

        applyMutableFields(template, request);
        return toDto(salaryStructureTemplateRepository.save(template));
    }

    private void applyMutableFields(SalaryStructureTemplate template, SalaryStructureTemplateRequest request) {
        template.setCode(request.code().trim());
        template.setName(request.name().trim());
        template.setDescription(request.description());
        template.setBasicPay(safeMoney(request.basicPay()));
        template.setHra(safeMoney(request.hra()));
        template.setDa(safeMoney(request.da()));
        template.setSpecialAllowance(safeMoney(request.specialAllowance()));
        template.setEmployeePfRate(safeRate(request.employeePfRate(), new BigDecimal("12.00")));
        template.setEmployeeEsiRate(safeRate(request.employeeEsiRate(), new BigDecimal("0.75")));
        if (request.active() != null) {
            template.setActive(request.active());
        }

        BigDecimal total = template.totalEarnings();
        if (total.compareTo(BigDecimal.ZERO) <= 0) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                    "Salary structure must have positive total earnings");
        }
    }

    private BigDecimal safeMoney(BigDecimal value) {
        if (value == null) {
            return BigDecimal.ZERO;
        }
        if (value.compareTo(BigDecimal.ZERO) < 0) {
            throw new ApplicationException(ErrorCode.VALIDATION_OUT_OF_RANGE,
                    "Salary components cannot be negative")
                    .withDetail("value", value);
        }
        return value;
    }

    private BigDecimal safeRate(BigDecimal value, BigDecimal defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value.compareTo(BigDecimal.ZERO) < 0) {
            throw new ApplicationException(ErrorCode.VALIDATION_OUT_OF_RANGE,
                    "Rate cannot be negative")
                    .withDetail("value", value);
        }
        return value;
    }

    private SalaryStructureTemplateDto toDto(SalaryStructureTemplate template) {
        return new SalaryStructureTemplateDto(
                template.getId(),
                template.getPublicId(),
                template.getCode(),
                template.getName(),
                template.getDescription(),
                template.getBasicPay(),
                template.getHra(),
                template.getDa(),
                template.getSpecialAllowance(),
                template.totalEarnings(),
                template.getEmployeePfRate(),
                template.getEmployeeEsiRate(),
                template.isActive(),
                template.getCreatedAt());
    }
}
