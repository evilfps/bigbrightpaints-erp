package com.bigbrightpaints.erp.core.auditaccess;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

import com.bigbrightpaints.erp.core.audit.AuditLog;
import com.bigbrightpaints.erp.core.audit.AuditLogRepository;

@Tag("critical")
class AuditLogReadAdapterTest {

  @Test
  void entityFieldResolvers_preferMetadataResourceKeys() {
    AuditLogReadAdapter adapter =
        new AuditLogReadAdapter(
            mock(AuditLogRepository.class),
            new AuditEventClassifier(),
            mock(AuditVisibilityPolicy.class));
    AuditLog auditLog = new AuditLog();
    auditLog.setMetadata(
        Map.of(
            "resourceType", "JOURNAL_ENTRY",
            "resourceId", "17",
            "referenceNumber", "OB-2026-0001"));

    assertThat(adapter.entityTypeFor(auditLog)).isEqualTo("JOURNAL_ENTRY");
    assertThat(adapter.entityIdFor(auditLog)).isEqualTo("17");
    assertThat(adapter.referenceNumberFor(auditLog)).isEqualTo("OB-2026-0001");
  }

  @Test
  void referenceNumberFallsBackToEntityIdWhenExplicitReferenceIsMissing() {
    AuditLogReadAdapter adapter =
        new AuditLogReadAdapter(
            mock(AuditLogRepository.class),
            new AuditEventClassifier(),
            mock(AuditVisibilityPolicy.class));
    AuditLog auditLog = new AuditLog();
    auditLog.setMetadata(Map.of("resourceId", "17"));

    assertThat(adapter.entityIdFor(auditLog)).isEqualTo("17");
    assertThat(adapter.referenceNumberFor(auditLog)).isEqualTo("17");
  }

  @Test
  void referenceResolvers_supportLegacyAuditMetadataKeys() {
    AuditLogReadAdapter adapter =
        new AuditLogReadAdapter(
            mock(AuditLogRepository.class),
            new AuditEventClassifier(),
            mock(AuditVisibilityPolicy.class));
    AuditLog journalAuditLog = new AuditLog();
    journalAuditLog.setMetadata(Map.of("journalEntryId", "19", "journalReference", "JE-19"));
    AuditLog orderAuditLog = new AuditLog();
    orderAuditLog.setMetadata(Map.of("orderNumber", "SO-2026-0001"));
    AuditLog referenceAuditLog = new AuditLog();
    referenceAuditLog.setMetadata(Map.of("reference", "REF-2026-0001"));

    assertThat(adapter.entityIdFor(journalAuditLog)).isEqualTo("19");
    assertThat(adapter.referenceNumberFor(journalAuditLog)).isEqualTo("JE-19");
    assertThat(adapter.entityIdFor(orderAuditLog)).isEqualTo("SO-2026-0001");
    assertThat(adapter.referenceNumberFor(orderAuditLog)).isEqualTo("SO-2026-0001");
    assertThat(adapter.entityIdFor(referenceAuditLog)).isEqualTo("REF-2026-0001");
    assertThat(adapter.referenceNumberFor(referenceAuditLog)).isEqualTo("REF-2026-0001");
  }

  @Test
  void entityResolvers_handleNullMetadataWithoutThrowing() {
    AuditLogReadAdapter adapter =
        new AuditLogReadAdapter(
            mock(AuditLogRepository.class),
            new AuditEventClassifier(),
            mock(AuditVisibilityPolicy.class));
    AuditLog auditLog = new AuditLog();
    auditLog.setMetadata(null);
    auditLog.setResourceType(" JOURNAL_ENTRY ");
    auditLog.setResourceId(" 17 ");

    assertThat(adapter.entityTypeFor(auditLog)).isEqualTo("JOURNAL_ENTRY");
    assertThat(adapter.entityIdFor(auditLog)).isEqualTo("17");
    assertThat(adapter.referenceNumberFor(auditLog)).isEqualTo("17");
  }

  @Test
  void queryPlatformFeed_resolvesMissingCompanyCodesInOneBatchPerPage() {
    AuditLogRepository auditLogRepository = mock(AuditLogRepository.class);
    AuditVisibilityPolicy auditVisibilityPolicy = mock(AuditVisibilityPolicy.class);
    AuditLogReadAdapter adapter =
        new AuditLogReadAdapter(
            auditLogRepository, new AuditEventClassifier(), auditVisibilityPolicy);
    Specification<com.bigbrightpaints.erp.core.audit.AuditLog> allowAll =
        (root, query, cb) -> cb.conjunction();
    when(auditVisibilityPolicy.platformVisibility()).thenReturn(allowAll);
    when(auditVisibilityPolicy.resolveCompanyCodes(java.util.Set.of(7L)))
        .thenReturn(Map.of(7L, "TENANT-A"));

    AuditLog fallbackCompanyLog = new AuditLog();
    fallbackCompanyLog.setCompanyId(7L);
    fallbackCompanyLog.setRequestPath("/api/v1/companies/7");
    fallbackCompanyLog.setMetadata(Map.of());

    AuditLog metadataCompanyLog = new AuditLog();
    metadataCompanyLog.setCompanyId(9L);
    metadataCompanyLog.setRequestPath("/api/v1/companies/9");
    metadataCompanyLog.setMetadata(Map.of("targetCompanyCode", "TENANT-B"));

    when(auditLogRepository.findAll(
            org.mockito.ArgumentMatchers.<Specification<AuditLog>>any(), any(Pageable.class)))
        .thenReturn(new PageImpl<>(java.util.List.of(fallbackCompanyLog, metadataCompanyLog)));

    AuditFeedSlice slice =
        adapter.queryPlatformFeed(
            new AuditFeedFilter(null, null, null, null, null, null, null, null, 0, 50));

    assertThat(slice.items())
        .extracting(item -> item.companyCode())
        .containsExactly("TENANT-A", "TENANT-B");
    verify(auditVisibilityPolicy).resolveCompanyCodes(java.util.Set.of(7L));
  }

  @Test
  void queryPlatformFeed_preservesNullMetadataValuesWithoutThrowing() {
    AuditLogRepository auditLogRepository = mock(AuditLogRepository.class);
    AuditVisibilityPolicy auditVisibilityPolicy = mock(AuditVisibilityPolicy.class);
    AuditLogReadAdapter adapter =
        new AuditLogReadAdapter(
            auditLogRepository, new AuditEventClassifier(), auditVisibilityPolicy);
    Specification<com.bigbrightpaints.erp.core.audit.AuditLog> allowAll =
        (root, query, cb) -> cb.conjunction();
    when(auditVisibilityPolicy.platformVisibility()).thenReturn(allowAll);

    AuditLog auditLog = new AuditLog();
    auditLog.setRequestPath("/api/v1/companies/9");
    HashMap<String, String> metadata = new HashMap<>();
    metadata.put("journalEntryId", "19");
    metadata.put("journalReference", null);
    auditLog.setMetadata(metadata);

    when(auditLogRepository.findAll(
            org.mockito.ArgumentMatchers.<Specification<AuditLog>>any(), any(Pageable.class)))
        .thenReturn(new PageImpl<>(java.util.List.of(auditLog)));

    AuditFeedSlice slice =
        adapter.queryPlatformFeed(
            new AuditFeedFilter(null, null, null, null, null, null, null, null, 0, 50));

    assertThat(slice.items())
        .singleElement()
        .satisfies(
            item -> {
              assertThat(item.entityId()).isEqualTo("19");
              assertThat(item.metadata()).containsKey("journalReference");
              assertThat(item.metadata().get("journalReference")).isNull();
            });
  }
}
