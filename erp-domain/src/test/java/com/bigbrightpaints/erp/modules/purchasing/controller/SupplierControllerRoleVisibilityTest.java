package com.bigbrightpaints.erp.modules.purchasing.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import com.bigbrightpaints.erp.modules.purchasing.dto.SupplierResponse;
import com.bigbrightpaints.erp.modules.purchasing.service.SupplierService;

@Tag("critical")
class SupplierControllerRoleVisibilityTest {

  private final SupplierService supplierService = mock(SupplierService.class);
  private final SupplierController controller = new SupplierController(supplierService);

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
