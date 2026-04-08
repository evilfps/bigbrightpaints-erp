package com.bigbrightpaints.erp.modules.accounting.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

class JournalCreationOwnershipContractTest {

  @Test
  void deletedAccountingCoreEngineCoreRemainsUnavailable() {
    assertThat(
            Path.of(
                    "/home/realnigga/Desktop/Mission-control/erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/internal/AccountingCoreEngineCore.java")
                .toFile())
        .doesNotExist();
  }

  @Test
  void journalEntryServiceUsesFocusedCollaboratorsInsteadOfInheritance() {
    assertThat(JournalEntryService.class.getSuperclass()).isEqualTo(Object.class);
    assertThat(fieldTypes(JournalEntryService.class))
        .contains(
            JournalPostingService.class,
            JournalQueryService.class,
            JournalReversalService.class,
            ManualJournalService.class,
            ClosingEntryReversalService.class);
  }

  @Test
  void accountingServiceUsesComposedCollaboratorsInsteadOfInheritance() {
    assertThat(AccountingService.class.getSuperclass()).isEqualTo(Object.class);
    assertThat(fieldTypes(AccountingService.class))
        .contains(
            AccountCatalogService.class,
            JournalEntryService.class,
            DealerReceiptService.class,
            SettlementService.class,
            CreditDebitNoteService.class,
            InventoryAccountingService.class)
        .doesNotContain(PayrollAccountingService.class);
  }

  @Test
  void accountingFacadeOwnsPayrollBoundaryDirectlyThroughFocusedService() {
    assertThat(fieldTypes(AccountingFacade.class)).contains(PayrollAccountingService.class);
  }

  @Test
  void legacySettlementSupportSeamsRemainDeleted() {
    assertThat(serviceFile("SettlementSupportService.java").toFile()).doesNotExist();
    assertThat(serviceFile("SettlementCoreSupport.java").toFile()).doesNotExist();
    assertThat(serviceFile("SettlementRequestResolutionService.java").toFile()).doesNotExist();
    assertThat(legacySettlementSupportFiles()).isEmpty();
  }

  @Test
  void settlementServiceUsesFocusedSettlementCollaborators() {
    assertThat(SettlementService.class.getSuperclass()).isEqualTo(Object.class);
    assertThat(fieldTypes(SettlementService.class))
        .contains(
            SupplierPaymentService.class,
            DealerSettlementService.class,
            SupplierSettlementService.class);
  }

  @Test
  void settlementAndPostingFacadesDoNotDependOnDeletedLegacySeams() {
    assertThat(readService("DealerReceiptService.java")).doesNotContain("SettlementSupportService");
    assertThat(readService("SupplierPaymentService.java"))
        .doesNotContain("SettlementSupportService")
        .doesNotContain("SettlementRequestResolutionService");
    assertThat(readService("DealerSettlementService.java"))
        .doesNotContain("SettlementSupportService")
        .doesNotContain("SettlementRequestResolutionService");
    assertThat(readService("SupplierSettlementService.java"))
        .doesNotContain("SettlementSupportService")
        .doesNotContain("SettlementRequestResolutionService");
    assertThat(readService("ManualJournalService.java")).doesNotContain("SettlementSupportService");
    assertThat(readService("PayrollAccountingService.java"))
        .doesNotContain("SettlementSupportService");
    assertThat(readService("CreditDebitNoteService.java"))
        .doesNotContain("SettlementSupportService")
        .doesNotContain("JournalPostingService");
    assertThat(readService("InventoryAccountingService.java"))
        .doesNotContain("SettlementSupportService")
        .doesNotContain("JournalPostingService");
  }

  @Test
  void journalPostingFacadeAndCollaboratorsStayBelowHotspotThresholds() {
    assertThat(lineCount("JournalPostingService.java")).isLessThan(500);
    assertThat(lineCount("JournalEntryMutationService.java")).isLessThan(500);
    assertThat(lineCount("JournalPartnerContextService.java")).isLessThan(500);
    assertThat(lineCount("JournalDuplicateGuardService.java")).isLessThan(500);
    assertThat(lineCount("JournalLinePostingService.java")).isLessThan(500);
    assertThat(lineCount("SettlementAllocationResolutionService.java")).isLessThan(500);
    assertThat(lineCount("SettlementTotalsValidationService.java")).isLessThan(500);
    assertThat(lineCount("SettlementJournalLineDraftService.java")).isLessThan(500);
  }

  @Test
  void settlementWritePathRejectsOversizedReplacementSeams() {
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
            "Settlement write path must not inject a renamed settlement-resolution replacement seam"
                + " above the 500-line mission cap")
        .isEmpty();
  }

  private Set<Class<?>> fieldTypes(Class<?> type) {
    return Arrays.stream(type.getDeclaredFields())
        .map(field -> field.getType())
        .collect(Collectors.toSet());
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

  private List<String> legacySettlementSupportFiles() {
    Path serviceDir = serviceFile("placeholder").getParent();
    try (var stream = Files.list(serviceDir)) {
      return stream
          .map(path -> path.getFileName().toString())
          .filter(name -> name.matches("Settlement.*Support.*\\.java"))
          .filter(name -> !name.equals("SettlementAuditMemoDecoder.java"))
          .toList();
    } catch (IOException ex) {
      throw new AssertionError(ex);
    }
  }
}
