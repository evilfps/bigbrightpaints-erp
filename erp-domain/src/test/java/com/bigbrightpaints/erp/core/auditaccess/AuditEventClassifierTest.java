package com.bigbrightpaints.erp.core.auditaccess;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.bigbrightpaints.erp.core.audit.AuditEvent;
import com.bigbrightpaints.erp.core.audit.AuditLog;
import com.bigbrightpaints.erp.core.audittrail.AuditActionEvent;

@Tag("critical")
class AuditEventClassifierTest {

  private final AuditEventClassifier classifier = new AuditEventClassifier();

  @ParameterizedTest
  @MethodSource("auditLogCategoryCases")
  void categoryFor_mapsAuditLogEvents(AuditEvent event, String expectedCategory) {
    AuditLog auditLog = new AuditLog();
    auditLog.setEventType(event);

    assertThat(classifier.categoryFor(auditLog)).isEqualTo(expectedCategory);
  }

  private static Stream<Arguments> auditLogCategoryCases() {
    return Stream.of(
        Arguments.of(null, "GENERAL"),
        Arguments.of(AuditEvent.LOGIN_SUCCESS, "AUTH"),
        Arguments.of(AuditEvent.ACCESS_GRANTED, "SECURITY"),
        Arguments.of(AuditEvent.CONFIGURATION_CHANGED, "ADMIN"),
        Arguments.of(AuditEvent.SENSITIVE_DATA_ACCESSED, "DATA"),
        Arguments.of(AuditEvent.DATA_RETENTION_ACTION, "COMPLIANCE"),
        Arguments.of(AuditEvent.SYSTEM_SHUTDOWN, "SYSTEM"),
        Arguments.of(AuditEvent.REFERENCE_GENERATED, "BUSINESS"));
  }

  @ParameterizedTest
  @MethodSource("pathModuleCases")
  void moduleFor_recognizesCanonicalPathPrefixes(String path, String expectedModule) {
    AuditLog auditLog = new AuditLog();
    auditLog.setRequestPath(path);

    assertThat(classifier.moduleFor(auditLog)).isEqualTo(expectedModule);
  }

  private static Stream<Arguments> pathModuleCases() {
    return Stream.of(
        Arguments.of("/api/v1/superadmin/users", "SUPERADMIN"),
        Arguments.of("/api/v1/admin/settings", "ADMIN"),
        Arguments.of("/api/v1/accounting/journal-entries", "ACCOUNTING"),
        Arguments.of("/api/v1/auth/login", "AUTH"),
        Arguments.of("/api/v1/changelog/releases", "CHANGELOG"),
        Arguments.of("/api/v1/companies/17", "COMPANIES"));
  }

  @Test
  void moduleFor_marksAccountingEventsWithoutAccountingPathsAsAccounting() {
    AuditLog auditLog = new AuditLog();
    auditLog.setEventType(AuditEvent.PAYROLL_POSTED);
    auditLog.setRequestPath("/api/v1/hr/payroll/17/post");

    assertThat(classifier.moduleFor(auditLog)).isEqualTo("ACCOUNTING");
  }

  @Test
  void moduleFor_usesMetadataResourceTypeWhenTheAuditLogColumnIsUnset() {
    AuditLog auditLog = new AuditLog();
    HashMap<String, String> metadata = new HashMap<>();
    metadata.put("resourceType", "accounting_supplier_statement");
    auditLog.setMetadata(metadata);

    assertThat(classifier.moduleFor(auditLog)).isEqualTo("ACCOUNTING_SUPPLIER_STATEMENT");
  }

  @Test
  void moduleFor_marksAccountingTrailFailuresAsAccounting() {
    AuditLog auditLog = new AuditLog();
    auditLog.setEventType(AuditEvent.INTEGRATION_FAILURE);
    auditLog.setMetadata(Map.of("eventTrailOperation", "JOURNAL_ENTRY_POSTED"));

    assertThat(classifier.moduleFor(auditLog)).isEqualTo("ACCOUNTING");
  }

