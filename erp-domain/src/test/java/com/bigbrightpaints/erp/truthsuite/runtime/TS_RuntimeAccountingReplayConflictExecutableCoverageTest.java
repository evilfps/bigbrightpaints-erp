package com.bigbrightpaints.erp.truthsuite.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.bigbrightpaints.erp.modules.accounting.service.AccountingFacade;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingService;
import com.bigbrightpaints.erp.modules.accounting.service.CreditDebitNoteService;
import com.bigbrightpaints.erp.modules.accounting.service.DealerReceiptService;
import com.bigbrightpaints.erp.modules.accounting.service.InventoryAccountingService;
import com.bigbrightpaints.erp.modules.accounting.service.JournalEntryService;
import com.bigbrightpaints.erp.modules.accounting.service.SettlementService;

@Tag("critical")
class TS_RuntimeAccountingReplayConflictExecutableCoverageTest {

  private static final Path ACCOUNTING_SERVICE_SOURCE =
      sourcePath("AccountingService.java");
  private static final Path JOURNAL_ENTRY_SERVICE_SOURCE =
      sourcePath("JournalEntryService.java");
  private static final Path SETTLEMENT_SERVICE_SOURCE =
      sourcePath("SettlementService.java");
  private static final Path SETTLEMENT_SUPPORT_SOURCE =
      sourcePath(settlementSupportFileName());

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
  void settlement_paths_route_through_focused_collaborators() {
    Set<String> settlementFieldTypes =
        Arrays.stream(SettlementService.class.getDeclaredFields())
            .map(field -> field.getType().getSimpleName())
            .collect(Collectors.toSet());

    assertThat(SettlementService.class.getSuperclass()).isEqualTo(Object.class);
    assertThat(Files.exists(SETTLEMENT_SUPPORT_SOURCE)).isFalse();
    assertThat(settlementFieldTypes)
        .contains("SupplierPaymentService", "DealerSettlementService", "SupplierSettlementService");
  }

  @Test
  void accountingService_routes_sensitive_flows_through_composed_services() {
    Set<String> fieldTypes =
        Arrays.stream(AccountingService.class.getDeclaredFields())
            .map(field -> field.getType().getSimpleName())
            .collect(Collectors.toSet());

    assertThat(fieldTypes)
        .contains(
            "AccountCatalogService",
            JournalEntryService.class.getSimpleName(),
            DealerReceiptService.class.getSimpleName(),
            SettlementService.class.getSimpleName(),
            CreditDebitNoteService.class.getSimpleName(),
            InventoryAccountingService.class.getSimpleName());
  }

  @Test
  void payroll_specific_accounting_writes_are_owned_by_payroll_accounting_service_via_facade() {
    Set<String> fieldTypes =
        Arrays.stream(AccountingFacade.class.getDeclaredFields())
            .map(field -> field.getType().getSimpleName())
            .collect(Collectors.toSet());

    assertThat(fieldTypes).contains("PayrollAccountingService");
  }

  @Test
  void journal_entry_service_routes_manual_paths_through_focused_collaborators() {
    Set<String> fieldTypes =
        Arrays.stream(JournalEntryService.class.getDeclaredFields())
            .map(field -> field.getType().getSimpleName())
            .collect(Collectors.toSet());

    assertThat(fieldTypes).contains("ManualJournalService", "ClosingEntryReversalService");
  }

  @Test
  void journal_creation_services_do_not_instantiate_journal_entries_directly() throws Exception {
    assertThat(Files.readString(ACCOUNTING_SERVICE_SOURCE)).doesNotContain("new JournalEntry(");
    assertThat(Files.readString(JOURNAL_ENTRY_SERVICE_SOURCE)).doesNotContain("new JournalEntry(");
  }

  @Test
  void accounting_write_paths_do_not_use_support_class_inheritance() throws Exception {
    assertThat(Files.readString(ACCOUNTING_SERVICE_SOURCE))
        .doesNotContain("extends " + accountingSupportName());
    assertThat(Files.readString(JOURNAL_ENTRY_SERVICE_SOURCE))
        .doesNotContain("extends " + accountingSupportName());
    assertThat(Files.readString(SETTLEMENT_SERVICE_SOURCE))
        .doesNotContain("extends " + settlementSupportName());
    assertThat(Files.exists(SETTLEMENT_SUPPORT_SOURCE)).isFalse();
  }

  private static String accountingSupportName() {
    return "Accounting" + "CoreSupport";
  }

  private static String settlementSupportName() {
    return "Settlement" + "CoreSupport";
  }

  private static String settlementSupportFileName() {
    return settlementSupportName() + ".java";
  }

  private static Path sourcePath(String fileName) {
    return Path.of(
        "src",
        "main",
        "java",
        "com",
        "bigbrightpaints",
        "erp",
        "modules",
        "accounting",
        "service",
        fileName);
  }
}
