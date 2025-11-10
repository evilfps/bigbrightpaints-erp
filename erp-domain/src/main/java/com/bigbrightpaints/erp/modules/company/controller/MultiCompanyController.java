package com.bigbrightpaints.erp.modules.company.controller;

import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.dto.CompanyDto;
import com.bigbrightpaints.erp.modules.company.service.CompanyService;
import com.bigbrightpaints.erp.shared.dto.ApiResponse;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/multi-company")
public class MultiCompanyController {

    private final CompanyService companyService;

    public MultiCompanyController(CompanyService companyService) {
        this.companyService = companyService;
    }

    @PostMapping("/companies/switch")
    public ResponseEntity<ApiResponse<CompanyDto>> switchCompany(@RequestBody SwitchCompanyRequest request) {
        Company company = companyService.findByCode(request.companyCode());
        return ResponseEntity.ok(ApiResponse.success("Switched company",
                new CompanyDto(company.getId(), company.getPublicId(), company.getName(), company.getCode(), company.getTimezone())));
    }

    public record SwitchCompanyRequest(@NotBlank String companyCode) {}
}
