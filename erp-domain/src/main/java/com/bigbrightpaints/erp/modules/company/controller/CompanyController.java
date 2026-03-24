package com.bigbrightpaints.erp.modules.company.controller;

import com.bigbrightpaints.erp.modules.auth.domain.UserPrincipal;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.dto.CompanyDto;
import com.bigbrightpaints.erp.modules.company.dto.CompanyAdminCredentialResetDto;
import com.bigbrightpaints.erp.modules.company.dto.CompanyLifecycleStateDto;
import com.bigbrightpaints.erp.modules.company.dto.CompanyLifecycleStateRequest;
import com.bigbrightpaints.erp.modules.company.dto.CompanyRequest;
import com.bigbrightpaints.erp.modules.company.dto.CompanySupportWarningDto;
import com.bigbrightpaints.erp.modules.company.dto.CompanyTenantMetricsDto;
import com.bigbrightpaints.erp.modules.company.service.CompanyService;
import com.bigbrightpaints.erp.modules.company.service.TenantRuntimeEnforcementService;
import com.bigbrightpaints.erp.shared.dto.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
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
    @PreAuthorize("hasAnyAuthority('ROLE_SUPER_ADMIN','ROLE_ADMIN','ROLE_ACCOUNTING','ROLE_SALES')")
    public ResponseEntity<ApiResponse<List<CompanyDto>>> list(@AuthenticationPrincipal UserPrincipal principal) {
        if (principal == null) {
            return ResponseEntity.status(401).body(ApiResponse.failure("Unauthenticated"));
        }
        if (isSuperAdmin(principal)) {
            return ResponseEntity.ok(ApiResponse.success(companyService.findAll()));
        }
        return ResponseEntity.ok(ApiResponse.success(companyService.findAll(requireCompanyContext(principal))));
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

    @PutMapping("/{id}/tenant-runtime/policy")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<TenantRuntimeEnforcementService.TenantRuntimeSnapshot>> updateTenantRuntimePolicy(
            @PathVariable Long id,
            @Valid @RequestBody CompanyTenantRuntimePolicyRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                "Company tenant runtime policy updated",
                companyService.updateTenantRuntimePolicy(id, request.toServiceRequest())));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<CompanyDto>> update(@PathVariable Long id,
                                                           @Valid @RequestBody CompanyRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Company updated",
                companyService.update(id, request, Set.of())));
    }

    @PostMapping("/{id}/support/admin-password-reset")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<CompanyAdminCredentialResetDto>> resetTenantAdminPassword(
            @PathVariable Long id,
            @Valid @RequestBody CompanyAdminPasswordResetRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                "Admin credentials reset and emailed",
                companyService.resetTenantAdminPassword(id, request.adminEmail(), request.reason())));
    }

    @PostMapping("/{id}/support/warnings")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<CompanySupportWarningDto>> issueSupportWarning(
            @PathVariable Long id,
            @Valid @RequestBody CompanySupportWarningRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                "Tenant warning issued",
                companyService.issueTenantSupportWarning(id, request.toServiceRequest())));
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

    private boolean isSuperAdmin(UserPrincipal principal) {
        return principal.getUser().getRoles().stream()
                .anyMatch(role -> "ROLE_SUPER_ADMIN".equalsIgnoreCase(role.getName()));
    }

    public record CompanyTenantRuntimePolicyRequest(String holdState,
                                                    @Size(max = 300, message = "reasonCode must be at most 300 characters")
                                                    String reasonCode,
                                                    @Min(value = 1, message = "maxConcurrentRequests must be at least 1")
                                                    Integer maxConcurrentRequests,
                                                    @Min(value = 1, message = "maxRequestsPerMinute must be at least 1")
                                                    Integer maxRequestsPerMinute,
                                                    @Min(value = 1, message = "maxActiveUsers must be at least 1")
                                                    Integer maxActiveUsers) {
        private CompanyService.TenantRuntimePolicyMutationRequest toServiceRequest() {
            return new CompanyService.TenantRuntimePolicyMutationRequest(
                    holdState,
                    reasonCode,
                    maxConcurrentRequests,
                    maxRequestsPerMinute,
                    maxActiveUsers);
        }
    }

    public record CompanyAdminPasswordResetRequest(
            @Email @NotBlank String adminEmail,
            @Size(max = 300, message = "reason must be at most 300 characters")
            String reason) {
        public CompanyAdminPasswordResetRequest(String adminEmail) {
            this(adminEmail, null);
        }
    }

    public record CompanySupportWarningRequest(
            @Size(max = 100, message = "warningCategory must be at most 100 characters")
            String warningCategory,
            @NotBlank @Size(max = 500, message = "message must be at most 500 characters")
            String message,
            @Size(max = 20, message = "requestedLifecycleState must be at most 20 characters")
            String requestedLifecycleState,
            @Min(value = 1, message = "gracePeriodHours must be at least 1")
            Integer gracePeriodHours) {
        private CompanyService.TenantSupportWarningRequest toServiceRequest() {
            return new CompanyService.TenantSupportWarningRequest(
                    warningCategory,
                    message,
                    requestedLifecycleState,
                    gracePeriodHours);
        }
    }
}
