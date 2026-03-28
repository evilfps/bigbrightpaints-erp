package com.bigbrightpaints.erp.modules.company.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.bigbrightpaints.erp.modules.company.dto.CompanyDto;
import com.bigbrightpaints.erp.modules.company.service.CompanyService;
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
  void delete_is_not_declared_on_company_controller() {
    assertThat(Arrays.stream(CompanyController.class.getDeclaredMethods()))
        .noneMatch(method -> method.getName().equals("delete"));
  }
}
