package com.bigbrightpaints.erp.modules.sales.dto;

import com.bigbrightpaints.erp.modules.accounting.domain.GstRegistrationType;
import com.bigbrightpaints.erp.modules.sales.domain.DealerPaymentTerms;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;

public record CreateDealerRequest(
        @NotBlank String name,
        @NotBlank String companyName,
        @Email(message = "Provide a valid contact email")
        @NotBlank String contactEmail,
        @NotBlank String contactPhone,
        String address,
        @PositiveOrZero BigDecimal creditLimit,
        @Pattern(regexp = "^$|[0-9]{2}[A-Za-z0-9]{13}$", message = "GST number must be a valid 15-character GSTIN")
        String gstNumber,
        @Pattern(regexp = "^$|[A-Za-z0-9]{2}$", message = "State code must be exactly 2 characters")
        String stateCode,
        GstRegistrationType gstRegistrationType,
        DealerPaymentTerms paymentTerms,
        String region
) {

    public CreateDealerRequest(String name,
                               String companyName,
                               String contactEmail,
                               String contactPhone,
                               String address,
                               BigDecimal creditLimit) {
        this(name, companyName, contactEmail, contactPhone, address, creditLimit, null, null, null, null, null);
    }
}
