package com.bigbrightpaints.erp.truthsuite.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.orchestrator.repository.OrchestratorCommand;
import com.bigbrightpaints.erp.orchestrator.repository.OrchestratorCommandRepository;
import com.bigbrightpaints.erp.orchestrator.service.OrchestratorIdempotencyService;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.batch.support.transaction.ResourcelessTransactionManager;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("concurrency")
@Tag("reconciliation")
@Tag("critical")
class TS_RuntimeOrchestratorIdempotencyExecutableCoverageTest {

    @Test
    void start_creates_new_lease_and_trims_idempotency_key() {
        OrchestratorCommandRepository commandRepository = mock(OrchestratorCommandRepository.class);
        CompanyContextService companyContextService = mock(CompanyContextService.class);
        when(companyContextService.requireCurrentCompany()).thenReturn(company(101L));
        when(commandRepository.reserveScope(101L, "ORCH.ORDER.APPROVE", "idem-1", expectedHash(101L, "ORCH.ORDER.APPROVE",
                Map.of("orderId", "SO-1", "total", "100.00")), "trace-new"))
                .thenReturn(1);

        OrchestratorCommand reserved = new OrchestratorCommand(
                101L,
                "ORCH.ORDER.APPROVE",
                "idem-1",
                expectedHash(101L, "ORCH.ORDER.APPROVE", Map.of("orderId", "SO-1", "total", "100.00")),
                "trace-new"
        );
        when(commandRepository.lockByScope(101L, "ORCH.ORDER.APPROVE", "idem-1"))
                .thenReturn(Optional.of(reserved));

        OrchestratorIdempotencyService service = new OrchestratorIdempotencyService(
                commandRepository,
                companyContextService,
                new ObjectMapper(),
                new ResourcelessTransactionManager()
        );

        OrchestratorIdempotencyService.CommandLease lease = service.start(
                "ORCH.ORDER.APPROVE",
                " idem-1 ",
                Map.of("orderId", "SO-1", "total", "100.00"),
                () -> "trace-new"
        );

        assertThat(lease.shouldExecute()).isTrue();
        assertThat(lease.traceId()).isEqualTo("trace-new");
        assertThat(lease.command().getIdempotencyKey()).isEqualTo("idem-1");
        assertThat(lease.command().getStatus()).isEqualTo(OrchestratorCommand.Status.IN_PROGRESS);
        verify(commandRepository).reserveScope(101L, "ORCH.ORDER.APPROVE", "idem-1",
                expectedHash(101L, "ORCH.ORDER.APPROVE", Map.of("orderId", "SO-1", "total", "100.00")),
                "trace-new");
    }

    @Test
    void start_on_duplicate_failed_command_marks_retry_and_reuses_trace() {
        OrchestratorCommandRepository commandRepository = mock(OrchestratorCommandRepository.class);
        CompanyContextService companyContextService = mock(CompanyContextService.class);
        when(companyContextService.requireCurrentCompany()).thenReturn(company(201L));
        when(commandRepository.reserveScope(any(), any(), any(), any(), any())).thenReturn(0);

        Map<String, Object> payload = Map.of("batchId", "B-1", "qty", "10");
        String requestHash = expectedHash(201L, "ORCH.FACTORY.BATCH.DISPATCH", payload);
        OrchestratorCommand existing = new OrchestratorCommand(
                201L,
                "ORCH.FACTORY.BATCH.DISPATCH",
                "idem-dispatch",
                requestHash,
                "trace-existing"
        );
        existing.markFailed("broker timeout");
        when(commandRepository.lockByScope(201L, "ORCH.FACTORY.BATCH.DISPATCH", "idem-dispatch"))
                .thenReturn(Optional.of(existing));
        when(commandRepository.save(existing)).thenReturn(existing);

        OrchestratorIdempotencyService service = new OrchestratorIdempotencyService(
                commandRepository,
                companyContextService,
                new ObjectMapper(),
                new ResourcelessTransactionManager()
        );

        OrchestratorIdempotencyService.CommandLease lease = service.start(
                "ORCH.FACTORY.BATCH.DISPATCH",
                "idem-dispatch",
                payload,
                () -> "trace-new"
        );

        assertThat(lease.shouldExecute()).isTrue();
        assertThat(lease.traceId()).isEqualTo("trace-existing");
        assertThat(existing.getStatus()).isEqualTo(OrchestratorCommand.Status.IN_PROGRESS);
        assertThat(existing.getLastError()).isNull();
        verify(commandRepository).save(existing);
    }

