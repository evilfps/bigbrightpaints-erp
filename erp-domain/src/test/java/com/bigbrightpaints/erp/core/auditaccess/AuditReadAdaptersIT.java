package com.bigbrightpaints.erp.core.auditaccess;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import com.bigbrightpaints.erp.core.audit.AuditEvent;
import com.bigbrightpaints.erp.core.audit.AuditLog;
import com.bigbrightpaints.erp.core.audit.AuditLogRepository;
import com.bigbrightpaints.erp.core.audit.AuditStatus;
import com.bigbrightpaints.erp.core.auditaccess.dto.AuditFeedItemDto;
import com.bigbrightpaints.erp.core.audittrail.AuditActionEvent;
import com.bigbrightpaints.erp.core.audittrail.AuditActionEventRepository;
import com.bigbrightpaints.erp.core.audittrail.AuditActionEventSource;
import com.bigbrightpaints.erp.core.audittrail.AuditActionEventStatus;
import com.bigbrightpaints.erp.core.security.AuthScopeService;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.test.AbstractIntegrationTest;

@Tag("critical")
@Transactional
class AuditReadAdaptersIT extends AbstractIntegrationTest {

  @Autowired private AuditLogReadAdapter auditLogReadAdapter;
  @Autowired private BusinessAuditReadAdapter businessAuditReadAdapter;
  @Autowired private AuditLogRepository auditLogRepository;
  @Autowired private AuditActionEventRepository auditActionEventRepository;
  @Autowired private AuthScopeService authScopeService;

