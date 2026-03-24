package com.bigbrightpaints.erp.modules.sales.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.util.Objects;

public final class DealerPortalCreditLimitRequestCreateRequest {

    @NotNull
    @Positive
    private final BigDecimal amountRequested;
    private final String reason;

    @JsonCreator
    public DealerPortalCreditLimitRequestCreateRequest(@JsonProperty("amountRequested") BigDecimal amountRequested,
                                                       @JsonProperty("reason") String reason) {
        this.amountRequested = amountRequested;
        this.reason = reason;
    }

    public BigDecimal amountRequested() {
        return amountRequested;
    }

    public String reason() {
        return reason;
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
        if (!(other instanceof DealerPortalCreditLimitRequestCreateRequest that)) {
            return false;
        }
        return Objects.equals(amountRequested, that.amountRequested)
                && Objects.equals(reason, that.reason);
    }

    @Override
    public int hashCode() {
        return Objects.hash(amountRequested, reason);
    }

    @Override
    public String toString() {
        return "DealerPortalCreditLimitRequestCreateRequest[" +
                "amountRequested=" + amountRequested +
                ", reason=" + reason +
                ']';
    }
}
