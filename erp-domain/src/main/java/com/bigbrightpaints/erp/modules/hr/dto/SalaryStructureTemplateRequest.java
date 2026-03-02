package com.bigbrightpaints.erp.modules.hr.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

public record SalaryStructureTemplateRequest(
        @NotBlank @Size(max = 64) String code,
        @NotBlank @Size(max = 128) String name,
        @Size(max = 1000) String description,
        @DecimalMin(value = "0.00") BigDecimal basicPay,
        @DecimalMin(value = "0.00") BigDecimal hra,
        @DecimalMin(value = "0.00") BigDecimal da,
        @DecimalMin(value = "0.00") BigDecimal specialAllowance,
        @DecimalMin(value = "0.00") BigDecimal employeePfRate,
        @DecimalMin(value = "0.00") BigDecimal employeeEsiRate,
        Boolean active
) {
}