  @Test
  void auditLogAdapter_filtersModulesAcrossPathsResourceTypesAndCategories() {
    Company company = dataSeeder.ensureCompany("ARDMOD", "Audit Read Modules");
    LocalDate day = LocalDate.of(2035, 1, 20);

    AuditLog superadmin =
        saveAuditLog(
            company.getId(),
            AuditEvent.USER_CREATED,
            day.atTime(9, 0),
            log -> log.setRequestPath("/api/v1/superadmin/users"));
    AuditLog admin =
        saveAuditLog(
            company.getId(),
            AuditEvent.USER_UPDATED,
            day.atTime(9, 1),
            log -> log.setRequestPath("/api/v1/admin/settings"));
    AuditLog accountingPath =
        saveAuditLog(
            company.getId(),
            AuditEvent.PAYROLL_POSTED,
            day.atTime(9, 2),
            log -> log.setRequestPath("/api/v1/accounting/journal-entries/17"));
    AuditLog auth =
        saveAuditLog(
            company.getId(),
            AuditEvent.LOGIN_SUCCESS,
            day.atTime(9, 3),
            log -> log.setRequestPath("/api/v1/auth/login"));
    AuditLog changelog =
        saveAuditLog(
            company.getId(),
            AuditEvent.DATA_READ,
            day.atTime(9, 4),
            log -> log.setRequestPath("/api/v1/changelog/releases"));
    AuditLog companies =
        saveAuditLog(
            company.getId(),
            AuditEvent.DATA_UPDATE,
            day.atTime(9, 5),
            log -> log.setRequestPath("/api/v1/companies/17"));
    AuditLog securityFallback =
        saveAuditLog(company.getId(), AuditEvent.ACCESS_DENIED, day.atTime(9, 6), log -> {});
    AuditLog dataFallback =
        saveAuditLog(company.getId(), AuditEvent.DATA_READ, day.atTime(9, 7), log -> {});
    AuditLog complianceFallback =
        saveAuditLog(company.getId(), AuditEvent.AUDIT_LOG_EXPORTED, day.atTime(9, 8), log -> {});
    AuditLog systemFallback =
        saveAuditLog(company.getId(), AuditEvent.SYSTEM_SHUTDOWN, day.atTime(9, 9), log -> {});
    AuditLog systemIntegrationFailure =
        saveAuditLog(
            company.getId(),
            AuditEvent.INTEGRATION_FAILURE,
            day.atTime(9, 10),
            log -> log.setMetadata(Map.of()));
    AuditLog accountingFallback =
        saveAuditLog(company.getId(), AuditEvent.PAYMENT_PROCESSED, day.atTime(9, 11), log -> {});
    AuditLog accountingTrailFailure =
        saveAuditLog(
            company.getId(),
            AuditEvent.INTEGRATION_FAILURE,
            day.atTime(9, 12),
            log -> log.setMetadata(Map.of("eventTrailOperation", "PAYROLL_POSTED")));
    AuditLog businessFallback =
        saveAuditLog(company.getId(), AuditEvent.REFERENCE_GENERATED, day.atTime(9, 13), log -> {});
    AuditLog resourceTypeData =
        saveAuditLog(
            company.getId(),
            AuditEvent.LOGIN_FAILURE,
            day.atTime(9, 14),
            log -> log.setResourceType("DATA"));
    AuditLog metadataCompliance =
        saveAuditLog(
            company.getId(),
            AuditEvent.LOGIN_FAILURE,
            day.atTime(9, 15),
            log -> log.setMetadata(Map.of("resourceType", "COMPLIANCE")));

    assertThat(
            sourceIds(
                queryTenantLogs(company, day, day, "SUPERADMIN", null, null, null, null, null)))
        .containsExactly(superadmin.getId());
    assertThat(sourceIds(queryTenantLogs(company, day, day, "ADMIN", null, null, null, null, null)))
        .containsExactly(admin.getId());
    assertThat(
            sourceIds(
                queryTenantLogs(company, day, day, "ACCOUNTING", null, null, null, null, null)))
        .containsExactlyInAnyOrder(
            accountingPath.getId(), accountingFallback.getId(), accountingTrailFailure.getId());
    assertThat(sourceIds(queryTenantLogs(company, day, day, "AUTH", null, null, null, null, null)))
        .containsExactly(auth.getId());
    assertThat(
            sourceIds(
                queryTenantLogs(company, day, day, "CHANGELOG", null, null, null, null, null)))
        .containsExactly(changelog.getId());
    assertThat(
            sourceIds(
                queryTenantLogs(company, day, day, "COMPANIES", null, null, null, null, null)))
        .containsExactly(companies.getId());
    assertThat(
            sourceIds(queryTenantLogs(company, day, day, "SECURITY", null, null, null, null, null)))
        .containsExactly(securityFallback.getId());
    assertThat(sourceIds(queryTenantLogs(company, day, day, "DATA", null, null, null, null, null)))
        .containsExactlyInAnyOrder(dataFallback.getId(), resourceTypeData.getId());
    assertThat(
            sourceIds(
                queryTenantLogs(company, day, day, "COMPLIANCE", null, null, null, null, null)))
        .containsExactlyInAnyOrder(complianceFallback.getId(), metadataCompliance.getId());
    assertThat(
            sourceIds(queryTenantLogs(company, day, day, "SYSTEM", null, null, null, null, null)))
        .containsExactlyInAnyOrder(systemFallback.getId(), systemIntegrationFailure.getId());
    assertThat(
            sourceIds(queryTenantLogs(company, day, day, "BUSINESS", null, null, null, null, null)))
        .containsExactly(businessFallback.getId());
    assertThat(
            sourceIds(queryTenantLogs(company, day, day, "MYSTERY", null, null, null, null, null)))
        .isEmpty();
    assertThat(
            sourceIds(queryAccountingLogs(company, day, day, null, null, null, null, null, null)))
        .containsExactlyInAnyOrder(
            accountingPath.getId(), accountingFallback.getId(), accountingTrailFailure.getId());
    assertThat(
            sourceIds(
                queryAccountingLogs(company, day, day, "BUSINESS", null, null, null, null, null)))
        .isEmpty();
  }