    @Test
    void start_on_duplicate_success_returns_replay_lease_without_execution() {
        OrchestratorCommandRepository commandRepository = mock(OrchestratorCommandRepository.class);
        CompanyContextService companyContextService = mock(CompanyContextService.class);
        when(companyContextService.requireCurrentCompany()).thenReturn(company(301L));
        when(commandRepository.reserveScope(any(), any(), any(), any(), any())).thenReturn(0);

        Map<String, Object> payload = Map.of("orderId", "SO-2");
        String requestHash = expectedHash(301L, "ORCH.ORDER.APPROVE", payload);
        OrchestratorCommand existing = new OrchestratorCommand(
                301L,
                "ORCH.ORDER.APPROVE",
                "idem-order",
                requestHash,
                "trace-replay"
        );
        existing.markSuccess();
        when(commandRepository.lockByScope(301L, "ORCH.ORDER.APPROVE", "idem-order"))
                .thenReturn(Optional.of(existing));

        OrchestratorIdempotencyService service = new OrchestratorIdempotencyService(
                commandRepository,
                companyContextService,
                new ObjectMapper(),
                new ResourcelessTransactionManager()
        );

        OrchestratorIdempotencyService.CommandLease lease = service.start(
                "ORCH.ORDER.APPROVE",
                "idem-order",
                payload,
                () -> "trace-new"
        );

        assertThat(lease.shouldExecute()).isFalse();
        assertThat(lease.traceId()).isEqualTo("trace-replay");
        verify(commandRepository, never()).save(existing);
    }

