package com.bigbrightpaints.erp.modules.hr.dto;

import java.time.LocalDate;
import java.util.UUID;

public record PayrollRunDto(Long id,
                            UUID publicId,
                            LocalDate runDate,
                            String status,
                            String processedBy,
                            String notes) {}
