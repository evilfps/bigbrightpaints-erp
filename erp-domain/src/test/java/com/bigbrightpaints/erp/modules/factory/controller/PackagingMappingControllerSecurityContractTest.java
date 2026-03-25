package com.bigbrightpaints.erp.modules.factory.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

import com.bigbrightpaints.erp.modules.factory.dto.PackagingSizeMappingRequest;

@Tag("critical")
class PackagingMappingControllerSecurityContractTest {

  @Test
  void listMappings_requiresAdminOrFactoryAuthorityAnnotation() throws NoSuchMethodException {
    assertPreAuthorize(
        PackagingMappingController.class.getMethod("listMappings"),
        "hasAnyAuthority('ROLE_ADMIN','ROLE_FACTORY')");
  }

  @Test
  void listActiveMappings_requiresAdminOrFactoryAuthorityAnnotation() throws NoSuchMethodException {
    assertPreAuthorize(
        PackagingMappingController.class.getMethod("listActiveMappings"),
        "hasAnyAuthority('ROLE_ADMIN','ROLE_FACTORY')");
  }

  @Test
  void createMapping_requiresAdminAuthorityAnnotation() throws NoSuchMethodException {
    assertPreAuthorize(
        PackagingMappingController.class.getMethod(
            "createMapping", PackagingSizeMappingRequest.class),
        "hasAnyAuthority('ROLE_ADMIN')");
  }

  @Test
  void updateMapping_requiresAdminAuthorityAnnotation() throws NoSuchMethodException {
    assertPreAuthorize(
        PackagingMappingController.class.getMethod(
            "updateMapping", Long.class, PackagingSizeMappingRequest.class),
        "hasAnyAuthority('ROLE_ADMIN')");
  }

  @Test
  void deactivateMapping_requiresAdminAuthorityAnnotation() throws NoSuchMethodException {
    assertPreAuthorize(
        PackagingMappingController.class.getMethod("deactivateMapping", Long.class),
        "hasAnyAuthority('ROLE_ADMIN')");
  }

  private void assertPreAuthorize(Method method, String expectedValue) {
    PreAuthorize annotation = method.getAnnotation(PreAuthorize.class);
    assertThat(annotation).isNotNull();
    assertThat(annotation.value()).isEqualTo(expectedValue);
  }
}
