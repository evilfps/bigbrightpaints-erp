package com.bigbrightpaints.erp.modules.company.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;

import com.bigbrightpaints.erp.modules.auth.domain.UserPrincipal;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccount;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.dto.CompanyDto;
import com.bigbrightpaints.erp.modules.company.service.CompanyService;
import com.bigbrightpaints.erp.modules.rbac.domain.Role;
import com.bigbrightpaints.erp.shared.dto.ApiResponse;

class CompanyControllerTest {

  @Test
  void list_returns401_whenPrincipalMissing() {
    CompanyService companyService = mock(CompanyService.class);
    CompanyController controller = new CompanyController(companyService);

    ResponseEntity<ApiResponse<List<CompanyDto>>> response = controller.list(null);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    verify(companyService, org.mockito.Mockito.never()).findAll();
  }

  @Test
  void delete_rejectsWhenAuthenticatedCompanyIdIsMissing() {
    CompanyService companyService = mock(CompanyService.class);
    CompanyController controller = new CompanyController(companyService);
    UserPrincipal principal = principal("ROLE_ADMIN", null);

    assertThatThrownBy(() -> controller.delete(principal, 5L))
        .isInstanceOf(AccessDeniedException.class)
        .hasMessageContaining("Missing authenticated company context");
  }

  @Test
  void delete_rejectsWhenAuthenticatedCompanyDoesNotMatchPath() {
    CompanyService companyService = mock(CompanyService.class);
    CompanyController controller = new CompanyController(companyService);
    UserPrincipal principal = principal("ROLE_ADMIN", 7L);

    assertThatThrownBy(() -> controller.delete(principal, 5L))
        .isInstanceOf(AccessDeniedException.class)
        .hasMessageContaining("Not allowed to delete company");
  }

  @Test
  void delete_rejectsDeletionEvenWhenAuthenticatedCompanyMatchesPath() {
    CompanyService companyService = mock(CompanyService.class);
    CompanyController controller = new CompanyController(companyService);
    UserPrincipal principal = principal("ROLE_ADMIN", 5L);

    assertThatThrownBy(() -> controller.delete(principal, 5L))
        .isInstanceOf(AccessDeniedException.class)
        .hasMessageContaining("Deleting companies is not permitted");
  }

  private UserPrincipal principal(String roleName, Long companyId) {
    Company company = new Company();
    ReflectionTestUtils.setField(company, "id", companyId);
    company.setCode("ACME");

    Role role = new Role();
    role.setName(roleName);

    UserAccount user = new UserAccount("admin@example.com", "ACME", "hash", "Admin");
    user.setCompany(companyId == null ? null : company);
    user.getRoles().add(role);
    return new UserPrincipal(user);
  }
}
