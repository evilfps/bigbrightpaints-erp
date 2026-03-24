package com.bigbrightpaints.erp.modules.sales.controller;

import com.bigbrightpaints.erp.core.security.PortalRoleActionMatrix;
import com.bigbrightpaints.erp.modules.sales.dto.CreditLimitRequestCreateRequest;
import com.bigbrightpaints.erp.modules.sales.dto.CreditLimitRequestDecisionRequest;
import com.bigbrightpaints.erp.modules.sales.dto.CreditLimitRequestDto;
import com.bigbrightpaints.erp.modules.sales.service.CreditLimitRequestService;
import com.bigbrightpaints.erp.shared.dto.ApiResponse;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/credit/limit-requests")
public class CreditLimitRequestController {

    private final CreditLimitRequestService creditLimitRequestService;

    public CreditLimitRequestController(CreditLimitRequestService creditLimitRequestService) {
        this.creditLimitRequestService = creditLimitRequestService;
    }

    @GetMapping
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_SALES')")
    public ResponseEntity<ApiResponse<List<CreditLimitRequestDto>>> listRequests() {
        return ResponseEntity.ok(ApiResponse.success(creditLimitRequestService.listRequests()));
    }

    @PostMapping
    @PreAuthorize("hasAnyAuthority('ROLE_SALES','ROLE_ADMIN')")
    public ResponseEntity<ApiResponse<CreditLimitRequestDto>> createRequest(
            @Valid @RequestBody CreditLimitRequestCreateRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                "Credit limit request created",
                creditLimitRequestService.createRequest(request)));
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize(PortalRoleActionMatrix.ADMIN_OR_ACCOUNTING)
    public ResponseEntity<ApiResponse<CreditLimitRequestDto>> approveRequest(
            @PathVariable Long id,
            @Valid @RequestBody CreditLimitRequestDecisionRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                "Credit limit request approved",
                creditLimitRequestService.approveRequest(id, request.reason())));
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize(PortalRoleActionMatrix.ADMIN_OR_ACCOUNTING)
    public ResponseEntity<ApiResponse<CreditLimitRequestDto>> rejectRequest(
            @PathVariable Long id,
            @Valid @RequestBody CreditLimitRequestDecisionRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                "Credit limit request rejected",
                creditLimitRequestService.rejectRequest(id, request.reason())));
    }
}
