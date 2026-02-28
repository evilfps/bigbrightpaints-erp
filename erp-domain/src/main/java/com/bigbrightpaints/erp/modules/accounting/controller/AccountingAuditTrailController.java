package com.bigbrightpaints.erp.modules.accounting.controller;

import com.bigbrightpaints.erp.modules.accounting.dto.AccountingAuditTrailEntryDto;
import com.bigbrightpaints.erp.modules.accounting.service.AuditTrailQueryService;
import com.bigbrightpaints.erp.shared.dto.ApiResponse;
import com.bigbrightpaints.erp.shared.dto.PageResponse;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/accounting")
public class AccountingAuditTrailController {

    private final AuditTrailQueryService auditTrailQueryService;

    public AccountingAuditTrailController(AuditTrailQueryService auditTrailQueryService) {
        this.auditTrailQueryService = auditTrailQueryService;
    }

    @GetMapping("/audit-trail")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
    public ResponseEntity<ApiResponse<PageResponse<AccountingAuditTrailEntryDto>>> listAuditTrail(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String user,
            @RequestParam(required = false) String actionType,
            @RequestParam(required = false) String entityType,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return ResponseEntity.ok(ApiResponse.success(
                auditTrailQueryService.queryAuditTrail(from, to, user, actionType, entityType, page, size)));
    }
}
