package com.bigbrightpaints.erp.truthsuite.o2c;

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.file.Files;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.bigbrightpaints.erp.truthsuite.support.TruthSuiteFileAssert;

@Tag("critical")
@Tag("reconciliation")
class TS_O2COrchestratorDispatchRemovalRegressionTest {

  private static final String INTEGRATION_COORDINATOR =
      "src/main/java/com/bigbrightpaints/erp/orchestrator/service/IntegrationCoordinator.java";
  private static final String COMMAND_DISPATCHER =
      "src/main/java/com/bigbrightpaints/erp/orchestrator/service/CommandDispatcher.java";
  private static final String DISPATCH_REQUEST =
      "src/main/java/com/bigbrightpaints/erp/orchestrator/dto/DispatchRequest.java";

  @Test
  void integrationCoordinatorNoLongerContainsLegacyDispatchJournalMethodsOrHelpers() {
    String source = TruthSuiteFileAssert.read(INTEGRATION_COORDINATOR);

    assertFalse(
        source.contains("postDispatchJournal("),
        "IntegrationCoordinator must not expose postDispatchJournal");
    assertFalse(
        source.contains("createAccountingEntry("),
        "IntegrationCoordinator must not expose createAccountingEntry");
    assertFalse(
        source.contains("postJournal("), "Legacy orchestrator journal helper must be removed");
    assertFalse(
        source.contains("DISPATCH-"),
        "IntegrationCoordinator must not build DISPATCH-prefixed journal references");
    assertFalse(
        source.contains("erp.dispatch.debit-account-id"),
        "Legacy dispatch debit mapping must be removed");
    assertFalse(
        source.contains("erp.dispatch.credit-account-id"),
        "Legacy dispatch credit mapping must be removed");
  }

  @Test
  void commandDispatcherNoLongerExposesLegacyDispatchShortcut() {
    String source = TruthSuiteFileAssert.read(COMMAND_DISPATCHER);

    assertFalse(
        source.contains("dispatchBatch("),
        "CommandDispatcher must not keep a legacy orchestrator dispatch shortcut");
    assertFalse(
        source.contains("DispatchRequest"),
        "CommandDispatcher must not reference the retired dispatch request payload");
    assertFalse(
        source.contains("integrationCoordinator.updateProductionStatus("),
        "Legacy dispatch shortcut must not advance production status independently");
    assertFalse(
        source.contains("integrationCoordinator.releaseInventory("),
        "Legacy dispatch shortcut must not release inventory independently");
    assertFalse(
        source.contains("integrationCoordinator.postDispatchJournal("),
        "Legacy dispatch shortcut must not post orchestrator dispatch journals");
    assertFalse(
        source.contains("workflowService.startWorkflow(\"dispatch\")"),
        "Legacy dispatch shortcut must not start a dispatch workflow");
  }

  @Test
  void staleOrchestratorDispatchArtifactsAreRemovedFromSourceTree() {
    assertFalse(
        Files.exists(TruthSuiteFileAssert.resolve(DISPATCH_REQUEST)),
        "Legacy DispatchRequest payload should be deleted");
    assertFalse(
        Files.exists(
            TruthSuiteFileAssert.resolve(
                "src/main/java/com/bigbrightpaints/erp/modules/sales/service/SalesJournalService.java")),
        "Legacy SalesJournalService should be removed");
    assertFalse(
        Files.exists(
            TruthSuiteFileAssert.resolve(
                "src/main/java/com/bigbrightpaints/erp/orchestrator/config/DispatchMappingHealthIndicator.java")),
        "DispatchMappingHealthIndicator should be removed with orchestrator dispatch journals");
    assertFalse(
        Files.exists(
            TruthSuiteFileAssert.resolve(
                "src/test/java/com/bigbrightpaints/erp/truthsuite/o2c/TS_O2COrchestratorDispatchCharacterizationTest.java")),
        "Temporary orchestrator characterization test must be deleted once the path is removed");
  }
}
