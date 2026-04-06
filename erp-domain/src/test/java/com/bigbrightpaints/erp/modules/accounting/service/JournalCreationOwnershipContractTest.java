package com.bigbrightpaints.erp.modules.accounting.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.util.Arrays;
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
  void accountingCoreSupportKeepsLegacyJournalCreationInternalsNonPublic() throws Exception {
    Class<?> accountingCoreSupport = AccountingCoreSupport.class;

    assertThat(Modifier.isPublic(accountingCoreSupport.getModifiers())).isFalse();
    assertThat(
            Modifier.isProtected(
                accountingCoreSupport
                    .getDeclaredMethod(
                        "createStandardJournal",
                        com.bigbrightpaints.erp.modules.accounting.dto.JournalCreationRequest.class)
                    .getModifiers()))
        .isTrue();
    assertThat(
            Modifier.isProtected(
                accountingCoreSupport
                    .getDeclaredMethod(
                        "createJournalEntry",
                        com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryRequest.class)
                    .getModifiers()))
        .isTrue();
  }

  @Test
  void journalEntryServiceUsesFocusedCollaboratorsInsteadOfInheritance() {
    assertThat(JournalEntryService.class.getSuperclass()).isEqualTo(Object.class);
    assertThat(fieldTypes(JournalEntryService.class))
        .contains(
            JournalPostingService.class,
            JournalQueryService.class,
            JournalReversalService.class,
            PeriodValidationService.class);
  }

  @Test
  void accountingServiceUsesComposedCollaboratorsInsteadOfInheritance() {
    assertThat(AccountingService.class.getSuperclass()).isEqualTo(Object.class);
    assertThat(fieldTypes(AccountingService.class))
        .contains(
            JournalEntryService.class,
            DealerReceiptService.class,
            SettlementService.class,
            CreditDebitNoteService.class,
            InventoryAccountingService.class);
  }

  private Set<Class<?>> fieldTypes(Class<?> type) {
    return Arrays.stream(type.getDeclaredFields())
        .map(field -> field.getType())
        .collect(Collectors.toSet());
  }
}
