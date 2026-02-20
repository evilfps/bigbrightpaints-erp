package com.bigbrightpaints.erp.core.audittrail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bigbrightpaints.erp.modules.auth.domain.UserAccount;
import com.bigbrightpaints.erp.modules.auth.domain.UserPrincipal;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

@ExtendWith(MockitoExtension.class)
class EnterpriseAuditTrailServiceTest {

    @Mock
    private AuditActionEventRepository auditActionEventRepository;
    @Mock
    private AuditActionEventRetryRepository auditActionEventRetryRepository;
    @Mock
    private MlInteractionEventRepository mlInteractionEventRepository;
    @Mock
    private CompanyContextService companyContextService;
    @Captor
    private ArgumentCaptor<AuditActionEvent> eventCaptor;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void constructor_rejectsBlankAuditPrivateKey() {
        assertThatThrownBy(() -> new EnterpriseAuditTrailService(
                auditActionEventRepository,
                auditActionEventRetryRepository,
                mlInteractionEventRepository,
                companyContextService,
                new ObjectMapper(),
                "   "))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("erp.security.audit.private-key must be configured");
    }

    @Test
    void recordBusinessEvent_dispatchesActorSnapshotFromSecurityContext() {
        EnterpriseAuditTrailService service = newService();

        EnterpriseAuditTrailService selfProxy = mock(EnterpriseAuditTrailService.class);
        doNothing().when(selfProxy).recordBusinessEventAsync(any(), any());
        setField(service, "self", selfProxy);

        Company company = new Company();
        setField(company, "id", 5L);
        UserAccount contextActor = user(101L, "context-user@bbp.com");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(new UserPrincipal(contextActor), null, List.of()));

        AuditActionEventCommand command = command(company, null);
        service.recordBusinessEvent(command);

