package com.bigbrightpaints.erp.modules.purchasing.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import com.bigbrightpaints.erp.modules.purchasing.service.SupplierService;

class SupplierControllerRoleVisibilityTest {

  private final SupplierService supplierService = mock(SupplierService.class);
  private final SupplierController controller = new SupplierController(supplierService);

  @Test
  void listSuppliers_redactsSensitiveBankDetailsForFactoryRole() {
    controller.listSuppliers(authentication("ROLE_FACTORY"));

    verify(supplierService).listSuppliers(false);
  }

  @Test
  void listSuppliers_keepsSensitiveBankDetailsForAdminRole() {
    controller.listSuppliers(authentication("ROLE_ADMIN"));

    verify(supplierService).listSuppliers(true);
  }

  @Test
  void getSupplier_keepsSensitiveBankDetailsForAccountingRole() {
    controller.getSupplier(55L, authentication("ROLE_ACCOUNTING"));

    verify(supplierService).getSupplier(55L, true);
  }

  private UsernamePasswordAuthenticationToken authentication(String authority) {
    return new UsernamePasswordAuthenticationToken(
        "user", "token", List.of(new SimpleGrantedAuthority(authority)));
  }
}
