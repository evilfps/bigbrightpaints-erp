package com.bigbrightpaints.erp.modules.admin.service;

import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.bigbrightpaints.erp.core.config.SystemSettingsService;
import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.core.security.SecurityActorResolver;
import com.bigbrightpaints.erp.core.util.CompanyTime;
import com.bigbrightpaints.erp.core.validation.ValidationUtils;
import com.bigbrightpaints.erp.modules.admin.domain.ExportRequest;
import com.bigbrightpaints.erp.modules.admin.domain.ExportRequestRepository;
import com.bigbrightpaints.erp.modules.admin.dto.ExportApprovalStatus;
import com.bigbrightpaints.erp.modules.admin.dto.ExportRequestCreateRequest;
import com.bigbrightpaints.erp.modules.admin.dto.ExportRequestDownloadResponse;
import com.bigbrightpaints.erp.modules.admin.dto.ExportRequestDto;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccount;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccountRepository;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;

@Service
public class ExportApprovalService {

  private final CompanyContextService companyContextService;
  private final UserAccountRepository userAccountRepository;
  private final ExportRequestRepository exportRequestRepository;
  private final SystemSettingsService systemSettingsService;

  public ExportApprovalService(
      CompanyContextService companyContextService,
      UserAccountRepository userAccountRepository,
      ExportRequestRepository exportRequestRepository,
      SystemSettingsService systemSettingsService) {
    this.companyContextService = companyContextService;
    this.userAccountRepository = userAccountRepository;
    this.exportRequestRepository = exportRequestRepository;
    this.systemSettingsService = systemSettingsService;
  }

  public boolean isApprovalRequired() {
    return systemSettingsService.isExportApprovalRequired();
  }

  @Transactional
  public ExportRequestDto createRequest(ExportRequestCreateRequest request) {
    Company company = companyContextService.requireCurrentCompany();
    UserAccount actor = resolveActor(company);

    String reportType = ValidationUtils.requireNotBlank(request.reportType(), "reportType");
    String parameters = normalizeParameters(request.parameters());

    ExportRequest exportRequest = new ExportRequest();
    exportRequest.setCompany(company);
    exportRequest.setUserId(actor.getId());
    exportRequest.setReportType(reportType.toUpperCase(Locale.ROOT));
    exportRequest.setParameters(parameters);
    exportRequest.setStatus(ExportApprovalStatus.PENDING);

    ExportRequest saved = exportRequestRepository.save(exportRequest);
    return toDto(saved, actor);
  }

  @Transactional(readOnly = true)
  public List<ExportRequestDto> listPending() {
    Company company = companyContextService.requireCurrentCompany();
    return exportRequestRepository
        .findByCompanyAndStatusOrderByCreatedAtAsc(company, ExportApprovalStatus.PENDING)
        .stream()
        .map(this::toDto)
        .toList();
  }

  @Transactional
  public ExportRequestDto approve(Long requestId) {
    Company company = companyContextService.requireCurrentCompany();
    ExportRequest request = requireRequest(company, requestId);

    if (request.getStatus() != ExportApprovalStatus.PENDING) {
      throw invalidState(
          requestId, request.getStatus(), "Only pending export requests can be approved");
    }

    request.setStatus(ExportApprovalStatus.APPROVED);
    request.setApprovedBy(SecurityActorResolver.resolveActorOrUnknown());
    request.setApprovedAt(CompanyTime.now(company));
    return toDto(request);
  }

  @Transactional
  public ExportRequestDto reject(Long requestId, String reason) {
    Company company = companyContextService.requireCurrentCompany();
    ExportRequest request = requireRequest(company, requestId);

    if (request.getStatus() != ExportApprovalStatus.PENDING) {
      throw invalidState(
          requestId, request.getStatus(), "Only pending export requests can be rejected");
    }

    request.setStatus(ExportApprovalStatus.REJECTED);
    request.setApprovedBy(SecurityActorResolver.resolveActorOrUnknown());
    request.setApprovedAt(CompanyTime.now(company));
    request.setRejectionReason(normalizeReason(reason));
    return toDto(request);
  }

