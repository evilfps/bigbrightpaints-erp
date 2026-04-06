package com.bigbrightpaints.erp.truthsuite.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.bigbrightpaints.erp.modules.accounting.service.AccountingService;
import com.bigbrightpaints.erp.modules.accounting.service.CreditDebitNoteService;
import com.bigbrightpaints.erp.modules.accounting.service.DealerReceiptService;
import com.bigbrightpaints.erp.modules.accounting.service.InventoryAccountingService;
import com.bigbrightpaints.erp.modules.accounting.service.JournalEntryService;
import com.bigbrightpaints.erp.modules.accounting.service.SettlementService;

@Tag("critical")
class TS_RuntimeAccountingReplayConflictExecutableCoverageTest {

  private static final Path ACCOUNTING_SERVICE_SOURCE =
      Path.of(
          "/home/realnigga/Desktop/Mission-control/erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/service/AccountingService.java");
  private static final Path JOURNAL_ENTRY_SERVICE_SOURCE =
      Path.of(
          "/home/realnigga/Desktop/Mission-control/erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/service/JournalEntryService.java");

  @Test
  void accountingService_hides_legacy_replay_helper_surface() {
    Set<String> methodNames =
        Arrays.stream(AccountingService.class.getDeclaredMethods())
            .map(method -> method.getName())
            .collect(Collectors.toSet());

    assertThat(methodNames)
        .doesNotContain(
            "validatePartnerJournalReplay",
            "validateSettlementIdempotencyKey",
            "missingReservedPartnerAllocation",
            "buildDealerReceiptReference",
            "toSettlementAllocationSummaries",
            "logSettlementAuditSuccess");
  }

  @Test
  void accountingCoreSupport_remains_the_canonical_replay_owner() throws Exception {
    Class<?> accountingCoreSupport =
        Class.forName("com.bigbrightpaints.erp.modules.accounting.service.AccountingCoreSupport");
    Set<String> methodNames =
        Arrays.stream(accountingCoreSupport.getDeclaredMethods())
            .map(method -> method.getName())
            .collect(Collectors.toSet());

    assertThat(methodNames)
        .contains(
            "validatePartnerJournalReplay",
            "validateSettlementIdempotencyKey",
            "missingReservedPartnerAllocation",
            "buildDealerReceiptReference",
            "toSettlementAllocationSummaries",
            "logSettlementAuditSuccess");
  }

  @Test
  void accountingService_routes_sensitive_flows_through_composed_services() {
    Set<Class<?>> fieldTypes =
        Arrays.stream(AccountingService.class.getDeclaredFields())
            .map(field -> field.getType())
            .collect(Collectors.toSet());

    assertThat(fieldTypes)
        .contains(
            JournalEntryService.class,
            DealerReceiptService.class,
            SettlementService.class,
            CreditDebitNoteService.class,
            InventoryAccountingService.class);
  }

  @Test
  void journal_creation_services_do_not_instantiate_journal_entries_directly() throws Exception {
    assertThat(Files.readString(ACCOUNTING_SERVICE_SOURCE)).doesNotContain("new JournalEntry(");
    assertThat(Files.readString(JOURNAL_ENTRY_SERVICE_SOURCE)).doesNotContain("new JournalEntry(");
  }
}
