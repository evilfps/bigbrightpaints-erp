package com.bigbrightpaints.erp.core.auditaccess;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.time.Instant;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.bigbrightpaints.erp.core.auditaccess.dto.AuditFeedItemDto;
import com.bigbrightpaints.erp.core.audittrail.AuditActionEvent;
import com.bigbrightpaints.erp.core.audittrail.AuditActionEventRepository;

@Tag("critical")
class BusinessAuditReadAdapterTest {

  @Test
  void toDto_handlesNullMetadataAndNullStatus() {
    BusinessAuditReadAdapter adapter =
        new BusinessAuditReadAdapter(
            mock(AuditActionEventRepository.class),
            new AuditEventClassifier(),
            mock(AuditVisibilityPolicy.class));
    AuditActionEvent event = new AuditActionEvent();
    ReflectionTestUtils.setField(event, "id", 17L);
    event.setCompanyId(9L);
    event.setOccurredAt(Instant.parse("2035-04-01T10:15:30Z"));
    event.setModule(" accounting ");
    event.setAction("JOURNAL_ENTRY_POSTED");
    event.setStatus(null);
    event.setActorIdentifier("ops@example.com");
    event.setMetadata(null);
    event.setTraceId("trace-17");

    AuditFeedItemDto dto = ReflectionTestUtils.invokeMethod(adapter, "toDto", event, "TENANT-A");

    assertThat(dto.sourceId()).isEqualTo(17L);
    assertThat(dto.companyCode()).isEqualTo("TENANT-A");
    assertThat(dto.category()).isEqualTo("ACCOUNTING");
    assertThat(dto.module()).isEqualTo("ACCOUNTING");
    assertThat(dto.status()).isNull();
    assertThat(dto.metadata()).isEmpty();
  }
}
