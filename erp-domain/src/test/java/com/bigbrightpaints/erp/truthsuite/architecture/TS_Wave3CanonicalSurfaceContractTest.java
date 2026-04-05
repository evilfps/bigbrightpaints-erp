package com.bigbrightpaints.erp.truthsuite.architecture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.bigbrightpaints.erp.truthsuite.support.TruthSuiteFileAssert;

@Tag("critical")
@Tag("architecture")
class TS_Wave3CanonicalSurfaceContractTest {

  private static final String WORKFLOW_ENGINE =
      "src/main/java/com/bigbrightpaints/erp/modules/inventory/service/"
          + "FinishedGoodsWorkflowEngineService.java";

  @Test
  void accountingCoreScaffoldsStayHiddenBehindPublicServiceEntryPoints() throws Exception {
    assertHidden("com.bigbrightpaints.erp.modules.accounting.service.AccountingFacadeCore");
    assertHidden("com.bigbrightpaints.erp.modules.accounting.service.AccountingCoreEngineCore");
    assertRemoved("com.bigbrightpaints.erp.modules.accounting.service.AccountingCoreEngine");
    assertRemoved("com.bigbrightpaints.erp.modules.accounting.service.AccountingCoreLogic");
    assertRemoved("com.bigbrightpaints.erp.modules.accounting.service.AccountingCoreService");
  }

  @Test
  void finishedGoodsWorkflowSeamStaysInternalAndUsesInjectedInventoryServices() throws Exception {
    assertHidden(
        "com.bigbrightpaints.erp.modules.inventory.service.FinishedGoodsWorkflowEngineService");

    String source = TruthSuiteFileAssert.read(WORKFLOW_ENGINE);
    assertThat(source).doesNotContain("new InventoryMovementRecorder(");
    assertThat(source).doesNotContain("new InventoryValuationService(");
    assertThat(source).doesNotContain("new PackagingSlipService(");
    assertThat(source).doesNotContain("new FinishedGoodsReservationEngine(");
    assertThat(source).doesNotContain("new FinishedGoodsDispatchEngine(");

    TruthSuiteFileAssert.assertContains(
        WORKFLOW_ENGINE,
        "InventoryValuationService inventoryValuationService",
        "FinishedGoodsReservationEngine reservationEngine",
        "FinishedGoodsDispatchEngine dispatchEngine",
        "PackagingSlipService packagingSlipService");
    assertThat(source).doesNotContain("BatchNumberService batchNumberService");
  }

  @Test
  void ambiguousReportAndAuthSurfaceNamesDoNotComeBack() {
    assertThatThrownBy(
            () ->
                Class.forName(
                    "com.bigbrightpaints.erp.modules.reports.service.InventoryValuationService"))
        .isInstanceOf(ClassNotFoundException.class);
    assertThatCode(
            () ->
                Class.forName(
                    "com.bigbrightpaints.erp.modules.reports.service."
                        + "InventoryValuationQueryService"))
        .doesNotThrowAnyException();

    assertThatThrownBy(
            () -> Class.forName("com.bigbrightpaints.erp.modules.auth.dto.ResetPasswordRequest"))
        .isInstanceOf(ClassNotFoundException.class);
  }

  private void assertHidden(String fqcn) throws Exception {
    assertThat(Modifier.isPublic(Class.forName(fqcn).getModifiers()))
        .as("%s should stay package-private", fqcn)
        .isFalse();
  }

  private void assertRemoved(String fqcn) {
    Path sourcePath = Path.of("src/main/java", fqcn.replace('.', '/') + ".java");
    assertThat(Files.exists(sourcePath)).as("%s source should be removed", fqcn).isFalse();
  }
}
