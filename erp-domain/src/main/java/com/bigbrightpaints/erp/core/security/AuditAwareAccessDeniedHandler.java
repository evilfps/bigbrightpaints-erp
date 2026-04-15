package com.bigbrightpaints.erp.core.security;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.bigbrightpaints.erp.core.audit.AuditEvent;
import com.bigbrightpaints.erp.core.audit.AuditService;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.shared.dto.ApiResponse;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
public class AuditAwareAccessDeniedHandler implements AccessDeniedHandler {

  public static final String ACCESS_DENIED_AUDIT_REASON = "security-access-denied-handler";

  private final AuditService auditService;
  private final ObjectMapper objectMapper;

  public AuditAwareAccessDeniedHandler(AuditService auditService, ObjectMapper objectMapper) {
    this.auditService = auditService;
    this.objectMapper = objectMapper;
  }

  @Override
  public void handle(
      HttpServletRequest request, HttpServletResponse response, AccessDeniedException ex)
      throws IOException {
    String traceId = UUID.randomUUID().toString();
    String userMessage =
        PortalRoleActionMatrix.resolveAccessDeniedMessage(
            org.springframework.security.core.context.SecurityContextHolder.getContext()
                .getAuthentication(),
            request);
    if (!StringUtils.hasText(userMessage)) {
      userMessage = "Access denied";
    }

    if (!AccessDeniedAuditMarker.isCurrentRequestAlreadyAudited(request)) {
      Map<String, String> metadata = new HashMap<>();
      metadata.put("reason", ACCESS_DENIED_AUDIT_REASON);
      metadata.put("traceId", traceId);
      metadata.put("deniedPath", request.getRequestURI());
      metadata.put("deniedMethod", request.getMethod());
      String actor = SecurityActorResolver.resolveActorWithSystemProcessFallback();
      metadata.put("actor", actor);
      String tenantScope = AccessDeniedAuditMarker.resolveTenantScope(request);
      if (StringUtils.hasText(tenantScope)) {
        metadata.put("tenantScope", tenantScope);
      }
      auditService.logAuthFailure(AuditEvent.ACCESS_DENIED, actor, tenantScope, metadata);
      AccessDeniedAuditMarker.markCurrentRequestAudited();
    }

    Map<String, Object> data = new HashMap<>();
    data.put("code", ErrorCode.AUTH_INSUFFICIENT_PERMISSIONS.getCode());
    data.put("message", userMessage);
    data.put("traceId", traceId);
    ApiResponse<Map<String, Object>> body = ApiResponse.failure(userMessage, data);

    response.setStatus(HttpServletResponse.SC_FORBIDDEN);
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    objectMapper.writeValue(response.getWriter(), body);
  }
}
