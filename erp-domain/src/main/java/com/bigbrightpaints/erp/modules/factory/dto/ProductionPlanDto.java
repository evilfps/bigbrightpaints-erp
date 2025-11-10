package com.bigbrightpaints.erp.modules.factory.dto;

import java.time.LocalDate;
import java.util.UUID;

public record ProductionPlanDto(Long id,
                                UUID publicId,
                                String planNumber,
                                String productName,
                                double quantity,
                                LocalDate plannedDate,
                                String status,
                                String notes) {}