  @Test
  void moduleFor_keepsReferenceGenerationInBusinessModule() {
    AuditLog auditLog = new AuditLog();
    auditLog.setEventType(AuditEvent.REFERENCE_GENERATED);

    assertThat(classifier.moduleFor(auditLog)).isEqualTo("BUSINESS");
  }

  @Test
  void moduleFor_prefersExplicitResourceTypeBeforeCategoryFallback() {
    AuditLog auditLog = new AuditLog();
    auditLog.setEventType(AuditEvent.ACCESS_GRANTED);
    auditLog.setResourceType("inventory_adjustment");

    assertThat(classifier.moduleFor(auditLog)).isEqualTo("INVENTORY_ADJUSTMENT");
  }

  @Test
  void moduleFor_fallsBackToDerivedCategoryWhenNoExplicitModuleHintExists() {
    AuditLog auditLog = new AuditLog();
    auditLog.setEventType(AuditEvent.ACCESS_GRANTED);

    assertThat(classifier.moduleFor(auditLog)).isEqualTo("SECURITY");
  }

  @Test
  void moduleFor_actionEvents_normalizesOrDefaultsModule() {
    AuditActionEvent accountingEvent = new AuditActionEvent();
    accountingEvent.setModule(" accounting ");
    AuditActionEvent blankModuleEvent = new AuditActionEvent();
    blankModuleEvent.setModule(" ");

    assertThat(classifier.categoryFor(accountingEvent)).isEqualTo("ACCOUNTING");
    assertThat(classifier.moduleFor(accountingEvent)).isEqualTo("ACCOUNTING");
    assertThat(classifier.moduleFor(blankModuleEvent)).isEqualTo("BUSINESS");
  }

  @Test
  void isAccountingEvent_handlesNullAndTrailFailureFallbacks() {
    AuditLog nonAccountingFailure = new AuditLog();
    nonAccountingFailure.setEventType(AuditEvent.INTEGRATION_FAILURE);
    nonAccountingFailure.setMetadata(Map.of());
    AuditLog accountingFailure = new AuditLog();
    accountingFailure.setEventType(AuditEvent.INTEGRATION_FAILURE);
    accountingFailure.setMetadata(Map.of("eventTrailOperation", "PAYROLL_POSTED"));

    assertThat(classifier.isAccountingEvent((AuditEvent) null)).isFalse();
    assertThat(classifier.isAccountingEvent((AuditLog) null)).isFalse();
    assertThat(classifier.isAccountingEvent(nonAccountingFailure)).isFalse();
    assertThat(classifier.isAccountingEvent(accountingFailure)).isTrue();
  }

  @Test
  void accountingEventTypes_exposesCanonicalAccountingActions() {
    assertThat(classifier.accountingEventTypes())
        .contains(AuditEvent.PAYROLL_POSTED, AuditEvent.JOURNAL_ENTRY_POSTED)
        .doesNotContain(AuditEvent.REFERENCE_GENERATED);
  }

  @Test
  void subjectResolvers_trimValuesAndIgnoreInvalidMetadata() {
    assertThat(classifier.subjectUserId(Map.of("subjectUserId", "bad-id"))).isNull();
    assertThat(classifier.subjectUserId(Map.of("subjectUserId", " "))).isNull();

    Map<String, String> metadata = new HashMap<>();
    metadata.put("targetUserId", " 42 ");
    metadata.put("subjectEmail", " owner@example.com ");

    assertThat(classifier.subjectUserId(metadata)).isEqualTo(42L);
    assertThat(classifier.subjectIdentifier(metadata)).isEqualTo("owner@example.com");
  }

  @Test
  void subjectIdentifier_returnsNullWhenMetadataIsMissingOrBlank() {
    assertThat(classifier.subjectIdentifier(null)).isNull();
    assertThat(classifier.subjectIdentifier(Map.of("subjectEmail", " "))).isNull();
  }
}
