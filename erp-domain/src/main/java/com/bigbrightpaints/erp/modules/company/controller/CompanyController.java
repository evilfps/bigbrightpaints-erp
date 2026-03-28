package com.bigbrightpaints.erp.modules.company.controller;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import com.bigbrightpaints.erp.modules.auth.domain.UserPrincipal;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.dto.CompanyDto;
import com.bigbrightpaints.erp.modules.company.service.CompanyService;
import com.bigbrightpaints.erp.shared.dto.ApiResponse;

@RestController
@RequestMapping("/api/v1/companies")
public class CompanyController {

  private final CompanyService companyService;

  public CompanyController(CompanyService companyService) {
    this.companyService = companyService;
  }

  @GetMapping
  @PreAuthorize("hasAnyAuthority('ROLE_SUPER_ADMIN','ROLE_ADMIN','ROLE_ACCOUNTING','ROLE_SALES')")
  public ResponseEntity<ApiResponse<List<CompanyDto>>> list(
      @AuthenticationPrincipal UserPrincipal principal) {
    if (principal == null) {
      return ResponseEntity.status(401).body(ApiResponse.failure("Unauthenticated"));
    }
    if (isSuperAdmin(principal)) {
      return ResponseEntity.ok(ApiResponse.success(companyService.findAll()));
    }
    return ResponseEntity.ok(
        ApiResponse.success(companyService.findAll(requireCompanyContext(principal))));
  }

  @DeleteMapping("/{id}")
  @PreAuthorize("hasAuthority('ROLE_ADMIN')")
  public ResponseEntity<Void> delete(
      @AuthenticationPrincipal UserPrincipal principal, @PathVariable Long id) {
    Company allowedCompany = requireCompanyContext(principal);
    if (allowedCompany.getId() == null || !allowedCompany.getId().equals(id)) {
      throw new AccessDeniedException("Not allowed to delete company");
    }
    throw new AccessDeniedException("Deleting companies is not permitted");
  }

  private Company requireCompanyContext(UserPrincipal principal) {
    if (principal == null
        || principal.getUser() == null
        || principal.getUser().getCompany() == null) {
      throw new AccessDeniedException("Missing authenticated company context");
    }
    return principal.getUser().getCompany();
  }

  private boolean isSuperAdmin(UserPrincipal principal) {
    return principal.getUser().getRoles().stream()
        .anyMatch(role -> "ROLE_SUPER_ADMIN".equalsIgnoreCase(role.getName()));
  }
}
