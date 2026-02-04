package com.bigbrightpaints.erp.modules.purchasing.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.List;

public record GoodsReceiptRequest(
        @NotNull Long purchaseOrderId,
        @NotBlank String receiptNumber,
        @NotNull LocalDate receiptDate,
        String memo,
        String idempotencyKey,
        @NotEmpty List<@Valid GoodsReceiptLineRequest> lines
) {}
