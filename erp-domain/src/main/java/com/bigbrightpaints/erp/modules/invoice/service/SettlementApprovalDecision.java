package com.bigbrightpaints.erp.modules.invoice.service;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public record SettlementApprovalDecision(
        String approvalId,
        String makerUserId,
        String checkerUserId,
        SettlementApprovalReasonCode reasonCode,
        Instant approvedAt,
        Map<String, String> auditMetadata
) {

    public SettlementApprovalDecision {
        approvalId = requireText(approvalId, "approvalId");
        makerUserId = requireText(makerUserId, "makerUserId");
        checkerUserId = requireText(checkerUserId, "checkerUserId");
        if (makerUserId.equalsIgnoreCase(checkerUserId)) {
            throw new IllegalArgumentException("Maker and checker must be different users");
        }
        reasonCode = Objects.requireNonNull(reasonCode, "reasonCode");
        approvedAt = Objects.requireNonNull(approvedAt, "approvedAt");
        auditMetadata = toImmutableAuditMetadata(
                approvalId,
                makerUserId,
                checkerUserId,
                reasonCode,
                approvedAt,
                auditMetadata);
    }

    public static SettlementApprovalDecision requireApproved(SettlementApprovalDecision approval, String context) {
        if (approval != null) {
            return approval;
        }
        String label = hasText(context) ? context.trim() : "Settlement";
        throw new IllegalStateException(label + " approval is required");
    }

    public Map<String, String> immutableAuditMetadata() {
        return auditMetadata;
    }

    private static Map<String, String> toImmutableAuditMetadata(String approvalId,
                                                                String makerUserId,
                                                                String checkerUserId,
                                                                SettlementApprovalReasonCode reasonCode,
                                                                Instant approvedAt,
                                                                Map<String, String> auditMetadata) {
        Map<String, String> metadata = normalizeCallerMetadata(auditMetadata);
        requireCallerAuditMetadata(metadata);
        metadata.put("approvalId", approvalId);
        metadata.put("makerUserId", makerUserId);
        metadata.put("checkerUserId", checkerUserId);
        metadata.put("reasonCode", reasonCode.name());
        metadata.put("approvedAt", approvedAt.toString());
        return Collections.unmodifiableMap(metadata);
    }

    private static Map<String, String> normalizeCallerMetadata(Map<String, String> auditMetadata) {
        if (auditMetadata == null) {
            throw new IllegalArgumentException("auditMetadata is required");
        }
        Map<String, String> metadata = new LinkedHashMap<>();
        auditMetadata.forEach((key, value) -> {
            if (hasText(key) && hasText(value)) {
                metadata.put(key.trim(), value.trim());
            }
        });
        return metadata;
    }

    private static void requireCallerAuditMetadata(Map<String, String> metadata) {
        if (!hasText(metadata.get("ticket"))) {
            throw new IllegalArgumentException("auditMetadata.ticket is required");
        }
        Set<String> sourceKeys = Set.of("approvalSource", "source", "sourceSystem", "workflowSource");
        boolean hasSource = sourceKeys.stream().anyMatch(key -> hasText(metadata.get(key)));
        if (!hasSource) {
            throw new IllegalArgumentException("auditMetadata approval source is required");
        }
    }

    private static String requireText(String value, String fieldName) {
        if (hasText(value)) {
            return value.trim();
        }
        throw new IllegalArgumentException(fieldName + " is required");
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
