package com.bigbrightpaints.erp.modules.company.controller;

import com.bigbrightpaints.erp.modules.auth.domain.UserPrincipal;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.dto.CompanyDto;
import com.bigbrightpaints.erp.modules.company.dto.CompanyLifecycleStateDto;
import com.bigbrightpaints.erp.modules.company.dto.CompanyLifecycleStateRequest;
import com.bigbrightpaints.erp.modules.company.dto.CompanyRequest;
import com.bigbrightpaints.erp.modules.company.dto.CompanyTenantMetricsDto;
import com.bigbrightpaints.erp.modules.company.service.CompanyService;
import com.bigbrightpaints.erp.shared.dto.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("/api/v1/companies")
public class CompanyController {

    private final CompanyService companyService;

    public CompanyController(CompanyService companyService) {
        this.companyService = companyService;
    }

    @GetMapping
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING','ROLE_SALES')")
    public ResponseEntity<ApiResponse<List<CompanyDto>>> list(@AuthenticationPrincipal UserPrincipal principal) {
        if (principal == null) {
            return ResponseEntity.status(401).body(ApiResponse.failure("Unauthenticated"));
        }
        return ResponseEntity.ok(ApiResponse.success(companyService.findAll(requireCompanyContext(principal))));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<CompanyDto>> create(@Valid @RequestBody CompanyRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Company created", companyService.create(request)));
    }

    @PostMapping("/{id}/lifecycle-state")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<CompanyLifecycleStateDto>> updateLifecycleState(@PathVariable Long id,
                                                                                       @Valid @RequestBody CompanyLifecycleStateRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                "Company lifecycle state updated",
                companyService.updateLifecycleState(id, request)));
    }

    @GetMapping("/{id}/tenant-metrics")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<CompanyTenantMetricsDto>> getTenantMetrics(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(
                "Company tenant metrics fetched",
                companyService.getTenantMetrics(id)));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<CompanyDto>> update(@AuthenticationPrincipal UserPrincipal principal,
                                                           @PathVariable Long id,
                                                           @Valid @RequestBody CompanyRequest request) {
        Set<Company> allowedCompanies = requireCompanyContext(principal);
        return ResponseEntity.ok(ApiResponse.success("Company updated",
                companyService.update(id, request, allowedCompanies)));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal UserPrincipal principal,
                                       @PathVariable Long id) {
        Set<Company> allowedCompanies = requireCompanyContext(principal);
        if (allowedCompanies.stream().noneMatch(c -> c.getId().equals(id))) {
            throw new AccessDeniedException("Not allowed to delete company");
        }
        throw new AccessDeniedException("Deleting companies is not permitted");
    }

    private Set<Company> requireCompanyContext(UserPrincipal principal) {
        if (principal == null
                || principal.getUser() == null
                || principal.getUser().getCompanies() == null
                || principal.getUser().getCompanies().isEmpty()) {
            throw new AccessDeniedException("Missing authenticated company context");
        }
        return principal.getUser().getCompanies();
    }
}
