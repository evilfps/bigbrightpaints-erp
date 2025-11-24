package com.bigbrightpaints.erp.modules.accounting.controller;

import com.bigbrightpaints.erp.modules.accounting.dto.PayrollBatchPaymentRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.PayrollBatchPaymentResponse;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingService;
import com.bigbrightpaints.erp.shared.dto.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/accounting/payroll")
public class PayrollController {

    private final AccountingService accountingService;

    public PayrollController(AccountingService accountingService) {
        this.accountingService = accountingService;
    }

    @PostMapping("/payments/batch")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
    public ResponseEntity<ApiResponse<PayrollBatchPaymentResponse>> processBatchPayment(
            @Valid @RequestBody PayrollBatchPaymentRequest request) {
        PayrollBatchPaymentResponse response = accountingService.processPayrollBatchPayment(request);
        return ResponseEntity.ok(ApiResponse.success("Payroll batch posted", response));
    }
}
