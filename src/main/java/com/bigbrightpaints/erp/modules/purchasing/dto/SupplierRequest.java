package com.bigbrightpaints.erp.modules.purchasing.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

public record SupplierRequest(
        @NotBlank @Size(max = 64) String name,
        @Size(max = 64) String code,
        @Email String contactEmail,
        @Size(max = 32) String contactPhone,
        @Size(max = 512) String address,
        @DecimalMin(value = "0.00") BigDecimal creditLimit) {
}