    @Test
    void start_rejects_payload_mismatch_and_missing_locked_row() {
        OrchestratorCommandRepository commandRepository = mock(OrchestratorCommandRepository.class);
        CompanyContextService companyContextService = mock(CompanyContextService.class);
        when(companyContextService.requireCurrentCompany()).thenReturn(company(401L));
        when(commandRepository.reserveScope(any(), any(), any(), any(), any())).thenReturn(0);

        OrchestratorCommand mismatched = new OrchestratorCommand(
                401L,
                "ORCH.PAYROLL.RUN",
                "idem-payroll",
                "different-hash",
                "trace-payroll"
        );
        when(commandRepository.lockByScope(401L, "ORCH.PAYROLL.RUN", "idem-payroll"))
                .thenReturn(Optional.of(mismatched));

        OrchestratorIdempotencyService service = new OrchestratorIdempotencyService(
                commandRepository,
                companyContextService,
                new ObjectMapper(),
                new ResourcelessTransactionManager()
        );

        assertThatThrownBy(() -> service.start(
                "ORCH.PAYROLL.RUN",
                "idem-payroll",
                Map.of("period", "2026-02"),
                () -> "trace-ignored"
        )).isInstanceOf(ApplicationException.class)
                .satisfies(ex -> {
                    ApplicationException appEx = (ApplicationException) ex;
                    assertThat(appEx.getErrorCode()).isEqualTo(ErrorCode.CONCURRENCY_CONFLICT);
                    assertThat(appEx.getDetails()).containsEntry("commandName", "ORCH.PAYROLL.RUN");
                    assertThat(appEx.getDetails()).containsEntry("idempotencyKey", "idem-payroll");
                });

        when(commandRepository.lockByScope(401L, "ORCH.PAYROLL.RUN", "idem-missing"))
                .thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.start(
                "ORCH.PAYROLL.RUN",
                "idem-missing",
                Map.of("period", "2026-02"),
                () -> "trace-ignored"
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("command row not found");
    }

    @Test
    void start_requires_key_and_mark_helpers_are_null_safe() {
        OrchestratorCommandRepository commandRepository = mock(OrchestratorCommandRepository.class);
        CompanyContextService companyContextService = mock(CompanyContextService.class);
        when(companyContextService.requireCurrentCompany()).thenReturn(company(501L));

        OrchestratorIdempotencyService service = new OrchestratorIdempotencyService(
                commandRepository,
                companyContextService,
                new ObjectMapper(),
                new ResourcelessTransactionManager()
        );

        assertThatThrownBy(() -> service.start("ORCH.ORDER.APPROVE", "   ", Map.of(), () -> "trace"))
                .isInstanceOf(ApplicationException.class)
                .satisfies(ex -> assertThat(((ApplicationException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.VALIDATION_MISSING_REQUIRED_FIELD));

        String overlongKey = "k".repeat(256);
        assertThatThrownBy(() -> service.start("ORCH.ORDER.APPROVE", overlongKey, Map.of(), () -> "trace"))
                .isInstanceOf(ApplicationException.class)
                .satisfies(ex -> {
                    ApplicationException appEx = (ApplicationException) ex;
                    assertThat(appEx.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_INVALID_INPUT);
                    assertThat(appEx.getDetails())
                            .containsEntry("maxLength", 255)
                            .containsEntry("actualLength", 256);
                });

        OrchestratorCommand command = new OrchestratorCommand(
                501L,
                "ORCH.ORDER.APPROVE",
                "idem-order",
                "hash",
                "trace-order"
        );

        service.markFailed(command, new RuntimeException(""));
        assertThat(command.getStatus()).isEqualTo(OrchestratorCommand.Status.FAILED);
        assertThat(command.getLastError()).isEqualTo("RuntimeException");

        service.markFailed(command, null);
        assertThat(command.getStatus()).isEqualTo(OrchestratorCommand.Status.FAILED);
        assertThat(command.getLastError()).isEqualTo("FAILED");

        service.markSuccess(command);
        assertThat(command.getStatus()).isEqualTo(OrchestratorCommand.Status.SUCCESS);
        assertThat(command.getLastError()).isNull();

        service.markSuccess(null);
        service.markFailed(null, new RuntimeException("ignored"));
    }

    @Test
    void mark_helpers_persist_status_transitions_for_tracked_command() {
        OrchestratorCommandRepository commandRepository = mock(OrchestratorCommandRepository.class);
        CompanyContextService companyContextService = mock(CompanyContextService.class);
        when(companyContextService.requireCurrentCompany()).thenReturn(company(701L));

        OrchestratorIdempotencyService service = new OrchestratorIdempotencyService(
                commandRepository,
                companyContextService,
                new ObjectMapper(),
                new ResourcelessTransactionManager()
        );

        OrchestratorCommand detached = new OrchestratorCommand(
                701L,
                "ORCH.ORDER.APPROVE",
                "idem-order",
                "hash",
                "trace-order"
        );
        UUID commandId = UUID.randomUUID();
        ReflectionTestUtils.setField(detached, "id", commandId);

        OrchestratorCommand managed = new OrchestratorCommand(
                701L,
                "ORCH.ORDER.APPROVE",
                "idem-order",
                "hash",
                "trace-order"
        );
        ReflectionTestUtils.setField(managed, "id", commandId);

        when(commandRepository.findById(commandId)).thenReturn(Optional.of(managed));
        when(commandRepository.saveAndFlush(managed)).thenReturn(managed);

        service.markFailed(detached, new RuntimeException("broker timeout"));
        assertThat(detached.getStatus()).isEqualTo(OrchestratorCommand.Status.FAILED);
        assertThat(detached.getLastError()).isEqualTo("broker timeout");
        assertThat(managed.getStatus()).isEqualTo(OrchestratorCommand.Status.FAILED);
        assertThat(managed.getLastError()).isEqualTo("broker timeout");

        service.markSuccess(detached);
        assertThat(detached.getStatus()).isEqualTo(OrchestratorCommand.Status.SUCCESS);
        assertThat(detached.getLastError()).isNull();
        assertThat(managed.getStatus()).isEqualTo(OrchestratorCommand.Status.SUCCESS);
        assertThat(managed.getLastError()).isNull();

        verify(commandRepository, times(2)).findById(commandId);
        verify(commandRepository, times(2)).saveAndFlush(managed);
    }

    @Test
    void mark_success_defers_persistence_until_after_commit_when_transaction_active() {
        OrchestratorCommandRepository commandRepository = mock(OrchestratorCommandRepository.class);
        CompanyContextService companyContextService = mock(CompanyContextService.class);
        when(companyContextService.requireCurrentCompany()).thenReturn(company(702L));

        OrchestratorIdempotencyService service = new OrchestratorIdempotencyService(
                commandRepository,
                companyContextService,
                new ObjectMapper(),
                new ResourcelessTransactionManager()
        );

        OrchestratorCommand detached = new OrchestratorCommand(
                702L,
                "ORCH.ORDER.APPROVE",
                "idem-order",
                "hash",
                "trace-order"
        );
        UUID commandId = UUID.randomUUID();
        ReflectionTestUtils.setField(detached, "id", commandId);

        OrchestratorCommand managed = new OrchestratorCommand(
                702L,
                "ORCH.ORDER.APPROVE",
                "idem-order",
                "hash",
                "trace-order"
        );
        ReflectionTestUtils.setField(managed, "id", commandId);

        when(commandRepository.findById(commandId)).thenReturn(Optional.of(managed));
        when(commandRepository.saveAndFlush(managed)).thenReturn(managed);

        TransactionTemplate transactionTemplate = new TransactionTemplate(new ResourcelessTransactionManager());
        transactionTemplate.executeWithoutResult(status -> {
            service.markSuccess(detached);
            verify(commandRepository, never()).saveAndFlush(managed);
        });

        assertThat(managed.getStatus()).isEqualTo(OrchestratorCommand.Status.SUCCESS);
        assertThat(managed.getLastError()).isNull();
        verify(commandRepository, times(1)).findById(commandId);
        verify(commandRepository, times(1)).saveAndFlush(managed);
    }

    @Test
    void mark_success_marks_failed_when_outer_transaction_rolls_back() {
        OrchestratorCommandRepository commandRepository = mock(OrchestratorCommandRepository.class);
        CompanyContextService companyContextService = mock(CompanyContextService.class);
        when(companyContextService.requireCurrentCompany()).thenReturn(company(703L));

        OrchestratorIdempotencyService service = new OrchestratorIdempotencyService(
                commandRepository,
                companyContextService,
                new ObjectMapper(),
                new ResourcelessTransactionManager()
        );

        OrchestratorCommand detached = new OrchestratorCommand(
                703L,
                "ORCH.ORDER.APPROVE",
                "idem-order",
                "hash",
                "trace-order"
        );
        UUID commandId = UUID.randomUUID();
        ReflectionTestUtils.setField(detached, "id", commandId);

        OrchestratorCommand managed = new OrchestratorCommand(
                703L,
                "ORCH.ORDER.APPROVE",
                "idem-order",
                "hash",
                "trace-order"
        );
        ReflectionTestUtils.setField(managed, "id", commandId);

        when(commandRepository.findById(commandId)).thenReturn(Optional.of(managed));
        when(commandRepository.saveAndFlush(managed)).thenReturn(managed);

        TransactionTemplate transactionTemplate = new TransactionTemplate(new ResourcelessTransactionManager());
        transactionTemplate.executeWithoutResult(status -> {
            service.markSuccess(detached);
            status.setRollbackOnly();
            verify(commandRepository, never()).saveAndFlush(managed);
        });

        assertThat(managed.getStatus()).isEqualTo(OrchestratorCommand.Status.FAILED);
        assertThat(managed.getLastError()).isEqualTo("Outer transaction rolled back before commit");
        verify(commandRepository, times(1)).findById(commandId);
        verify(commandRepository, times(1)).saveAndFlush(managed);
    }

    @Test
    void mark_helpers_fail_closed_when_tracked_row_missing() {
        OrchestratorCommandRepository commandRepository = mock(OrchestratorCommandRepository.class);
        CompanyContextService companyContextService = mock(CompanyContextService.class);
        when(companyContextService.requireCurrentCompany()).thenReturn(company(801L));

        OrchestratorIdempotencyService service = new OrchestratorIdempotencyService(
                commandRepository,
                companyContextService,
                new ObjectMapper(),
                new ResourcelessTransactionManager()
        );

        OrchestratorCommand detached = new OrchestratorCommand(
                801L,
                "ORCH.ORDER.APPROVE",
                "idem-order",
                "hash",
                "trace-order"
        );
        UUID commandId = UUID.randomUUID();
        ReflectionTestUtils.setField(detached, "id", commandId);

        when(commandRepository.findById(commandId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.markSuccess(detached))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("command row not found");
    }

    @Test
    void start_fails_closed_when_payload_cannot_be_serialized_deterministically() {
        OrchestratorCommandRepository commandRepository = mock(OrchestratorCommandRepository.class);
        CompanyContextService companyContextService = mock(CompanyContextService.class);
        when(companyContextService.requireCurrentCompany()).thenReturn(company(601L));

        ObjectMapper failingMapper = new ObjectMapper() {
            @Override
            public ObjectMapper copy() {
                return this;
            }

            @Override
            public String writeValueAsString(Object value) throws com.fasterxml.jackson.core.JsonProcessingException {
                throw new com.fasterxml.jackson.core.JsonProcessingException("serialize fail") {
                };
            }
        };

        OrchestratorIdempotencyService service = new OrchestratorIdempotencyService(
                commandRepository,
                companyContextService,
                failingMapper,
                new ResourcelessTransactionManager()
        );

        Object payload = new Object() {
            @Override
            public String toString() {
                return "fallback-payload";
            }
        };

        assertThatThrownBy(() -> service.start(
                "ORCH.ORDER.APPROVE",
                "idem-fallback",
                payload,
                () -> "trace-fallback"
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unable to deterministically hash orchestrator payload");
        verify(commandRepository, never()).reserveScope(any(), any(), any(), any(), any());
        verify(commandRepository, never()).lockByScope(any(), any(), any());
    }

    private Company company(Long id) {
        Company company = new Company();
        company.setCode("COMP-" + id);
        company.setName("Company " + id);
        company.setTimezone("UTC");
        ReflectionTestUtils.setField(company, "id", id);
        return company;
    }

    private String expectedHash(Long companyId, String commandName, Object payload) {
        try {
            ObjectMapper mapper = new ObjectMapper()
                    .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
                    .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
            String json = mapper.writeValueAsString(payload);
            return sha256Hex(companyId + "|" + commandName + "|" + json);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to compute expected hash", ex);
        }
    }

    private String sha256Hex(String material) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(material.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to hash material", ex);
        }
    }
}