  @Transactional(readOnly = true)
  public ExportRequestDownloadResponse resolveDownload(Long requestId) {
    Company company = companyContextService.requireCurrentCompany();
    ExportRequest request = requireRequest(company, requestId);
    UserAccount actor = resolveActor(company);

    if (request.getUserId() == null || !request.getUserId().equals(actor.getId())) {
      throw new ApplicationException(
              ErrorCode.AUTH_INSUFFICIENT_PERMISSIONS,
              "Export request does not belong to the authenticated actor")
          .withDetail("requestId", requestId)
          .withDetail("actor", actor.getEmail());
    }

    if (!isApprovalRequired()) {
      return new ExportRequestDownloadResponse(
          request.getId(),
          request.getStatus(),
          request.getReportType(),
          request.getParameters(),
          "Export approval disabled; download allowed");
    }

    if (request.getStatus() != ExportApprovalStatus.APPROVED) {
      throw new ApplicationException(
              ErrorCode.AUTH_INSUFFICIENT_PERMISSIONS,
              "Export request is not approved for download")
          .withDetail("requestId", requestId)
          .withDetail("status", request.getStatus().name());
    }

    return new ExportRequestDownloadResponse(
        request.getId(),
        request.getStatus(),
        request.getReportType(),
        request.getParameters(),
        "Export request approved for download");
  }

  private ExportRequest requireRequest(Company company, Long requestId) {
    return exportRequestRepository
        .findByCompanyAndId(company, requestId)
        .orElseThrow(
            () ->
                new ApplicationException(
                    ErrorCode.BUSINESS_ENTITY_NOT_FOUND, "Export request not found: " + requestId));
  }

  private UserAccount resolveActor(Company company) {
    String actorEmail = SecurityActorResolver.resolveActorOrUnknown();
    if (!StringUtils.hasText(actorEmail)
        || SecurityActorResolver.UNKNOWN_AUTH_ACTOR.equals(actorEmail)
        || SecurityActorResolver.SYSTEM_PROCESS_ACTOR.equals(actorEmail)) {
      throw new ApplicationException(
          ErrorCode.AUTH_INSUFFICIENT_PERMISSIONS,
          "Authenticated actor is required for export request");
    }

    UserAccount actor =
        userAccountRepository
            .findByEmailIgnoreCaseAndAuthScopeCodeIgnoreCase(actorEmail, company.getCode())
            .orElseThrow(
                () ->
                    new ApplicationException(
                        ErrorCode.BUSINESS_ENTITY_NOT_FOUND,
                        "User not found for actor: " + actorEmail));

    if (actor.getCompany() == null
        || actor.getCompany().getId() == null
        || company.getId() == null
        || !company.getId().equals(actor.getCompany().getId())) {
      throw new ApplicationException(
              ErrorCode.AUTH_INSUFFICIENT_PERMISSIONS, "Actor not scoped to active company")
          .withDetail("actor", actorEmail)
          .withDetail("companyCode", company.getCode());
    }

    return actor;
  }

  private ExportRequestDto toDto(ExportRequest request) {
    String userEmail = resolveUserEmail(request.getUserId());
    return new ExportRequestDto(
        request.getId(),
        request.getUserId(),
        userEmail,
        request.getReportType(),
        request.getParameters(),
        request.getStatus(),
        request.getRejectionReason(),
        request.getCreatedAt(),
        request.getApprovedBy(),
        request.getApprovedAt());
  }

  private ExportRequestDto toDto(ExportRequest request, UserAccount user) {
    return new ExportRequestDto(
        request.getId(),
        request.getUserId(),
        user != null ? user.getEmail() : resolveUserEmail(request.getUserId()),
        request.getReportType(),
        request.getParameters(),
        request.getStatus(),
        request.getRejectionReason(),
        request.getCreatedAt(),
        request.getApprovedBy(),
        request.getApprovedAt());
  }

  private String resolveUserEmail(Long userId) {
    if (userId == null) {
      return null;
    }
    return userAccountRepository.findById(userId).map(UserAccount::getEmail).orElse(null);
  }

  private String normalizeParameters(String parameters) {
    if (!StringUtils.hasText(parameters)) {
      return null;
    }
    return parameters.trim();
  }

  private ApplicationException invalidState(
      Long requestId, ExportApprovalStatus currentStatus, String message) {
    return new ApplicationException(ErrorCode.BUSINESS_INVALID_STATE, message)
        .withDetail("requestId", requestId)
        .withDetail("status", currentStatus != null ? currentStatus.name() : null);
  }

  private String normalizeReason(String reason) {
    if (!StringUtils.hasText(reason)) {
      return null;
    }
    String trimmed = reason.trim();
    return StringUtils.hasText(trimmed) ? trimmed : null;
  }
}
