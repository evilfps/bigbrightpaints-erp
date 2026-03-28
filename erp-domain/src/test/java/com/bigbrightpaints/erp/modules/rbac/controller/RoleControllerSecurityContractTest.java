package com.bigbrightpaints.erp.modules.rbac.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.modules.rbac.dto.CreateRoleRequest;
import com.bigbrightpaints.erp.modules.rbac.dto.RoleDto;
import com.bigbrightpaints.erp.modules.rbac.service.RoleService;
import com.bigbrightpaints.erp.shared.dto.ApiResponse;

class RoleControllerSecurityContractTest {

  @Test
  void createRole_usesRoleMutationGuardAtControllerBoundary() throws Exception {
    Method method = RoleController.class.getMethod("createRole", CreateRoleRequest.class);

    PreAuthorize annotation = method.getAnnotation(PreAuthorize.class);

    assertThat(annotation).isNotNull();
    assertThat(annotation.value()).isEqualTo("hasAnyAuthority('ROLE_ADMIN','ROLE_SUPER_ADMIN')");
  }

  @Test
  void getRoleByKey_rejectsUnknownRolesInsteadOfFabricatingPlaceholderDto() {
    RoleService roleService = mock(RoleService.class);
    when(roleService.listRolesForCurrentActor())
        .thenReturn(List.of(new RoleDto(1L, "ROLE_SALES", "Sales", List.of())));
    RoleController controller = new RoleController(roleService);

    assertThatThrownBy(() -> controller.getRoleByKey("warehouse"))
        .isInstanceOfSatisfying(
            ApplicationException.class,
            ex -> assertThat(ex.getMessage()).isEqualTo("Unknown platform role: ROLE_WAREHOUSE"));
  }

  @Test
  void getRoleByKey_returnsExistingRoleWithoutRenamingCanonicalKey() {
    RoleService roleService = mock(RoleService.class);
    RoleDto salesRole = new RoleDto(1L, "ROLE_SALES", "Sales", List.of());
    when(roleService.listRolesForCurrentActor()).thenReturn(List.of(salesRole));
    RoleController controller = new RoleController(roleService);

    ResponseEntity<ApiResponse<RoleDto>> response = controller.getRoleByKey("ROLE_SALES");

    assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().message()).isEqualTo("Role ROLE_SALES");
    assertThat(response.getBody().data()).isEqualTo(salesRole);
  }
}