  @Test
  void auditLogAdapter_filtersByActorStatusActionEntityReferenceAndDateRanges() {
    Company company = dataSeeder.ensureCompany("ARDFLT", "Audit Read Filters");
    LocalDate dayOne = LocalDate.of(2035, 2, 1);
    LocalDate dayTwo = dayOne.plusDays(1);
    LocalDate dayThree = dayTwo.plusDays(1);

    AuditLog first =
        saveAuditLog(
            company.getId(),
            AuditEvent.ACCESS_DENIED,
            dayOne.atTime(8, 0),
            log -> {
              log.setUsername("Ops@Example.com");
              log.setUserId("501");
              log.setStatus(AuditStatus.FAILURE);
              log.setMetadata(Map.of("entityType", "DEALER", "referenceNumber", "REF-001"));
              log.setResourceId("RES-1");
              log.setTraceId("TRACE-1");
            });
    AuditLog second =
        saveAuditLog(
            company.getId(),
            AuditEvent.DATA_UPDATE,
            dayTwo.atTime(8, 0),
            log -> {
              log.setUsername("other@example.com");
              log.setUserId("777");
              log.setMetadata(Map.of("resourceType", "JOURNAL_ENTRY", "journalReference", "JE-99"));
              log.setTraceId("TRACE-2");
            });
    AuditLog third =
        saveAuditLog(
            company.getId(),
            AuditEvent.DATA_UPDATE,
            dayThree.atTime(8, 0),
            log -> {
              log.setUsername("later@example.com");
              log.setUserId("888");
              log.setTraceId("TRACE-3");
            });

    assertThat(
            sourceIds(queryTenantLogs(company, dayTwo, null, null, null, null, null, null, null)))
        .containsExactlyInAnyOrder(second.getId(), third.getId());
    assertThat(
            sourceIds(queryTenantLogs(company, null, dayTwo, null, null, null, null, null, null)))
        .containsExactlyInAnyOrder(first.getId(), second.getId());
    assertThat(
            sourceIds(
                queryTenantLogs(
                    company, null, null, null, null, null, "ops@example.com", null, null)))
        .containsExactly(first.getId());
    assertThat(sourceIds(queryTenantLogs(company, null, null, null, null, null, "777", null, null)))
        .containsExactly(second.getId());
    assertThat(
            sourceIds(
                queryTenantLogs(company, null, null, null, null, "failure", null, null, null)))
        .containsExactly(first.getId());
    assertThat(
            sourceIds(
                queryTenantLogs(
                    company, null, null, null, "access_denied", null, null, null, null)))
        .containsExactly(first.getId());
    assertThat(
            sourceIds(queryTenantLogs(company, null, null, null, null, null, null, "dealer", null)))
        .containsExactly(first.getId());
    assertThat(
            sourceIds(queryTenantLogs(company, null, null, null, null, null, null, null, "je-99")))
        .containsExactly(second.getId());
    assertThat(
            sourceIds(
                queryTenantLogs(company, null, null, null, null, null, null, null, "trace-1")))
        .containsExactly(first.getId());
  }

  @Test
  void queryPlatformFeed_appliesVisibilityRulesAndCompanyCodeFallbacks() {
    String platformCode = "PLATVIS" + Math.abs((int) (System.nanoTime() % 100000));
    authScopeService.updatePlatformScopeCode(platformCode);
    Company tenantA = dataSeeder.ensureCompany("PLATTA", "Platform Tenant A");
    Company tenantB = dataSeeder.ensureCompany("PLATTB", "Platform Tenant B");
    LocalDate absentDay = LocalDate.of(2035, 3, 1);

    AuditLog nullCompany =
        saveAuditLog(
            null,
            AuditEvent.DATA_READ,
            absentDay.atTime(9, 0),
            log -> log.setRequestPath("/internal/platform"));
    AuditLog superadmin =
        saveAuditLog(
            tenantA.getId(),
            AuditEvent.USER_CREATED,
            absentDay.atTime(9, 1),
            log -> log.setRequestPath("/api/v1/superadmin/users"));
    AuditLog adminSettings =
        saveAuditLog(
            tenantA.getId(),
            AuditEvent.CONFIGURATION_CHANGED,
            absentDay.atTime(9, 2),
            log -> log.setRequestPath("/api/v1/admin/settings/preferences"));
    AuditLog targetCompany =
        saveAuditLog(
            tenantB.getId(),
            AuditEvent.DATA_UPDATE,
            absentDay.atTime(9, 3),
            log -> {
              log.setRequestPath("/api/v1/companies/44");
              log.setMetadata(Map.of("targetCompanyCode", "TENANT-B"));
            });
    saveAuditLog(
        tenantA.getId(),
        AuditEvent.USER_UPDATED,
        absentDay.atTime(9, 4),
        log -> log.setRequestPath("/api/v1/admin/users"));

    AuditFeedSlice absentPlatformFeed =
        auditLogReadAdapter.queryPlatformFeed(
            filter(absentDay, absentDay, null, null, null, null, null, null));

    assertThat(sourceIds(absentPlatformFeed))
        .containsExactlyInAnyOrder(
            nullCompany.getId(), superadmin.getId(), adminSettings.getId(), targetCompany.getId());
    assertThat(companyCodesBySourceId(absentPlatformFeed))
        .containsEntry(nullCompany.getId(), null)
        .containsEntry(superadmin.getId(), tenantA.getCode())
        .containsEntry(adminSettings.getId(), tenantA.getCode())
        .containsEntry(targetCompany.getId(), "TENANT-B");

    Company platformCompany = dataSeeder.ensureCompany(platformCode, "Platform Company");
    LocalDate presentDay = absentDay.plusDays(1);
    AuditLog platformOwned =
        saveAuditLog(
            platformCompany.getId(),
            AuditEvent.DATA_READ,
            presentDay.atTime(9, 0),
            log -> log.setRequestPath("/api/v1/internal/platform"));

    AuditFeedSlice presentPlatformFeed =
        auditLogReadAdapter.queryPlatformFeed(
            filter(presentDay, presentDay, null, null, null, null, null, null));

    assertThat(sourceIds(presentPlatformFeed)).containsExactly(platformOwned.getId());
    assertThat(companyCodesBySourceId(presentPlatformFeed))
        .containsEntry(platformOwned.getId(), platformCode);
  }

