package com.bigbrightpaints.erp.truthsuite.accounting;

import com.bigbrightpaints.erp.truthsuite.support.TruthSuiteFileAssert;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("critical")
@Tag("reconciliation")
class TS_AccountingAuditSafetyCoverageTest {

    private static final String JOURNAL_POSTED_LISTENER =
            "src/main/java/com/bigbrightpaints/erp/core/audit/JournalEntryPostedAuditListener.java";
    private static final String ENTERPRISE_AUDIT_TRAIL_SERVICE =
            "src/main/java/com/bigbrightpaints/erp/core/audittrail/EnterpriseAuditTrailService.java";

    @Test
    void postedJournalAuditMarkerCapturesStableIdentifiersAndReferences() {
        TruthSuiteFileAssert.assertContainsInOrder(
                JOURNAL_POSTED_LISTENER,
                "if (event == null || event.entryId() == null) {",
                "metadata.put(\"journalEntryId\", event.entryId().toString());",
                "if (StringUtils.hasText(event.referenceNumber())) {",
                "metadata.put(\"journalReference\", event.referenceNumber());",
                "if (event.correlationId() != null) {",
                "metadata.put(\"correlationId\", event.correlationId().toString());",
                "auditService.logEvent(AuditEvent.JOURNAL_ENTRY_POSTED, AuditStatus.SUCCESS, metadata);");
    }

    @Test
    void postedJournalAuditMarkerFailsOpenWhenCoreAuditWriteFails() {
        TruthSuiteFileAssert.assertContains(
                JOURNAL_POSTED_LISTENER,
                "catch (Exception ex) {",
                "Failed to persist JOURNAL_ENTRY_POSTED audit marker for event {}",
                "log.warn(\"Failed to persist JOURNAL_ENTRY_POSTED audit marker for event {}\", event.entryId(), ex);");
    }

    @Test
    void enterpriseAuditRetryPersistsFirstThenFallsBackToInMemoryQueue() {
        TruthSuiteFileAssert.assertContainsInOrder(
                ENTERPRISE_AUDIT_TRAIL_SERVICE,
                "if (persistBusinessEventRetry(command, actorSnapshot, failedAttemptCount, exception)) {",
                "return;",
                "PendingBusinessEventRetry pending = new PendingBusinessEventRetry(command, actorSnapshot, failedAttemptCount);",
                "if (!offerBusinessEventRetry(pending)) {",
                "Business audit retry queue full (size={}, max={}); dropping event");
    }

    @Test
    void enterpriseAuditIdentityAnonymizationUsesHmacWithDeterministicFallback() {
        TruthSuiteFileAssert.assertContains(
                ENTERPRISE_AUDIT_TRAIL_SERVICE,
                "Mac mac = Mac.getInstance(\"HmacSHA256\");",
                "mac.init(new SecretKeySpec(auditPrivateKey.getBytes(StandardCharsets.UTF_8), \"HmacSHA256\"));",
                "return HexFormat.of().formatHex(digest).substring(0, 24);",
                "return Integer.toHexString(normalized.hashCode());");
    }

    @Test
    void enterpriseAuditMetadataSanitizationEnforcesHardEntryAndLengthCaps() {
        TruthSuiteFileAssert.assertContains(
                ENTERPRISE_AUDIT_TRAIL_SERVICE,
                "private static final int MAX_METADATA_ENTRIES = 40;",
                "private static final int MAX_METADATA_VALUE_LENGTH = 2000;",
                "sanitized.put(trim(entry.getKey(), 128, \"key\"),",
                "trim(entry.getValue(), MAX_METADATA_VALUE_LENGTH, \"\"));",
                "if (count >= MAX_METADATA_ENTRIES) {");
    }
}
