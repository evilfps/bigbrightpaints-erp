package com.bigbrightpaints.erp.core.auditaccess;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.bigbrightpaints.erp.core.audit.AuditEvent;
import com.bigbrightpaints.erp.core.audit.AuditLog;

class AuditEventClassifierTest {

  @Test
  void moduleFor_marksAccountingEventsWithoutAccountingPathsAsAccounting() {
    AuditEventClassifier classifier = new AuditEventClassifier();
    AuditLog auditLog = new AuditLog();
    auditLog.setEventType(AuditEvent.PAYROLL_POSTED);
    auditLog.setRequestPath("/api/v1/hr/payroll/17/post");

    assertThat(classifier.moduleFor(auditLog)).isEqualTo("ACCOUNTING");
  }

  @Test
  void moduleFor_usesMetadataResourceTypeWhenTheAuditLogColumnIsUnset() {
    AuditEventClassifier classifier = new AuditEventClassifier();
    AuditLog auditLog = new AuditLog();
    HashMap<String, String> metadata = new HashMap<>();
    metadata.put("resourceType", "accounting_supplier_statement");
    auditLog.setMetadata(metadata);

    assertThat(classifier.moduleFor(auditLog)).isEqualTo("ACCOUNTING_SUPPLIER_STATEMENT");
  }

  @Test
  void moduleFor_marksAccountingTrailFailuresAsAccounting() {
    AuditEventClassifier classifier = new AuditEventClassifier();
    AuditLog auditLog = new AuditLog();
    auditLog.setEventType(AuditEvent.INTEGRATION_FAILURE);
    auditLog.setMetadata(Map.of("eventTrailOperation", "JOURNAL_ENTRY_POSTED"));

    assertThat(classifier.moduleFor(auditLog)).isEqualTo("ACCOUNTING");
  }

  @Test
  void moduleFor_keepsReferenceGenerationInBusinessModule() {
    AuditEventClassifier classifier = new AuditEventClassifier();
    AuditLog auditLog = new AuditLog();
    auditLog.setEventType(AuditEvent.REFERENCE_GENERATED);

    assertThat(classifier.moduleFor(auditLog)).isEqualTo("BUSINESS");
  }
}
