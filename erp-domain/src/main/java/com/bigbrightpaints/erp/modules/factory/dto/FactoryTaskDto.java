package com.bigbrightpaints.erp.modules.factory.dto;

import java.time.LocalDate;
import java.util.UUID;

public record FactoryTaskDto(Long id,
                             UUID publicId,
                             String title,
                             String description,
                             String assignee,
                             String status,
                             LocalDate dueDate) {}
