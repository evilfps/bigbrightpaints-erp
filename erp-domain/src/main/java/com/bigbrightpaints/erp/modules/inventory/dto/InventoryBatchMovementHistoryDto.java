package com.bigbrightpaints.erp.modules.inventory.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record InventoryBatchMovementHistoryDto(
    String movementType, BigDecimal quantity, Instant timestamp) {}
