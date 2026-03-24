package com.bigbrightpaints.erp.modules.company.controller;

import com.bigbrightpaints.erp.modules.company.dto.CoATemplateDto;
import com.bigbrightpaints.erp.modules.company.dto.TenantOnboardingRequest;
import com.bigbrightpaints.erp.modules.company.dto.TenantOnboardingResponse;
import com.bigbrightpaints.erp.modules.company.service.CoATemplateService;
import com.bigbrightpaints.erp.modules.company.service.TenantOnboardingService;
import com.bigbrightpaints.erp.shared.dto.ApiResponse;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/superadmin/tenants")
@PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN')")
public class SuperAdminTenantOnboardingController {

    private final CoATemplateService coATemplateService;
    private final TenantOnboardingService tenantOnboardingService;

    public SuperAdminTenantOnboardingController(CoATemplateService coATemplateService,
                                                TenantOnboardingService tenantOnboardingService) {
        this.coATemplateService = coATemplateService;
        this.tenantOnboardingService = tenantOnboardingService;
    }

    @GetMapping("/coa-templates")
    public ResponseEntity<ApiResponse<List<CoATemplateDto>>> listCoATemplates() {
        return ResponseEntity.ok(ApiResponse.success(
                "CoA templates fetched",
                coATemplateService.listActiveTemplates()));
    }

    @PostMapping("/onboard")
    public ResponseEntity<ApiResponse<TenantOnboardingResponse>> onboardTenant(
            @Valid @RequestBody TenantOnboardingRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                "Tenant onboarded with seeded chart of accounts, tenant admin, and default accounting period",
                tenantOnboardingService.onboardTenant(request)));
    }
}
