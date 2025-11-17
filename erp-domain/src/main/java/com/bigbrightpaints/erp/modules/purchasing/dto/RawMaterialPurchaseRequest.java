package com.bigbrightpaints.erp.modules.purchasing.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.List;

public record RawMaterialPurchaseRequest(
        @NotNull Long supplierId,
        @NotBlank String invoiceNumber,
        @NotNull LocalDate invoiceDate,
        String memo,
        @NotEmpty List<@Valid RawMaterialPurchaseLineRequest> lines
) {}