  @Test
  void businessAuditAdapter_filtersTenantAndAccountingFeeds() {
    Company company = dataSeeder.ensureCompany("ARDBIZ", "Audit Read Business");
    LocalDate dayOne = LocalDate.of(2035, 4, 1);
    Long accountingActorId =
        dataSeeder
            .ensureUser("ops@example.com", "Passw0rd!", "Ops", company.getCode(), List.of())
            .getId();
    Long salesActorId =
        dataSeeder
            .ensureUser("sales@example.com", "Passw0rd!", "Sales", company.getCode(), List.of())
            .getId();
    Long payrollActorId =
        dataSeeder
            .ensureUser("payroll@example.com", "Passw0rd!", "Payroll", company.getCode(), List.of())
            .getId();

    AuditActionEvent accounting =
        saveAuditActionEvent(
            company,
            dayOne.atStartOfDay().plusHours(1).toInstant(ZoneOffset.UTC),
            "ACCOUNTING",
            "JOURNAL_ENTRY_POSTED",
            AuditActionEventStatus.SUCCESS,
            accountingActorId,
            "ops@example.com",
            "JOURNAL_ENTRY",
            "17",
            "JE-17",
            Map.of("subjectUserId", "91", "subjectIdentifier", "owner@example.com"));
    AuditActionEvent sales =
        saveAuditActionEvent(
            company,
            dayOne.plusDays(1).atStartOfDay().plusHours(1).toInstant(ZoneOffset.UTC),
            "SALES",
            "ORDER_APPROVED",
            AuditActionEventStatus.FAILURE,
            salesActorId,
            "sales@example.com",
            "SALES_ORDER",
            "SO-1",
            "SO-1",
            Map.of());
    AuditActionEvent accountingLater =
        saveAuditActionEvent(
            company,
            dayOne.plusDays(2).atStartOfDay().plusHours(1).toInstant(ZoneOffset.UTC),
            "ACCOUNTING",
            "PAYROLL_POSTED",
            AuditActionEventStatus.WARNING,
            payrollActorId,
            "payroll@example.com",
            "PAYROLL_RUN",
            "77",
            "PR-77",
            Map.of());

    assertThat(
            sourceIds(
                businessAuditReadAdapter.queryTenantCompanyFeed(
                    company,
                    filter(
                        dayOne,
                        dayOne.plusDays(2),
                        "sales",
                        "order_approved",
                        "failure",
                        "sales@example.com",
                        "sales_order",
                        "so-1"))))
        .containsExactly(sales.getId());
    assertThat(
            sourceIds(
                businessAuditReadAdapter.queryTenantCompanyFeed(
                    company, filter(dayOne.plusDays(1), null, null, null, null, null, null, null))))
        .containsExactlyInAnyOrder(sales.getId(), accountingLater.getId());
    assertThat(
            sourceIds(
                businessAuditReadAdapter.queryTenantCompanyFeed(
                    company,
                    filter(
                        null,
                        dayOne.plusDays(1),
                        null,
                        null,
                        null,
                        salesActorId.toString(),
                        null,
                        null))))
        .containsExactly(sales.getId());
    assertThat(
            sourceIds(
                businessAuditReadAdapter.queryAccountingFeed(
                    company,
                    filter(dayOne, dayOne.plusDays(2), null, null, null, null, null, null))))
        .containsExactlyInAnyOrder(accounting.getId(), accountingLater.getId());
    assertThat(
            sourceIds(
                businessAuditReadAdapter.queryAccountingFeed(
                    company,
                    filter(dayOne, dayOne.plusDays(2), "SALES", null, null, null, null, null))))
        .isEmpty();
  }

