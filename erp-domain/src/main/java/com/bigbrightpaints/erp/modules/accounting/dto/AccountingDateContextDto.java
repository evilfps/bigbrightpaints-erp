package com.bigbrightpaints.erp.modules.accounting.dto;

import java.time.Instant;
import java.time.LocalDate;

public record AccountingDateContextDto(
    Long companyId, String companyCode, String timezone, LocalDate today, Instant now) {}
