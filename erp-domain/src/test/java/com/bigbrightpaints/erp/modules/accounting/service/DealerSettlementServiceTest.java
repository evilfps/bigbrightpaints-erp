package com.bigbrightpaints.erp.modules.accounting.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

class DealerSettlementServiceTest {

  @Test
  void settlementWritersUseFocusedResolutionCollaborators() {
    Set<Class<?>> fieldTypes =
        Arrays.stream(DealerSettlementService.class.getDeclaredFields())
            .map(field -> field.getType())
            .collect(Collectors.toSet());
    assertThat(fieldTypes)
        .contains(
            SettlementAllocationResolutionService.class,
            SettlementTotalsValidationService.class,
            SettlementJournalLineDraftService.class);
    assertThat(serviceFile("SettlementRequestResolutionService.java").toFile()).doesNotExist();
    assertThat(readService("DealerSettlementService.java"))
        .doesNotContain("SettlementRequestResolutionService");
  }

  @Test
  void settlementWritePathHasNoInjectedOversizedReplacementSeam() {
    Map<Class<?>, String> settlementWriteOwners =
        Map.of(
            DealerSettlementService.class, "DealerSettlementService",
            SupplierSettlementService.class, "SupplierSettlementService",
            SupplierPaymentService.class, "SupplierPaymentService");

    Set<String> oversizedCollaborators =
        settlementWriteOwners.keySet().stream()
            .flatMap(
                owner ->
                    Arrays.stream(owner.getDeclaredFields())
                        .map(field -> field.getType())
                        .filter(
                            type ->
                                type.getPackageName()
                                    .equals("com.bigbrightpaints.erp.modules.accounting.service"))
                        .map(Class::getSimpleName))
            .filter(name -> name.contains("Settlement"))
            .filter(name -> !settlementWriteOwners.containsValue(name))
            .filter(name -> lineCount(name + ".java") >= 500)
            .collect(Collectors.toSet());

    assertThat(oversizedCollaborators)
        .as(
            "No settlement writer may inject a renamed settlement-resolution seam above the"
                + " 500-line mission cap")
        .isEmpty();
  }

  private Path serviceFile(String name) {
    return Path.of(
        "/home/realnigga/Desktop/Mission-control/erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/service/"
            + name);
  }

  private String readService(String name) {
    try {
      return Files.readString(serviceFile(name));
    } catch (IOException ex) {
      throw new AssertionError(ex);
    }
  }

  private long lineCount(String name) {
    try {
      return Files.lines(serviceFile(name)).count();
    } catch (IOException ex) {
      throw new AssertionError(ex);
    }
  }
}
