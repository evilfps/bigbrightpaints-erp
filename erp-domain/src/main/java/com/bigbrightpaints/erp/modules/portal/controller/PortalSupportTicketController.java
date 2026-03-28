package com.bigbrightpaints.erp.modules.portal.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.bigbrightpaints.erp.core.security.PortalRoleActionMatrix;
import com.bigbrightpaints.erp.modules.admin.dto.SupportTicketCreateRequest;
import com.bigbrightpaints.erp.modules.admin.dto.SupportTicketListResponse;
import com.bigbrightpaints.erp.modules.admin.dto.SupportTicketResponse;
import com.bigbrightpaints.erp.modules.admin.service.PortalSupportTicketService;
import com.bigbrightpaints.erp.shared.dto.ApiResponse;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/portal/support/tickets")
@PreAuthorize(PortalRoleActionMatrix.ADMIN_OR_ACCOUNTING)
public class PortalSupportTicketController {

  private final PortalSupportTicketService portalSupportTicketService;

  public PortalSupportTicketController(PortalSupportTicketService portalSupportTicketService) {
    this.portalSupportTicketService = portalSupportTicketService;
  }

  @PostMapping
  public ResponseEntity<ApiResponse<SupportTicketResponse>> create(
      @Valid @RequestBody SupportTicketCreateRequest request) {
    return ResponseEntity.ok(
        ApiResponse.success("Support ticket created", portalSupportTicketService.create(request)));
  }

  @GetMapping
  public ResponseEntity<ApiResponse<SupportTicketListResponse>> list() {
    return ResponseEntity.ok(
        ApiResponse.success(
            "Support tickets fetched",
            new SupportTicketListResponse(portalSupportTicketService.list())));
  }

  @GetMapping("/{ticketId}")
  public ResponseEntity<ApiResponse<SupportTicketResponse>> getById(@PathVariable Long ticketId) {
    return ResponseEntity.ok(
        ApiResponse.success(
            "Support ticket fetched", portalSupportTicketService.getById(ticketId)));
  }
}
