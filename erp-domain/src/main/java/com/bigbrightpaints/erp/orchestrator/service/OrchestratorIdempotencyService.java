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
import java.util.function.Supplier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class OrchestratorIdempotencyService {

    public record CommandLease(String traceId, OrchestratorCommand command, boolean shouldExecute) {
    }

    private final OrchestratorCommandRepository commandRepository;
    private final CompanyContextService companyContextService;
    private final ObjectMapper hashMapper;

    public OrchestratorIdempotencyService(OrchestratorCommandRepository commandRepository,
                                         CompanyContextService companyContextService,
                                         ObjectMapper objectMapper) {
        this.commandRepository = commandRepository;
        this.companyContextService = companyContextService;
        this.hashMapper = objectMapper.copy()
                .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
                .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    }

    public CommandLease start(String commandName,
                              String idempotencyKey,
                              Object requestPayload,
                              Supplier<String> traceIdSupplier) {
        Company company = companyContextService.requireCurrentCompany();
        String key = normalizeKey(idempotencyKey);
        String requestHash = hashRequest(company.getId(), commandName, requestPayload);

        try {
            String traceId = traceIdSupplier.get();
            OrchestratorCommand command = new OrchestratorCommand(company.getId(), commandName, key, requestHash, traceId);
            commandRepository.saveAndFlush(command);
            return new CommandLease(traceId, command, true);
        } catch (DataIntegrityViolationException ex) {
            OrchestratorCommand existing = commandRepository.lockByScope(company.getId(), commandName, key)
                    .orElseThrow(() -> new IllegalStateException("Idempotency reservation exists but command row not found"));

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
        }
    }

    public void markSuccess(OrchestratorCommand command) {
        if (command == null) {
            return;
        }
        command.markSuccess();
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
    }

    private String normalizeKey(String idempotencyKey) {
        if (!StringUtils.hasText(idempotencyKey)) {
            throw new ApplicationException(
                    ErrorCode.VALIDATION_MISSING_REQUIRED_FIELD,
                    "Idempotency key is required"
            );
        }
        return idempotencyKey.trim();
    }

    private String hashRequest(Long companyId, String commandName, Object requestPayload) {
        String json;
        try {
            json = hashMapper.writeValueAsString(requestPayload);
        } catch (JsonProcessingException ex) {
            json = String.valueOf(requestPayload);
        }
        String material = companyId + "|" + commandName + "|" + json;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(material.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception ex) {
            return Integer.toHexString(material.hashCode());
        }
    }
}