  private AuditFeedSlice queryTenantLogs(
      Company company,
      LocalDate from,
      LocalDate to,
      String module,
      String action,
      String status,
      String actor,
      String entityType,
      String reference) {
    return auditLogReadAdapter.queryTenantCompanyFeed(
        company, filter(from, to, module, action, status, actor, entityType, reference));
  }

  private AuditFeedSlice queryAccountingLogs(
      Company company,
      LocalDate from,
      LocalDate to,
      String module,
      String action,
      String status,
      String actor,
      String entityType,
      String reference) {
    return auditLogReadAdapter.queryAccountingFeed(
        company, filter(from, to, module, action, status, actor, entityType, reference));
  }

  private AuditFeedFilter filter(
      LocalDate from,
      LocalDate to,
      String module,
      String action,
      String status,
      String actor,
      String entityType,
      String reference) {
    return new AuditFeedFilter(
        from, to, module, action, status, actor, entityType, reference, 0, 50);
  }

  private List<Long> sourceIds(AuditFeedSlice slice) {
    return slice.items().stream().map(AuditFeedItemDto::sourceId).toList();
  }

  private Map<Long, String> companyCodesBySourceId(AuditFeedSlice slice) {
    Map<Long, String> companyCodes = new HashMap<>();
    for (AuditFeedItemDto item : slice.items()) {
      companyCodes.put(item.sourceId(), item.companyCode());
    }
    return companyCodes;
  }

  private AuditLog saveAuditLog(
      Long companyId, AuditEvent event, LocalDateTime occurredAt, Consumer<AuditLog> customizer) {
    AuditLog log = new AuditLog();
    log.setCompanyId(companyId);
    log.setEventType(event);
    log.setTimestamp(occurredAt);
    log.setStatus(AuditStatus.SUCCESS);
    log.setUserId("501");
    log.setUsername("ops@example.com");
    log.setRequestMethod("GET");
    log.setRequestPath("/api/v1/other");
    log.setTraceId(event.name() + "-" + occurredAt.toLocalTime());
    log.setMetadata(new HashMap<>());
    customizer.accept(log);
    return auditLogRepository.saveAndFlush(log);
  }

  private AuditActionEvent saveAuditActionEvent(
      Company company,
      Instant occurredAt,
      String module,
      String action,
      AuditActionEventStatus status,
      Long actorUserId,
      String actorIdentifier,
      String entityType,
      String entityId,
      String referenceNumber,
      Map<String, String> metadata) {
    AuditActionEvent event = new AuditActionEvent();
    event.setCompanyId(company.getId());
    event.setOccurredAt(occurredAt);
    event.setSource(AuditActionEventSource.BACKEND);
    event.setModule(module);
    event.setAction(action);
    event.setStatus(status);
    event.setActorUserId(actorUserId);
    event.setActorIdentifier(actorIdentifier);
    event.setEntityType(entityType);
    event.setEntityId(entityId);
    event.setReferenceNumber(referenceNumber);
    event.setTraceId(action + "-" + occurredAt.toEpochMilli());
    event.setMetadata(new HashMap<>(metadata));
    return auditActionEventRepository.saveAndFlush(event);
  }
}
