package com.bigbrightpaints.erp.modules.sales.controller;

import java.security.Principal;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.bigbrightpaints.erp.core.security.PortalRoleActionMatrix;
import com.bigbrightpaints.erp.modules.sales.dto.CreditLimitOverrideDecisionRequest;
import com.bigbrightpaints.erp.modules.sales.dto.CreditLimitOverrideRequestCreateRequest;
import com.bigbrightpaints.erp.modules.sales.dto.CreditLimitOverrideRequestDto;
import com.bigbrightpaints.erp.modules.sales.service.CreditLimitOverrideService;
import com.bigbrightpaints.erp.shared.dto.ApiResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/credit/override-requests")
public class CreditLimitOverrideController {

  private final CreditLimitOverrideService creditLimitOverrideService;

  public CreditLimitOverrideController(CreditLimitOverrideService creditLimitOverrideService) {
    this.creditLimitOverrideService = creditLimitOverrideService;
  }

  @PostMapping
  @PreAuthorize(PortalRoleActionMatrix.ADMIN_FACTORY_SALES)
  @Operation(summary = "Create credit limit override request")
  @ApiResponses({
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "201",
        description = "Override request created"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "400",
        description = "Invalid override request payload"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "403",
        description = "Forbidden")
  })
  public ResponseEntity<ApiResponse<CreditLimitOverrideRequestDto>> createRequest(
      @Valid @RequestBody CreditLimitOverrideRequestCreateRequest request, Principal principal) {
    String requestedBy = principal != null ? principal.getName() : "system";
    CreditLimitOverrideRequestDto response =
        creditLimitOverrideService.createRequest(request, requestedBy);
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(ApiResponse.success("Override request created", response));
  }

  @GetMapping
  @PreAuthorize(PortalRoleActionMatrix.ADMIN_OR_ACCOUNTING)
  public ResponseEntity<ApiResponse<List<CreditLimitOverrideRequestDto>>> listRequests(
      @RequestParam(required = false) String status) {
    List<CreditLimitOverrideRequestDto> requests = creditLimitOverrideService.listRequests(status);
    return ResponseEntity.ok(ApiResponse.success(requests));
  }

  @PostMapping("/{id}/approve")
  @PreAuthorize(PortalRoleActionMatrix.ADMIN_OR_ACCOUNTING)
  public ResponseEntity<ApiResponse<CreditLimitOverrideRequestDto>> approveRequest(
      @PathVariable Long id,
      @RequestBody(required = false) CreditLimitOverrideDecisionRequest request,
      Principal principal) {
    String reviewedBy = principal != null ? principal.getName() : "system";
    CreditLimitOverrideRequestDto response =
        creditLimitOverrideService.approveRequest(id, request, reviewedBy);
    return ResponseEntity.ok(ApiResponse.success("Override request approved", response));
  }

  @PostMapping("/{id}/reject")
  @PreAuthorize(PortalRoleActionMatrix.ADMIN_OR_ACCOUNTING)
  public ResponseEntity<ApiResponse<CreditLimitOverrideRequestDto>> rejectRequest(
      @PathVariable Long id,
      @RequestBody(required = false) CreditLimitOverrideDecisionRequest request,
      Principal principal) {
    String reviewedBy = principal != null ? principal.getName() : "system";
    CreditLimitOverrideRequestDto response =
        creditLimitOverrideService.rejectRequest(id, request, reviewedBy);
    return ResponseEntity.ok(ApiResponse.success("Override request rejected", response));
  }
}
