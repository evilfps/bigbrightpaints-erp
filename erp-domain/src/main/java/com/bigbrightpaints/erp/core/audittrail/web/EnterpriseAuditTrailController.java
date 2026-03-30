package com.bigbrightpaints.erp.core.audittrail.web;

import java.time.LocalDate;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.bigbrightpaints.erp.core.audittrail.AuditActionEventStatus;
import com.bigbrightpaints.erp.core.audittrail.EnterpriseAuditTrailService;
import com.bigbrightpaints.erp.modules.auth.domain.UserPrincipal;
import com.bigbrightpaints.erp.shared.dto.ApiResponse;
import com.bigbrightpaints.erp.shared.dto.PageResponse;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/audit")
public class EnterpriseAuditTrailController {

  private final EnterpriseAuditTrailService enterpriseAuditTrailService;

  public EnterpriseAuditTrailController(EnterpriseAuditTrailService enterpriseAuditTrailService) {
    this.enterpriseAuditTrailService = enterpriseAuditTrailService;
  }

  @PostMapping("/ml-events")
  @PreAuthorize("isAuthenticated()")
  public ResponseEntity<ApiResponse<MlAuditIngestResponse>> ingestMlEvents(
      @AuthenticationPrincipal UserPrincipal principal,
      @Valid @RequestBody AuditEventIngestRequest request,
      HttpServletRequest httpServletRequest) {
    MlAuditIngestResponse response =
        enterpriseAuditTrailService.ingestMlInteractions(
            principal != null ? principal.getUser() : null, request.events(), httpServletRequest);
    return ResponseEntity.ok(ApiResponse.success("ML events recorded", response));
  }

  @GetMapping("/ml-events")
  @PreAuthorize("hasAuthority('ROLE_ADMIN')")
  public ResponseEntity<ApiResponse<PageResponse<MlInteractionEventResponse>>> mlEvents(
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
      @RequestParam(required = false) String module,
      @RequestParam(required = false) String action,
      @RequestParam(required = false) AuditActionEventStatus status,
      @RequestParam(required = false) Long actorUserId,
      @RequestParam(required = false) String actorIdentifier,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    PageResponse<MlInteractionEventResponse> result =
        enterpriseAuditTrailService.queryMlEvents(
            from, to, module, action, status, actorUserId, actorIdentifier, page, size);
    return ResponseEntity.ok(ApiResponse.success(result));
  }
}
