package com.bigbrightpaints.erp.modules.company.controller;

import com.bigbrightpaints.erp.modules.company.dto.CompanyDto;
import com.bigbrightpaints.erp.modules.company.service.CompanyService;
import com.bigbrightpaints.erp.shared.dto.ApiResponse;
import com.bigbrightpaints.erp.modules.auth.domain.UserPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
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
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<CompanyDto>> switchCompany(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody SwitchCompanyRequest request) {
        if (principal == null) {
            return ResponseEntity.status(401).body(ApiResponse.failure("Unauthenticated"));
        }
        boolean member = principal.getUser().getCompanies().stream()
                .anyMatch(company -> company.getCode().equalsIgnoreCase(request.companyCode()));
        if (!member) {
            throw new AccessDeniedException("Not allowed to access company");
        }
        CompanyDto company = companyService.switchCompany(request.companyCode(), principal.getUser().getCompanies());
        return ResponseEntity.ok(ApiResponse.success("Switched company", company));
    }

    public record SwitchCompanyRequest(@NotBlank String companyCode) {}
}
