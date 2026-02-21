package com.bigbrightpaints.erp.orchestrator.service;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.orchestrator.repository.OrchestratorCommand;
import com.bigbrightpaints.erp.orchestrator.repository.OrchestratorCommandRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrchestratorIdempotencyServiceTest {

    @Mock
    private OrchestratorCommandRepository commandRepository;
    @Mock
    private CompanyContextService companyContextService;

    private OrchestratorIdempotencyService service;
    private Company company;

    @BeforeEach
    void setUp() {
        service = new OrchestratorIdempotencyService(
                commandRepository,
                companyContextService,
                new ObjectMapper(),
                new NoOpTransactionManager());

        company = new Company();
        ReflectionTestUtils.setField(company, "id", 7L);
        when(companyContextService.requireCurrentCompany()).thenReturn(company);
    }

    @Test
    void startReservesNewScopeWithoutDuplicateKeyFallback() {
        Map<String, Object> payload = Map.of("orderId", "101", "amount", "5000");
        String requestHash = ReflectionTestUtils.invokeMethod(
                service,
                "hashRequest",
                7L,
                "ORCH.ORDER.APPROVE",
                payload);

        OrchestratorCommand created = new OrchestratorCommand(
                7L,
                "ORCH.ORDER.APPROVE",
                "idem-new",
                requestHash,
                "trace-created");

        when(commandRepository.reserveScope(7L, "ORCH.ORDER.APPROVE", "idem-new", requestHash, "trace-seed"))
                .thenReturn(1);
        when(commandRepository.lockByScope(7L, "ORCH.ORDER.APPROVE", "idem-new"))
                .thenReturn(Optional.of(created));

        OrchestratorIdempotencyService.CommandLease lease = service.start(
                "ORCH.ORDER.APPROVE",
                "idem-new",
                payload,
                () -> "trace-seed");

        assertThat(lease.shouldExecute()).isTrue();
        assertThat(lease.traceId()).isEqualTo("trace-created");
        assertThat(lease.command()).isSameAs(created);
        verify(commandRepository).reserveScope(7L, "ORCH.ORDER.APPROVE", "idem-new", requestHash, "trace-seed");
        verify(commandRepository).lockByScope(7L, "ORCH.ORDER.APPROVE", "idem-new");
        verify(commandRepository, never()).saveAndFlush(any());
    }

    @Test
    void startReturnsExistingLeaseWhenReservationAlreadyExistsWithSamePayload() {
        Map<String, Object> payload = Map.of("orderId", "101", "amount", "5000");
        String requestHash = ReflectionTestUtils.invokeMethod(
                service,
                "hashRequest",
                7L,
                "ORCH.ORDER.APPROVE",
                payload);

        OrchestratorCommand existing = new OrchestratorCommand(
                7L,
                "ORCH.ORDER.APPROVE",
                "idem-existing",
                requestHash,
                "trace-existing");
        existing.markSuccess();

        when(commandRepository.reserveScope(7L, "ORCH.ORDER.APPROVE", "idem-existing", requestHash, "trace-seed"))
                .thenReturn(0);
        when(commandRepository.lockByScope(7L, "ORCH.ORDER.APPROVE", "idem-existing"))
                .thenReturn(Optional.of(existing));

        OrchestratorIdempotencyService.CommandLease lease = service.start(
                "ORCH.ORDER.APPROVE",
                "idem-existing",
                payload,
                () -> "trace-seed");

        assertThat(lease.shouldExecute()).isFalse();
        assertThat(lease.traceId()).isEqualTo("trace-existing");
        verify(commandRepository, never()).save(any());
    }

    @Test
    void startRejectsSameScopeWithDifferentPayload() {
        Map<String, Object> payload = Map.of("orderId", "101", "amount", "5000");
        String requestHash = ReflectionTestUtils.invokeMethod(
                service,
                "hashRequest",
                7L,
                "ORCH.ORDER.APPROVE",
                payload);

        OrchestratorCommand existing = new OrchestratorCommand(
                7L,
                "ORCH.ORDER.APPROVE",
                "idem-conflict",
                "different-hash",
                "trace-existing");

        when(commandRepository.reserveScope(7L, "ORCH.ORDER.APPROVE", "idem-conflict", requestHash, "trace-seed"))
                .thenReturn(0);
        when(commandRepository.lockByScope(7L, "ORCH.ORDER.APPROVE", "idem-conflict"))
                .thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.start(
                "ORCH.ORDER.APPROVE",
                "idem-conflict",
                payload,
                () -> "trace-seed"))
                .isInstanceOf(ApplicationException.class)
                .satisfies(ex -> {
                    ApplicationException appEx = (ApplicationException) ex;
                    assertThat(appEx.getErrorCode()).isEqualTo(ErrorCode.CONCURRENCY_CONFLICT);
                });

        verify(commandRepository, never()).save(any());
    }

    @Test
    void startRetriesFailedReservationWhenPayloadMatches() {
        Map<String, Object> payload = Map.of("orderId", "101", "amount", "5000");
        String requestHash = ReflectionTestUtils.invokeMethod(
                service,
                "hashRequest",
                7L,
                "ORCH.ORDER.APPROVE",
                payload);

        OrchestratorCommand existing = new OrchestratorCommand(
                7L,
                "ORCH.ORDER.APPROVE",
                "idem-failed",
                requestHash,
                "trace-failed");
        existing.markFailed("temporary");

        when(commandRepository.reserveScope(7L, "ORCH.ORDER.APPROVE", "idem-failed", requestHash, "trace-seed"))
                .thenReturn(0);
        when(commandRepository.lockByScope(7L, "ORCH.ORDER.APPROVE", "idem-failed"))
                .thenReturn(Optional.of(existing));

        OrchestratorIdempotencyService.CommandLease lease = service.start(
                "ORCH.ORDER.APPROVE",
                "idem-failed",
                payload,
                () -> "trace-seed");

        assertThat(lease.shouldExecute()).isTrue();
        assertThat(existing.getStatus()).isEqualTo(OrchestratorCommand.Status.IN_PROGRESS);
        assertThat(existing.getLastError()).isNull();
        verify(commandRepository).save(existing);
    }

    @Test
    void startFailsClosedWhenPayloadSerializationIsNonDeterministic() {
        assertThatThrownBy(() -> service.start(
                "ORCH.ORDER.APPROVE",
                "idem-bad-payload",
                new Object(),
                () -> "trace-seed"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unable to deterministically hash orchestrator payload");
        verify(commandRepository, never()).reserveScope(any(), any(), any(), any(), any());
    }

    private static class NoOpTransactionManager extends AbstractPlatformTransactionManager {
        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            // no-op
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            // no-op
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            // no-op
        }
    }
}
