package com.bigbrightpaints.erp.modules.sales.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.bigbrightpaints.erp.core.security.PortalRoleActionMatrix;
import com.bigbrightpaints.erp.modules.sales.domain.Dealer;
import com.bigbrightpaints.erp.modules.sales.dto.CreditLimitRequestCreateRequest;
import com.bigbrightpaints.erp.modules.sales.dto.CreditLimitRequestDecisionRequest;
import com.bigbrightpaints.erp.modules.sales.dto.CreditLimitRequestDto;
import com.bigbrightpaints.erp.modules.sales.service.CreditLimitRequestService;
import com.bigbrightpaints.erp.modules.sales.service.DealerPortalService;
import com.bigbrightpaints.erp.shared.dto.ApiResponse;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/credit/limit-requests")
public class CreditLimitRequestController {

  private final CreditLimitRequestService creditLimitRequestService;
  private final DealerPortalService dealerPortalService;

  public CreditLimitRequestController(
      CreditLimitRequestService creditLimitRequestService,
      DealerPortalService dealerPortalService) {
    this.creditLimitRequestService = creditLimitRequestService;
    this.dealerPortalService = dealerPortalService;
  }

  @GetMapping
  @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_SALES')")
  public ResponseEntity<ApiResponse<List<CreditLimitRequestDto>>> listRequests() {
    return ResponseEntity.ok(ApiResponse.success(creditLimitRequestService.listRequests()));
  }

  @PostMapping
  @PreAuthorize("hasAnyAuthority('ROLE_SALES','ROLE_ADMIN','ROLE_DEALER')")
  public ResponseEntity<ApiResponse<CreditLimitRequestDto>> createRequest(
      @Valid @RequestBody CreditLimitRequestCreateRequest request) {
    CreditLimitRequestDto response;
    if (dealerPortalService.isDealerUser()) {
      Dealer dealer = dealerPortalService.getCurrentDealer();
      if (dealerPortalService.isFinanceReadOnlyDealer(dealer)) {
        throw new AccessDeniedException(
            "Dealer portal access is limited to finance read-only endpoints for non-active"
                + " dealers");
      }
      Long dealerId = dealer.getId();
      DealerPortalService.RequesterIdentity requesterIdentity =
          dealerPortalService.getCurrentRequesterIdentity();
      response =
          creditLimitRequestService.createRequest(
              new CreditLimitRequestCreateRequest(
                  dealerId, request.amountRequested(), request.reason()),
              requesterIdentity.userId(),
              requesterIdentity.email());
    } else {
      response = creditLimitRequestService.createRequest(request);
    }
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(ApiResponse.success("Credit limit request created", response));
  }

  @PostMapping("/{id}/approve")
  @PreAuthorize(PortalRoleActionMatrix.ADMIN_OR_ACCOUNTING)
  public ResponseEntity<ApiResponse<CreditLimitRequestDto>> approveRequest(
      @PathVariable Long id, @Valid @RequestBody CreditLimitRequestDecisionRequest request) {
    return ResponseEntity.ok(
        ApiResponse.success(
            "Credit limit request approved",
            creditLimitRequestService.approveRequest(id, request.reason())));
  }

  @PostMapping("/{id}/reject")
  @PreAuthorize(PortalRoleActionMatrix.ADMIN_OR_ACCOUNTING)
  public ResponseEntity<ApiResponse<CreditLimitRequestDto>> rejectRequest(
      @PathVariable Long id, @Valid @RequestBody CreditLimitRequestDecisionRequest request) {
    return ResponseEntity.ok(
        ApiResponse.success(
            "Credit limit request rejected",
            creditLimitRequestService.rejectRequest(id, request.reason())));
  }
}
