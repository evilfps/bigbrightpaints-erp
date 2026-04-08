package com.bigbrightpaints.erp.modules.purchasing.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import com.bigbrightpaints.erp.modules.purchasing.dto.SupplierImportResponse;
import com.bigbrightpaints.erp.modules.purchasing.dto.SupplierRequest;
import com.bigbrightpaints.erp.modules.purchasing.dto.SupplierResponse;
import com.bigbrightpaints.erp.modules.purchasing.service.SupplierImportService;
import com.bigbrightpaints.erp.modules.purchasing.service.SupplierService;

@Tag("critical")
class SupplierControllerRoleVisibilityTest {

  private final SupplierService supplierService = mock(SupplierService.class);
  private final SupplierImportService supplierImportService = mock(SupplierImportService.class);
  private final SupplierController controller =
      new SupplierController(supplierService, supplierImportService);

  @Test
  void listSuppliers_redactsSensitiveBankDetailsForFactoryRole() {
    SupplierResponse supplierResponse = sampleSupplier();
    when(supplierService.listSuppliers(false)).thenReturn(List.of(supplierResponse));

    var response = controller.listSuppliers(authentication("ROLE_FACTORY"));

    verify(supplierService).listSuppliers(false);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().data()).containsExactly(supplierResponse);
  }

  @Test
  void listSuppliers_keepsSensitiveBankDetailsForAdminRole() {
    SupplierResponse supplierResponse = sampleSupplier();
    when(supplierService.listSuppliers(true)).thenReturn(List.of(supplierResponse));

    var response = controller.listSuppliers(authentication("ROLE_ADMIN"));

    verify(supplierService).listSuppliers(true);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().message()).isEqualTo("Suppliers");
  }

  @Test
  void getSupplier_keepsSensitiveBankDetailsForAccountingRole() {
    SupplierResponse supplierResponse = sampleSupplier();
    when(supplierService.getSupplier(55L, true)).thenReturn(supplierResponse);

    var response = controller.getSupplier(55L, authentication("ROLE_ACCOUNTING"));

    verify(supplierService).getSupplier(55L, true);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().data()).isEqualTo(supplierResponse);
  }

  @Test
  void listSuppliers_redactsSensitiveBankDetailsWhenAuthenticationIsMissing() {
    controller.listSuppliers(null);

    verify(supplierService).listSuppliers(false);
    verifyNoMoreInteractions(supplierService);
  }

  @Test
  void getSupplier_redactsSensitiveBankDetailsForFactoryRole() {
    controller.getSupplier(66L, authentication("ROLE_FACTORY"));

    verify(supplierService).getSupplier(66L, false);
  }

  @Test
  void getSupplier_redactsSensitiveBankDetailsWhenAuthoritiesAreMissing() {
    Authentication authentication = mock(Authentication.class);
    when(authentication.getAuthorities()).thenReturn(null);

    controller.getSupplier(67L, authentication);

    verify(supplierService).getSupplier(67L, false);
  }

  @Test
  void listSuppliers_redactsSensitiveBankDetailsForNonPrivilegedRole() {
    controller.listSuppliers(authentication("ROLE_SALES"));

    verify(supplierService).listSuppliers(false);
  }

  @Test
  void importSuppliers_routesThroughSupplierImportService() {
    MockMultipartFile file =
        new MockMultipartFile(
            "file",
            "suppliers.csv",
            "text/csv",
            "name,email,creditLimit,paymentTerms\nSupplier,supplier@example.com,1000,NET_30\n"
                .getBytes(StandardCharsets.UTF_8));
    SupplierImportResponse payload =
        new SupplierImportResponse(
            1, 0, List.of(new SupplierImportResponse.ImportError(0L, "placeholder")));
    when(supplierImportService.importSuppliers(file)).thenReturn(payload);

    var response = controller.importSuppliers(file);

    verify(supplierImportService).importSuppliers(file);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().data()).isEqualTo(payload);
  }

  @Test
  void createSupplier_returnsCreatedStatus() {
    SupplierRequest request =
        new SupplierRequest(
            "Supplier", "SUP-001", "supplier@example.com", "9999999999", "Address", BigDecimal.TEN);
    SupplierResponse payload = sampleSupplier();
    when(supplierService.createSupplier(request)).thenReturn(payload);

    var response = controller.createSupplier(request);

    verify(supplierService).createSupplier(request);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().data()).isEqualTo(payload);
  }

  private SupplierResponse sampleSupplier() {
    return new SupplierResponse(
        1L,
        java.util.UUID.randomUUID(),
        "SUP-001",
        "Supplier",
        null,
        "supplier@example.com",
        "9999999999",
        "Address",
        java.math.BigDecimal.TEN,
        java.math.BigDecimal.ONE,
        2L,
        "AP-SUP-001",
        "29ABCDE1234F1Z5",
        "KA",
        null,
        null,
        null,
        null,
        null,
        null);
  }

  private UsernamePasswordAuthenticationToken authentication(String authority) {
    return new UsernamePasswordAuthenticationToken(
        "user", "token", List.of(new SimpleGrantedAuthority(authority)));
  }
}
