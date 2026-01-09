package com.bigbrightpaints.erp.modules.accounting.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record OnboardingPartnerOpeningBalanceRequest(
        @NotBlank String referenceNumber,
        LocalDate entryDate,
        @NotNull Long offsetAccountId,
        String memo,
        @NotEmpty List<@Valid PartnerLine> lines
) {
    public record PartnerLine(
            Long partnerId,
            String partnerCode,
            @NotNull BigDecimal amount,
            String memo
    ) {}
}
