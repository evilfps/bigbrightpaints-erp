package com.bigbrightpaints.erp.modules.sales.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public final class CreditLimitRequestDto {

    private final Long id;
    private final UUID publicId;
    private final String dealerName;
    private final BigDecimal amountRequested;
    private final String status;
    private final String reason;
    private final Instant createdAt;

    public CreditLimitRequestDto(Long id,
                                 UUID publicId,
                                 String dealerName,
                                 BigDecimal amountRequested,
                                 String status,
                                 String reason,
                                 Instant createdAt) {
        this.id = id;
        this.publicId = publicId;
        this.dealerName = dealerName;
        this.amountRequested = amountRequested;
        this.status = status;
        this.reason = reason;
        this.createdAt = createdAt;
    }

    public Long id() {
        return id;
    }

    public UUID publicId() {
        return publicId;
    }

    public String dealerName() {
        return dealerName;
    }

    public BigDecimal amountRequested() {
        return amountRequested;
    }

    public String status() {
        return status;
    }

    public String reason() {
        return reason;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Long getId() {
        return id;
    }

    public UUID getPublicId() {
        return publicId;
    }

    public String getDealerName() {
        return dealerName;
    }

    public BigDecimal getAmountRequested() {
        return amountRequested;
    }

    public String getStatus() {
        return status;
    }

    public String getReason() {
        return reason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof CreditLimitRequestDto that)) {
            return false;
        }
        return Objects.equals(id, that.id)
                && Objects.equals(publicId, that.publicId)
                && Objects.equals(dealerName, that.dealerName)
                && Objects.equals(amountRequested, that.amountRequested)
                && Objects.equals(status, that.status)
                && Objects.equals(reason, that.reason)
                && Objects.equals(createdAt, that.createdAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, publicId, dealerName, amountRequested, status, reason, createdAt);
    }

    @Override
    public String toString() {
        return "CreditLimitRequestDto[" +
                "id=" + id +
                ", publicId=" + publicId +
                ", dealerName=" + dealerName +
                ", amountRequested=" + amountRequested +
                ", status=" + status +
                ", reason=" + reason +
                ", createdAt=" + createdAt +
                ']';
    }
}
