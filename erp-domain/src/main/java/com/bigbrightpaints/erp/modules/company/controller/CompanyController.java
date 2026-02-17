package com.bigbrightpaints.erp.modules.company.controller;

import com.bigbrightpaints.erp.modules.company.dto.CompanyDto;
import com.bigbrightpaints.erp.modules.company.dto.CompanyLifecycleStateDto;
import com.bigbrightpaints.erp.modules.company.dto.CompanyLifecycleStateRequest;
import com.bigbrightpaints.erp.modules.company.dto.CompanyRequest;
import com.bigbrightpaints.erp.modules.company.dto.CompanyTenantMetricsDto;
import com.bigbrightpaints.erp.modules.company.service.CompanyService;
import com.bigbrightpaints.erp.shared.dto.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/companies")
public class CompanyController {

    private final CompanyService companyService;

    public CompanyController(CompanyService companyService) {
        this.companyService = companyService;
    }

    @GetMapping
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING','ROLE_SALES')")
    public ResponseEntity<ApiResponse<List<CompanyDto>>> list(@AuthenticationPrincipal com.bigbrightpaints.erp.modules.auth.domain.UserPrincipal principal) {
        if (principal == null) {
            return ResponseEntity.status(401).body(ApiResponse.failure("Unauthenticated"));
        }
        return ResponseEntity.ok(ApiResponse.success(companyService.findAll(principal.getUser().getCompanies())));
    }

    @PostMapping
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<CompanyDto>> create(@Valid @RequestBody CompanyRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Company created", companyService.create(request)));
    }

    @PostMapping("/{id}/lifecycle-state")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<CompanyLifecycleStateDto>> updateLifecycleState(@PathVariable Long id,
                                                                                       @Valid @RequestBody CompanyLifecycleStateRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                "Company lifecycle state updated",
                companyService.updateLifecycleState(id, request)));
    }

    @GetMapping("/{id}/tenant-metrics")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<CompanyTenantMetricsDto>> getTenantMetrics(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(
                "Company tenant metrics fetched",
                companyService.getTenantMetrics(id)));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<CompanyDto>> update(@AuthenticationPrincipal com.bigbrightpaints.erp.modules.auth.domain.UserPrincipal principal,
                                                           @PathVariable Long id,
                                                           @Valid @RequestBody CompanyRequest request) {
        if (principal == null || principal.getUser().getCompanies().stream().noneMatch(c -> c.getId().equals(id))) {
            throw new org.springframework.security.access.AccessDeniedException("Not allowed to update company");
        }
        return ResponseEntity.ok(ApiResponse.success("Company updated",
                companyService.update(id, request, principal.getUser().getCompanies())));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal com.bigbrightpaints.erp.modules.auth.domain.UserPrincipal principal,
                                       @PathVariable Long id) {
        if (principal == null || principal.getUser().getCompanies().stream().noneMatch(c -> c.getId().equals(id))) {
            throw new org.springframework.security.access.AccessDeniedException("Not allowed to delete company");
        }
        throw new org.springframework.security.access.AccessDeniedException("Deleting companies is not permitted");
    }
}
