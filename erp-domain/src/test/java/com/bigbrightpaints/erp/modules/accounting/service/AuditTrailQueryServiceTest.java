package com.bigbrightpaints.erp.modules.accounting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
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
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.test.util.ReflectionTestUtils;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;

import com.bigbrightpaints.erp.core.audittrail.AuditActionEvent;
import com.bigbrightpaints.erp.core.audittrail.AuditActionEventRepository;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.shared.dto.PageResponse;

@Tag("critical")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
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

  @Test
  void queryAuditTrail_executesCombinedSpecificationForNumericActorFilters() {
    Company company = new Company();
    setField(company, "id", 12L);
    company.setCode("BBP");
    when(companyContextService.requireCurrentCompany()).thenReturn(company);
    when(auditActionEventRepository.findAll(any(Specification.class), any(Pageable.class)))
        .thenReturn(new PageImpl<>(List.of()));

    service.queryAuditTrail(
        LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 28), "42", "POSTED", "JOURNAL_ENTRY", 0, 20);

    ArgumentCaptor<Specification<AuditActionEvent>> specCaptor = ArgumentCaptor.forClass(Specification.class);
    verify(auditActionEventRepository).findAll(specCaptor.capture(), any(Pageable.class));

    CriteriaHarness harness = new CriteriaHarness();
    Predicate predicate = specCaptor.getValue().toPredicate(harness.root, harness.query, harness.builder);

    assertThat(predicate).isNotNull();
    verify(harness.builder).equal(harness.companyIdPath, 12L);
    verify(harness.builder).greaterThanOrEqualTo(harness.occurredAtPath, Instant.parse("2026-02-01T00:00:00Z"));
    verify(harness.builder).lessThan(harness.occurredAtPath, Instant.parse("2026-03-01T00:00:00Z"));
    verify(harness.builder).equal(harness.moduleStringPath, "accounting");
    verify(harness.builder).equal(harness.actionStringPath, "posted");
    verify(harness.builder).equal(harness.entityTypeStringPath, "journal_entry");
    verify(harness.builder).equal(harness.actorIdentifierStringPath, "42");
    verify(harness.builder).equal(harness.actorUserIdPath, 42L);
  }

  @Test
  void queryAuditTrail_executesStringActorSpecificationAndNullRangeFallback() {
    Company company = new Company();
    setField(company, "id", 13L);
    company.setCode("BBP");
    when(companyContextService.requireCurrentCompany()).thenReturn(company);
    when(auditActionEventRepository.findAll(any(Specification.class), any(Pageable.class)))
        .thenReturn(new PageImpl<>(List.of()));

    service.queryAuditTrail(null, null, "Ops@Example.com", null, null, 0, 20);

    ArgumentCaptor<Specification<AuditActionEvent>> specCaptor = ArgumentCaptor.forClass(Specification.class);
    verify(auditActionEventRepository).findAll(specCaptor.capture(), any(Pageable.class));

    CriteriaHarness harness = new CriteriaHarness();
    Predicate predicate = specCaptor.getValue().toPredicate(harness.root, harness.query, harness.builder);

    assertThat(predicate).isNotNull();
    verify(harness.builder).conjunction();
    verify(harness.builder).equal(harness.companyIdPath, 13L);
    verify(harness.builder).equal(harness.moduleStringPath, "accounting");
    verify(harness.builder).equal(harness.actorIdentifierStringPath, "ops@example.com");
  }

  @Test
  void byActor_numericFilterMatchesActorIdentifierOrActorUserId() {
    @SuppressWarnings("unchecked")
    Specification<AuditActionEvent> spec =
        (Specification<AuditActionEvent>)
            ReflectionTestUtils.invokeMethod(service, "byActor", "42");
    Root<AuditActionEvent> root = mock(Root.class);
    CriteriaQuery<?> query = mock(CriteriaQuery.class);
    CriteriaBuilder cb = mock(CriteriaBuilder.class);
    @SuppressWarnings("unchecked")
    Path<String> actorIdentifierPath = mock(Path.class);
    @SuppressWarnings("unchecked")
    Expression<String> loweredActorIdentifier = mock(Expression.class);
    @SuppressWarnings("unchecked")
    Path<Long> actorUserIdPath = mock(Path.class);
    Predicate actorIdentifierPredicate = mock(Predicate.class);
    Predicate actorUserIdPredicate = mock(Predicate.class);
    Predicate combined = mock(Predicate.class);

    when(root.get("actorIdentifier")).thenReturn((Path) actorIdentifierPath);
    when(actorIdentifierPath.as(String.class)).thenReturn((Path) actorIdentifierPath);
    when(cb.lower(actorIdentifierPath)).thenReturn(loweredActorIdentifier);
    when(cb.equal(loweredActorIdentifier, "42")).thenReturn(actorIdentifierPredicate);
    when(root.get("actorUserId")).thenReturn((Path) actorUserIdPath);
    when(cb.equal(actorUserIdPath, 42L)).thenReturn(actorUserIdPredicate);
    when(cb.or(actorIdentifierPredicate, actorUserIdPredicate)).thenReturn(combined);

    assertThat(spec.toPredicate(root, query, cb)).isSameAs(combined);
  }

  @Test
  void byActor_textFilterMatchesOnlyActorIdentifier() {
    @SuppressWarnings("unchecked")
    Specification<AuditActionEvent> spec =
        (Specification<AuditActionEvent>)
            ReflectionTestUtils.invokeMethod(service, "byActor", "ops@example.com");
    Root<AuditActionEvent> root = mock(Root.class);
    CriteriaQuery<?> query = mock(CriteriaQuery.class);
    CriteriaBuilder cb = mock(CriteriaBuilder.class);
    @SuppressWarnings("unchecked")
    Path<String> actorIdentifierPath = mock(Path.class);
    @SuppressWarnings("unchecked")
    Expression<String> loweredActorIdentifier = mock(Expression.class);
    Predicate actorIdentifierPredicate = mock(Predicate.class);

    when(root.get("actorIdentifier")).thenReturn((Path) actorIdentifierPath);
    when(actorIdentifierPath.as(String.class)).thenReturn((Path) actorIdentifierPath);
    when(cb.lower(actorIdentifierPath)).thenReturn(loweredActorIdentifier);
    when(cb.equal(loweredActorIdentifier, "ops@example.com")).thenReturn(actorIdentifierPredicate);

    assertThat(spec.toPredicate(root, query, cb)).isSameAs(actorIdentifierPredicate);
    verify(root, never()).get("actorUserId");
  }

  @Test
  void byExactIgnoreCase_returnsNullForBlankInputAndLowercasePredicateForText() {
    Specification<AuditActionEvent> blankSpec =
        (Specification<AuditActionEvent>)
            ReflectionTestUtils.invokeMethod(service, "byExactIgnoreCase", "action", "   ");

    assertThat(blankSpec)
        .isNull();

    @SuppressWarnings("unchecked")
    Specification<AuditActionEvent> spec =
        (Specification<AuditActionEvent>)
            ReflectionTestUtils.invokeMethod(service, "byExactIgnoreCase", "entityType", "Journal_Entry");
    Root<AuditActionEvent> root = mock(Root.class);
    CriteriaQuery<?> query = mock(CriteriaQuery.class);
    CriteriaBuilder cb = mock(CriteriaBuilder.class);
    @SuppressWarnings("unchecked")
    Path<String> fieldPath = mock(Path.class);
    @SuppressWarnings("unchecked")
    Expression<String> loweredField = mock(Expression.class);
    Predicate predicate = mock(Predicate.class);

    when(root.get("entityType")).thenReturn((Path) fieldPath);
    when(fieldPath.as(String.class)).thenReturn((Path) fieldPath);
    when(cb.lower(fieldPath)).thenReturn(loweredField);
    when(cb.equal(loweredField, "journal_entry")).thenReturn(predicate);

    assertThat(spec.toPredicate(root, query, cb)).isSameAs(predicate);
  }

  @Test
  @SuppressWarnings({"unchecked", "rawtypes"})
  void queryAuditTrail_buildsSpecificationForNumericActorAndDateRange() {
    Company company = new Company();
    setField(company, "id", 12L);
    company.setCode("BBP");
    when(companyContextService.requireCurrentCompany()).thenReturn(company);
    when(auditActionEventRepository.findAll(any(Specification.class), any(Pageable.class)))
        .thenReturn(new PageImpl<>(List.of()));

    service.queryAuditTrail(
        LocalDate.of(2026, 2, 1),
        LocalDate.of(2026, 2, 28),
        "42",
        "manual_journal_created",
        "journal_entry",
        -5,
        500);

    ArgumentCaptor<Specification<AuditActionEvent>> specCaptor =
        ArgumentCaptor.forClass(Specification.class);
    ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
    verify(auditActionEventRepository).findAll(specCaptor.capture(), pageableCaptor.capture());
    assertThat(pageableCaptor.getValue().getPageNumber()).isZero();
    assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(200);

    Root<AuditActionEvent> root = mock(Root.class);
    CriteriaQuery<?> query = mock(CriteriaQuery.class);
    CriteriaBuilder cb = mock(CriteriaBuilder.class);

    Path<Long> companyIdPath = mock(Path.class);
    Path<Instant> occurredAtPath = mock(Path.class);
    Path<String> modulePath = mock(Path.class);
    Path<String> actorIdentifierPath = mock(Path.class);
    Path<Long> actorUserIdPath = mock(Path.class);
    Path<String> actionPath = mock(Path.class);
    Path<String> entityTypePath = mock(Path.class);

    Expression<String> loweredModule = mock(Expression.class);
    Expression<String> loweredActorIdentifier = mock(Expression.class);
    Expression<String> loweredAction = mock(Expression.class);
    Expression<String> loweredEntityType = mock(Expression.class);

    Predicate companyPredicate = mock(Predicate.class);
    Predicate occurredAfterPredicate = mock(Predicate.class);
    Predicate occurredBeforePredicate = mock(Predicate.class);
    Predicate occurredRangePredicate = mock(Predicate.class);
    Predicate modulePredicate = mock(Predicate.class);
    Predicate actorIdentifierPredicate = mock(Predicate.class);
    Predicate actorUserIdPredicate = mock(Predicate.class);
    Predicate actorPredicate = mock(Predicate.class);
    Predicate actionPredicate = mock(Predicate.class);
    Predicate entityTypePredicate = mock(Predicate.class);
    Predicate combinedPredicate = mock(Predicate.class);

    when(root.get("companyId")).thenReturn((Path) companyIdPath);
    when(root.get("occurredAt")).thenReturn((Path) occurredAtPath);
    when(root.get("module")).thenReturn((Path) modulePath);
    when(root.get("actorIdentifier")).thenReturn((Path) actorIdentifierPath);
    when(root.get("actorUserId")).thenReturn((Path) actorUserIdPath);
    when(root.get("action")).thenReturn((Path) actionPath);
    when(root.get("entityType")).thenReturn((Path) entityTypePath);

    when(modulePath.as(String.class)).thenReturn((Expression) modulePath);
    when(actorIdentifierPath.as(String.class)).thenReturn((Expression) actorIdentifierPath);
    when(actionPath.as(String.class)).thenReturn((Expression) actionPath);
    when(entityTypePath.as(String.class)).thenReturn((Expression) entityTypePath);

    when(cb.lower(modulePath)).thenReturn(loweredModule);
    when(cb.lower(actorIdentifierPath)).thenReturn(loweredActorIdentifier);
    when(cb.lower(actionPath)).thenReturn(loweredAction);
    when(cb.lower(entityTypePath)).thenReturn(loweredEntityType);

    when(cb.equal(companyIdPath, 12L)).thenReturn(companyPredicate);
    when(cb.greaterThanOrEqualTo(eq(occurredAtPath), eq(Instant.parse("2026-02-01T00:00:00Z"))))
        .thenReturn(occurredAfterPredicate);
    when(cb.lessThan(eq(occurredAtPath), eq(Instant.parse("2026-03-01T00:00:00Z"))))
        .thenReturn(occurredBeforePredicate);
    when(cb.equal(loweredModule, "accounting")).thenReturn(modulePredicate);
    when(cb.equal(loweredActorIdentifier, "42")).thenReturn(actorIdentifierPredicate);
    when(cb.equal(actorUserIdPath, 42L)).thenReturn(actorUserIdPredicate);
    when(cb.or(actorIdentifierPredicate, actorUserIdPredicate)).thenReturn(actorPredicate);
    when(cb.equal(loweredAction, "manual_journal_created")).thenReturn(actionPredicate);
    when(cb.equal(loweredEntityType, "journal_entry")).thenReturn(entityTypePredicate);
    when(cb.and(any(Predicate.class), any(Predicate.class)))
        .thenReturn(occurredRangePredicate, combinedPredicate, combinedPredicate, combinedPredicate);

    Predicate result = specCaptor.getValue().toPredicate(root, query, cb);

    assertThat(result).isSameAs(combinedPredicate);
    verify(cb).equal(companyIdPath, 12L);
    verify(cb).greaterThanOrEqualTo(occurredAtPath, Instant.parse("2026-02-01T00:00:00Z"));
    verify(cb).lessThan(occurredAtPath, Instant.parse("2026-03-01T00:00:00Z"));
    verify(cb).equal(loweredModule, "accounting");
    verify(cb).or(actorIdentifierPredicate, actorUserIdPredicate);
    verify(cb).equal(loweredAction, "manual_journal_created");
    verify(cb).equal(loweredEntityType, "journal_entry");
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

  @SuppressWarnings({"unchecked", "rawtypes"})
  private static final class CriteriaHarness {
    private final CriteriaBuilder builder = org.mockito.Mockito.mock(CriteriaBuilder.class);
    private final CriteriaQuery<?> query = org.mockito.Mockito.mock(CriteriaQuery.class);
    private final Root<AuditActionEvent> root = org.mockito.Mockito.mock(Root.class);
    private final Path<Long> companyIdPath = org.mockito.Mockito.mock(Path.class);
    private final Path<Instant> occurredAtPath = org.mockito.Mockito.mock(Path.class);
    private final Path<String> modulePath = org.mockito.Mockito.mock(Path.class);
    private final Path<String> actionPath = org.mockito.Mockito.mock(Path.class);
    private final Path<String> entityTypePath = org.mockito.Mockito.mock(Path.class);
    private final Path<String> actorIdentifierPath = org.mockito.Mockito.mock(Path.class);
    private final Path<Long> actorUserIdPath = org.mockito.Mockito.mock(Path.class);
    private final Expression<String> moduleStringPath = org.mockito.Mockito.mock(Expression.class);
    private final Expression<String> actionStringPath = org.mockito.Mockito.mock(Expression.class);
    private final Expression<String> entityTypeStringPath = org.mockito.Mockito.mock(Expression.class);
    private final Expression<String> actorIdentifierStringPath = org.mockito.Mockito.mock(Expression.class);
    private final Predicate predicate = org.mockito.Mockito.mock(Predicate.class);
    private final Predicate andPredicate = org.mockito.Mockito.mock(Predicate.class);
    private final Predicate conjunctionPredicate = org.mockito.Mockito.mock(Predicate.class);

    private CriteriaHarness() {
      when(root.get("companyId")).thenReturn((Path) companyIdPath);
      when(root.get("occurredAt")).thenReturn((Path) occurredAtPath);
      when(root.get("module")).thenReturn((Path) modulePath);
      when(root.get("action")).thenReturn((Path) actionPath);
      when(root.get("entityType")).thenReturn((Path) entityTypePath);
      when(root.get("actorIdentifier")).thenReturn((Path) actorIdentifierPath);
      when(root.get("actorUserId")).thenReturn((Path) actorUserIdPath);
      when(modulePath.as(String.class)).thenReturn((Expression) moduleStringPath);
      when(actionPath.as(String.class)).thenReturn((Expression) actionStringPath);
      when(entityTypePath.as(String.class)).thenReturn((Expression) entityTypeStringPath);
      when(actorIdentifierPath.as(String.class)).thenReturn((Expression) actorIdentifierStringPath);
      when(builder.lower(any(Expression.class))).thenAnswer(invocation -> invocation.getArgument(0));
      org.mockito.Mockito.doReturn(predicate).when(builder).equal(any(), any());
      when(builder.greaterThanOrEqualTo(any(Expression.class), any(Instant.class))).thenReturn(predicate);
      when(builder.lessThan(any(Expression.class), any(Instant.class))).thenReturn(predicate);
      when(builder.and(any(Predicate.class), any(Predicate.class))).thenReturn(andPredicate);
      when(builder.or(any(Predicate.class), any(Predicate.class))).thenReturn(predicate);
      when(builder.conjunction()).thenReturn(conjunctionPredicate);
    }
  }
}
