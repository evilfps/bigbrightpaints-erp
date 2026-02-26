package com.bigbrightpaints.erp.orchestrator.service;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.orchestrator.repository.OrchestratorCommand;
import com.bigbrightpaints.erp.orchestrator.repository.OrchestratorCommandRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

@Service
public class OrchestratorIdempotencyService {

    private static final Logger log = LoggerFactory.getLogger(OrchestratorIdempotencyService.class);
    private static final String ROLLBACK_FAILURE_MESSAGE = "Outer transaction rolled back before commit";

    public record CommandLease(String traceId, OrchestratorCommand command, boolean shouldExecute) {
    }

    private final OrchestratorCommandRepository commandRepository;
    private final CompanyContextService companyContextService;
    private final ObjectMapper hashMapper;
    private final TransactionTemplate txTemplate;

    public OrchestratorIdempotencyService(OrchestratorCommandRepository commandRepository,
                                         CompanyContextService companyContextService,
                                         ObjectMapper objectMapper,
                                         PlatformTransactionManager txManager) {
        this.commandRepository = commandRepository;
        this.companyContextService = companyContextService;
        this.hashMapper = objectMapper.copy()
                .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
                .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
        TransactionTemplate template = new TransactionTemplate(txManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.txTemplate = template;
    }

    public CommandLease start(String commandName,
                              String idempotencyKey,
                              Object requestPayload,
                              Supplier<String> traceIdSupplier) {
        Company company = companyContextService.requireCurrentCompany();
        String key = CorrelationIdentifierSanitizer.sanitizeRequiredIdempotencyKey(idempotencyKey);
        String requestHash = hashRequest(company.getId(), commandName, requestPayload);

        return txTemplate.execute(status -> {
            String traceCandidate = traceIdSupplier != null ? traceIdSupplier.get() : null;
            String traceId = CorrelationIdentifierSanitizer.sanitizeTraceIdOrFallback(
                    traceCandidate,
                    () -> UUID.randomUUID().toString());
            boolean reserved = commandRepository.reserveScope(
                    company.getId(),
                    commandName,
                    key,
                    requestHash,
                    traceId) > 0;

            OrchestratorCommand existing = commandRepository.lockByScope(company.getId(), commandName, key)
                    .orElseThrow(() -> new IllegalStateException("Idempotency reservation exists but command row not found"));

            if (reserved) {
                return new CommandLease(existing.getTraceId(), existing, true);
            }

            if (!requestHash.equals(existing.getRequestHash())) {
                throw new ApplicationException(
                        ErrorCode.CONCURRENCY_CONFLICT,
                        "Idempotency key already used with different payload"
                ).withDetail("commandName", commandName)
                        .withDetail("idempotencyKey", key);
            }

            if (existing.getStatus() == OrchestratorCommand.Status.FAILED) {
                existing.markRetry();
                commandRepository.save(existing);
                return new CommandLease(existing.getTraceId(), existing, true);
            }

            return new CommandLease(existing.getTraceId(), existing, false);
        });
    }

    public void markSuccess(OrchestratorCommand command) {
        if (command == null) {
            return;
        }
        command.markSuccess();
        UUID commandId = command.getId();
        if (commandId == null) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()
                && TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    safelyPersistStatus(commandId, OrchestratorCommand::markSuccess, "SUCCESS");
                }

                @Override
                public void afterCompletion(int status) {
                    if (status == TransactionSynchronization.STATUS_ROLLED_BACK) {
                        safelyPersistStatus(commandId, managed -> managed.markFailed(ROLLBACK_FAILURE_MESSAGE), "FAILED");
                    }
                }
            });
            return;
        }
        persistStatusUpdate(commandId, OrchestratorCommand::markSuccess);
    }

    public void markFailed(OrchestratorCommand command, RuntimeException ex) {
        if (command == null) {
            return;
        }
        String message = ex != null ? ex.getMessage() : null;
        if (!StringUtils.hasText(message)) {
            message = ex != null ? ex.getClass().getSimpleName() : "FAILED";
        }
        command.markFailed(message);
        String resolvedMessage = message;
        persistStatusUpdate(command.getId(), managed -> managed.markFailed(resolvedMessage));
    }

    private void persistStatusUpdate(UUID commandId, java.util.function.Consumer<OrchestratorCommand> mutator) {
        if (commandId == null || mutator == null) {
            return;
        }
        txTemplate.execute(status -> {
            OrchestratorCommand managed = commandRepository.findById(commandId)
                    .orElseThrow(() -> new IllegalStateException("Orchestrator command row not found for id " + commandId));
            mutator.accept(managed);
            commandRepository.saveAndFlush(managed);
            return null;
        });
    }

    private void safelyPersistStatus(UUID commandId,
                                     java.util.function.Consumer<OrchestratorCommand> mutator,
                                     String targetStatus) {
        try {
            persistStatusUpdate(commandId, mutator);
        } catch (RuntimeException ex) {
            log.error("Failed to persist orchestrator command {} transition to {}", commandId, targetStatus, ex);
        }
    }

    private String hashRequest(Long companyId, String commandName, Object requestPayload) {
        String json = serializeRequestPayload(commandName, requestPayload);
        String material = companyId + "|" + commandName + "|" + json;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(material.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception ex) {
            return Integer.toHexString(material.hashCode());
        }
    }

    private String serializeRequestPayload(String commandName, Object requestPayload) {
        try {
            return hashMapper.writeValueAsString(requestPayload);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException(
                    "Unable to deterministically hash orchestrator payload for command " + commandName,
                    ex);
        }
    }
}