        ArgumentCaptor<UserAccount> actorCaptor = ArgumentCaptor.forClass(UserAccount.class);
        verify(selfProxy).recordBusinessEventAsync(any(AuditActionEventCommand.class), actorCaptor.capture());
        assertThat(actorCaptor.getValue()).isNotNull();
        assertThat(actorCaptor.getValue().getId()).isEqualTo(101L);
        assertThat(actorCaptor.getValue().getEmail()).isEqualTo("context-user@bbp.com");
    }

    @Test
    void recordBusinessEventAsync_prefersProvidedActorSnapshot() {
        EnterpriseAuditTrailService service = newService();

        Company company = new Company();
        setField(company, "id", 7L);

        UserAccount contextActor = user(200L, "context-only@bbp.com");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(new UserPrincipal(contextActor), null, List.of()));

        UserAccount snapshotActor = user(300L, "snapshot@bbp.com");
        AuditActionEventCommand command = command(company, null);

        service.recordBusinessEventAsync(command, snapshotActor);

        verify(auditActionEventRepository).save(eventCaptor.capture());
        AuditActionEvent saved = eventCaptor.getValue();
        assertThat(saved.getActorUserId()).isEqualTo(300L);
        assertThat(saved.getActorIdentifier()).isEqualTo("snapshot@bbp.com");
    }

    @Test
    void recordBusinessEventAsync_persistsFailure_andScheduledRetryPersistsEvent() {
        EnterpriseAuditTrailService service = newService();
        Company company = new Company();
        setField(company, "id", 9L);
        UserAccount snapshotActor = user(444L, "retry-user@bbp.com");
        AuditActionEventCommand command = command(company, null);

        when(auditActionEventRepository.save(any(AuditActionEvent.class)))
                .thenThrow(new RuntimeException("db unavailable"))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(auditActionEventRetryRepository.save(any(AuditActionEventRetry.class)))
                .thenAnswer(invocation -> {
                    AuditActionEventRetry retry = invocation.getArgument(0);
                    if (retry.getId() == null) {
                        setField(retry, "id", 900L);
                    }
                    return retry;
                });

        service.recordBusinessEventAsync(command, snapshotActor);
        assertThat(service.pendingBusinessEventRetryQueueSize()).isZero();
        ArgumentCaptor<AuditActionEventRetry> retryCaptor = ArgumentCaptor.forClass(AuditActionEventRetry.class);
        verify(auditActionEventRetryRepository).save(retryCaptor.capture());
        AuditActionEventRetry savedRetry = retryCaptor.getValue();
        when(auditActionEventRetryRepository.lockDueRetries(any(), anyInt()))
                .thenReturn(List.of(savedRetry));

        service.retryQueuedBusinessEvents();

        verify(auditActionEventRepository, times(2)).save(any(AuditActionEvent.class));
        verify(auditActionEventRetryRepository).delete(savedRetry);
        assertThat(service.pendingBusinessEventRetryQueueSize()).isZero();
    }

    @Test
    void retryQueuedBusinessEvents_dropsPersistentRetryAfterConfiguredMaxAttempts() {
        EnterpriseAuditTrailService service = newService();
        setField(service, "businessEventRetryMaxAttempts", 2);

        Company company = new Company();
        setField(company, "id", 11L);
        AuditActionEventCommand command = command(company, null);

        doThrow(new RuntimeException("persistent failure"))
                .when(auditActionEventRepository)
                .save(any(AuditActionEvent.class));
        when(auditActionEventRetryRepository.save(any(AuditActionEventRetry.class)))
                .thenAnswer(invocation -> {
                    AuditActionEventRetry retry = invocation.getArgument(0);
                    if (retry.getId() == null) {
                        setField(retry, "id", 901L);
                    }
                    return retry;
                });

        service.recordBusinessEventAsync(command, null);
        assertThat(service.pendingBusinessEventRetryQueueSize()).isZero();
        ArgumentCaptor<AuditActionEventRetry> retryCaptor = ArgumentCaptor.forClass(AuditActionEventRetry.class);
        verify(auditActionEventRetryRepository).save(retryCaptor.capture());
        AuditActionEventRetry savedRetry = retryCaptor.getValue();
        when(auditActionEventRetryRepository.lockDueRetries(any(), anyInt()))
                .thenReturn(List.of(savedRetry));

        service.retryQueuedBusinessEvents();

        verify(auditActionEventRepository, times(2)).save(any(AuditActionEvent.class));
        verify(auditActionEventRetryRepository).delete(savedRetry);
        assertThat(service.pendingBusinessEventRetryQueueSize()).isZero();
    }

    @Test
    void recordBusinessEventAsync_honorsRetryQueueCapacityWhenPersistentRetryStoreUnavailable() {
        EnterpriseAuditTrailService service = newService();
        setField(service, "businessEventRetryMaxQueueSize", 1);

        Company company = new Company();
        setField(company, "id", 13L);

        doThrow(new RuntimeException("db unavailable"))
                .when(auditActionEventRepository)
                .save(any(AuditActionEvent.class));
        doThrow(new RuntimeException("retry-store-unavailable"))
                .when(auditActionEventRetryRepository)
                .save(any(AuditActionEventRetry.class));

        service.recordBusinessEventAsync(command(company, null), null);
        service.recordBusinessEventAsync(command(company, null), null);

        verify(auditActionEventRepository, times(2)).save(any(AuditActionEvent.class));
        verify(auditActionEventRetryRepository, times(2)).save(any(AuditActionEventRetry.class));
        assertThat(service.pendingBusinessEventRetryQueueSize()).isEqualTo(1);
    }

    @Test
    @SuppressWarnings("unchecked")
    void byOccurredRange_usesExclusiveUpperBoundWhenFromAndToProvided() throws Exception {
        EnterpriseAuditTrailService service = newService();
        Method method = EnterpriseAuditTrailService.class
                .getDeclaredMethod("byOccurredRange", LocalDate.class, LocalDate.class);
        method.setAccessible(true);
        Specification<AuditActionEvent> spec =
                (Specification<AuditActionEvent>) method.invoke(service, LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 2));

        Root<AuditActionEvent> root = mock(Root.class);
        CriteriaQuery<?> query = mock(CriteriaQuery.class);
        CriteriaBuilder cb = mock(CriteriaBuilder.class);
        Path<Instant> occurredPath = mock(Path.class);
        Predicate ge = mock(Predicate.class);
        Predicate lt = mock(Predicate.class);
        Predicate combined = mock(Predicate.class);
        when(root.<Instant>get("occurredAt")).thenReturn(occurredPath);
        when(cb.greaterThanOrEqualTo(any(Path.class), any(Instant.class))).thenReturn(ge);
        when(cb.lessThan(any(Path.class), any(Instant.class))).thenReturn(lt);
        when(cb.and(ge, lt)).thenReturn(combined);

        Predicate result = spec.toPredicate(root, query, cb);

        verify(cb).greaterThanOrEqualTo(any(Path.class), any(Instant.class));
        verify(cb).lessThan(any(Path.class), any(Instant.class));
        verify(cb).and(ge, lt);
        verify(cb, times(0)).between(any(Path.class), any(Instant.class), any(Instant.class));
        assertThat(result).isSameAs(combined);
    }

    @Test
    @SuppressWarnings("unchecked")
    void byOccurredRangeMl_usesExclusiveUpperBoundWhenFromAndToProvided() throws Exception {
        EnterpriseAuditTrailService service = newService();
        Method method = EnterpriseAuditTrailService.class
                .getDeclaredMethod("byOccurredRangeMl", LocalDate.class, LocalDate.class);
        method.setAccessible(true);
        Specification<MlInteractionEvent> spec =
                (Specification<MlInteractionEvent>) method.invoke(service, LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 2));

        Root<MlInteractionEvent> root = mock(Root.class);
        CriteriaQuery<?> query = mock(CriteriaQuery.class);
        CriteriaBuilder cb = mock(CriteriaBuilder.class);
        Path<Instant> occurredPath = mock(Path.class);
        Predicate ge = mock(Predicate.class);
        Predicate lt = mock(Predicate.class);
        Predicate combined = mock(Predicate.class);
        when(root.<Instant>get("occurredAt")).thenReturn(occurredPath);
        when(cb.greaterThanOrEqualTo(any(Path.class), any(Instant.class))).thenReturn(ge);
        when(cb.lessThan(any(Path.class), any(Instant.class))).thenReturn(lt);
        when(cb.and(ge, lt)).thenReturn(combined);

        Predicate result = spec.toPredicate(root, query, cb);

        verify(cb).greaterThanOrEqualTo(any(Path.class), any(Instant.class));
        verify(cb).lessThan(any(Path.class), any(Instant.class));
        verify(cb).and(ge, lt);
        verify(cb, times(0)).between(any(Path.class), any(Instant.class), any(Instant.class));
        assertThat(result).isSameAs(combined);
    }

    private EnterpriseAuditTrailService newService() {
        return new EnterpriseAuditTrailService(
                auditActionEventRepository,
                auditActionEventRetryRepository,
                mlInteractionEventRepository,
                companyContextService,
                new ObjectMapper(),
                "test-audit-key");
    }

    private static AuditActionEventCommand command(Company company, UserAccount actorOverride) {
        return new AuditActionEventCommand(
                company,
                AuditActionEventSource.BACKEND,
                "accounting",
                "post_journal",
                "JournalEntry",
                "42",
                "JRN-42",
                AuditActionEventStatus.SUCCESS,
                null,
                null,
                null,
                null,
                "REQ-1",
                "TRACE-1",
                "127.0.0.1",
                "JUnit",
                actorOverride,
                Boolean.FALSE,
                null,
                null,
                Instant.parse("2026-02-15T10:00:00Z")
        );
    }

    private static UserAccount user(Long id, String email) {
        UserAccount account = new UserAccount();
        setField(account, "id", id);
        account.setEmail(email);
        account.setEnabled(true);
        return account;
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
