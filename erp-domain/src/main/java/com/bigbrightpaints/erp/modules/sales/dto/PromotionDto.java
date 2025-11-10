package com.bigbrightpaints.erp.modules.sales.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record PromotionDto(Long id,
                           UUID publicId,
                           String name,
                           String description,
                           String discountType,
                           BigDecimal discountValue,
                           LocalDate startDate,
                           LocalDate endDate,
                           String status) {}
