package com.bigbrightpaints.erp.modules.factory.dto;

import jakarta.validation.constraints.NotBlank;

import java.time.LocalDate;

public record FactoryTaskRequest(
        @NotBlank String title,
        String description,
        String assignee,
        String status,
        LocalDate dueDate
) {}
