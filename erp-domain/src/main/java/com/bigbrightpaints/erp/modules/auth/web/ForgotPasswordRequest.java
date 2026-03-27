package com.bigbrightpaints.erp.modules.auth.web;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record ForgotPasswordRequest(@Email @NotBlank String email, @NotBlank String companyCode) {}
