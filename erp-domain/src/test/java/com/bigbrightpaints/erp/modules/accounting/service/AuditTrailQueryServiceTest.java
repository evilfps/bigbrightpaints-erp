package com.bigbrightpaints.erp.modules.accounting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import com.bigbrightpaints.erp.core.audittrail.AuditActionEvent;
import com.bigbrightpaints.erp.core.audittrail.AuditActionEventRepository;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.shared.dto.PageResponse;

@ExtendWith(MockitoExtension.class)
class AuditTrailQueryServiceTest {

  @Mock private CompanyContextService companyContextService;
  @Mock private AuditActionEventRepository auditActionEventRepository;

  private AuditTrailQueryService service;

  @BeforeEach
  void setUp() {
    service = new AuditTrailQueryService(companyContextService, auditActionEventRepository);
  }

  @Test
  void queryAuditTrail_filtersByUserActionAndEntityType() {
    Company company = new Company();
    setField(company, "id", 11L);
    company.setCode("BBP");
    when(companyContextService.requireCurrentCompany()).thenReturn(company);

    AuditActionEvent matching =
        event(
            1L,
            "ACCOUNTING",
            "MANUAL_JOURNAL_CREATED",
            "JOURNAL_ENTRY",
            "ops@example.com",
            Instant.parse("2026-02-27T10:15:30Z"),
            mapOf(
                "beforeState",
                "{}",
                "afterState",
                "{\"status\":\"POSTED\"}",
                "sensitiveOperation",
                "true"));

    AuditActionEvent wrongUser =
        event(
            2L,
            "ACCOUNTING",
            "MANUAL_JOURNAL_CREATED",
            "JOURNAL_ENTRY",
            "other@example.com",
            Instant.parse("2026-02-27T10:16:30Z"),
            mapOf("beforeState", "{}", "afterState", "{}", "sensitiveOperation", "true"));

    AuditActionEvent wrongAction =
        event(
            3L,
            "ACCOUNTING",
            "JOURNAL_REVERSED",
            "JOURNAL_ENTRY",
            "ops@example.com",
            Instant.parse("2026-02-27T10:17:30Z"),
            mapOf("beforeState", "{}", "afterState", "{}", "sensitiveOperation", "false"));

    AuditActionEvent wrongModule =
        event(
            4L,
            "SALES",
            "MANUAL_JOURNAL_CREATED",
            "JOURNAL_ENTRY",
            "ops@example.com",
            Instant.parse("2026-02-27T10:18:30Z"),
            mapOf("beforeState", "{}", "afterState", "{}", "sensitiveOperation", "false"));

    when(auditActionEventRepository.findAll(
            any(org.springframework.data.jpa.domain.Specification.class), any(Pageable.class)))
        .thenReturn(new PageImpl<>(List.of(matching)));

    PageResponse<com.bigbrightpaints.erp.modules.accounting.dto.AccountingAuditTrailEntryDto> page =
        service.queryAuditTrail(
            LocalDate.of(2026, 2, 1),
            LocalDate.of(2026, 2, 28),
            "ops@example.com",
            "MANUAL_JOURNAL_CREATED",
            "JOURNAL_ENTRY",
            0,
            20);

    assertThat(page.content()).hasSize(1);
    assertThat(page.content().getFirst().actorIdentifier()).isEqualTo("ops@example.com");
    assertThat(page.content().getFirst().actionType()).isEqualTo("MANUAL_JOURNAL_CREATED");
    assertThat(page.content().getFirst().entityType()).isEqualTo("JOURNAL_ENTRY");
    assertThat(page.content().getFirst().beforeState()).isEqualTo("{}");
    assertThat(page.content().getFirst().afterState()).contains("POSTED");
    assertThat(page.content().getFirst().sensitiveOperation()).isTrue();

    ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
    verify(auditActionEventRepository)
        .findAll(any(org.springframework.data.jpa.domain.Specification.class), pageableCaptor.capture());
    Pageable pageable = pageableCaptor.getValue();
    assertThat(pageable.getPageNumber()).isEqualTo(0);
    assertThat(pageable.getPageSize()).isEqualTo(20);
    assertThat(pageable.getSort().getOrderFor("occurredAt")).isNotNull();
    assertThat(pageable.getSort().getOrderFor("occurredAt").getDirection())
        .isEqualTo(Sort.Direction.DESC);
    assertThat(pageable.getSort().getOrderFor("id")).isNotNull();
    assertThat(pageable.getSort().getOrderFor("id").getDirection()).isEqualTo(Sort.Direction.DESC);
    verify(auditActionEventRepository, never())
        .findAll(
            any(org.springframework.data.jpa.domain.Specification.class),
            any(org.springframework.data.domain.Sort.class));
  }

  @Test
  void queryAuditTrail_appliesPaginationAfterFiltering() {
    Company company = new Company();
    setField(company, "id", 12L);
    company.setCode("BBP");
    when(companyContextService.requireCurrentCompany()).thenReturn(company);

    AuditActionEvent first =
        event(
            11L,
            "ACCOUNTING",
            "JOURNAL_REVERSED",
            "JOURNAL_ENTRY",
            "ops",
            Instant.parse("2026-02-27T10:15:30Z"),
            Map.of());
    AuditActionEvent second =
        event(
            12L,
            "ACCOUNTING",
            "JOURNAL_REVERSED",
            "JOURNAL_ENTRY",
            "ops",
            Instant.parse("2026-02-27T10:16:30Z"),
            Map.of());
    AuditActionEvent third =
        event(
            13L,
            "ACCOUNTING",
            "JOURNAL_REVERSED",
            "JOURNAL_ENTRY",
            "ops",
            Instant.parse("2026-02-27T10:17:30Z"),
            Map.of());

    when(auditActionEventRepository.findAll(
            any(org.springframework.data.jpa.domain.Specification.class), any(Pageable.class)))
        .thenReturn(new PageImpl<>(List.of(third), Pageable.ofSize(2).withPage(1), 3));

    PageResponse<com.bigbrightpaints.erp.modules.accounting.dto.AccountingAuditTrailEntryDto> page =
        service.queryAuditTrail(null, null, null, "JOURNAL_REVERSED", "JOURNAL_ENTRY", 1, 2);

    assertThat(page.totalElements()).isEqualTo(3);
    assertThat(page.totalPages()).isEqualTo(2);
    assertThat(page.content()).hasSize(1);
    assertThat(page.content().getFirst().id()).isEqualTo(13L);
  }

  private AuditActionEvent event(
      Long id,
      String module,
      String action,
      String entityType,
      String actor,
      Instant occurredAt,
      Map<String, String> metadata) {
    AuditActionEvent event = new AuditActionEvent();
    setField(event, "id", id);
    event.setCompanyId(11L);
    event.setModule(module);
    event.setAction(action);
    event.setEntityType(entityType);
    event.setEntityId("entity-" + id);
    event.setReferenceNumber("REF-" + id);
    event.setActorIdentifier(actor);
    event.setOccurredAt(occurredAt);
    event.setMetadata(metadata);
    return event;
  }

  private static Map<String, String> mapOf(String... entries) {
    Map<String, String> metadata = new HashMap<>();
    for (int i = 0; i + 1 < entries.length; i += 2) {
      metadata.put(entries[i], entries[i + 1]);
    }
    return metadata;
  }

  private static void setField(Object target, String fieldName, Object value) {
    try {
      Field field = target.getClass().getDeclaredField(fieldName);
      field.setAccessible(true);
      field.set(target, value);
    } catch (ReflectiveOperationException ex) {
      throw new RuntimeException(ex);
    }
  }
}
