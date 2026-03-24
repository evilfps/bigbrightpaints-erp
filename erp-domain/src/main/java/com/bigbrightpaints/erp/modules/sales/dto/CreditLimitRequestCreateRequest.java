package com.bigbrightpaints.erp.modules.sales.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.util.Objects;

public final class CreditLimitRequestCreateRequest {

    @NotNull
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    private final Long dealerId;
    @NotNull
    @Positive
    private final BigDecimal amountRequested;
    private final String reason;

    @JsonCreator
    public CreditLimitRequestCreateRequest(@JsonProperty("dealerId") Long dealerId,
                                           @JsonProperty("amountRequested") BigDecimal amountRequested,
                                           @JsonProperty("reason") String reason) {
        this.dealerId = dealerId;
        this.amountRequested = amountRequested;
        this.reason = reason;
    }

    public Long dealerId() {
        return dealerId;
    }

    public BigDecimal amountRequested() {
        return amountRequested;
    }

    public String reason() {
        return reason;
    }

    public Long getDealerId() {
        return dealerId;
    }

    public BigDecimal getAmountRequested() {
        return amountRequested;
    }

    public String getReason() {
        return reason;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof CreditLimitRequestCreateRequest that)) {
            return false;
        }
        return Objects.equals(dealerId, that.dealerId)
                && Objects.equals(amountRequested, that.amountRequested)
                && Objects.equals(reason, that.reason);
    }

    @Override
    public int hashCode() {
        return Objects.hash(dealerId, amountRequested, reason);
    }

    @Override
    public String toString() {
        return "CreditLimitRequestCreateRequest[" +
                "dealerId=" + dealerId +
                ", amountRequested=" + amountRequested +
                ", reason=" + reason +
                ']';
    }
}
