package com.bigbrightpaints.erp.modules.admin.service;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

import com.bigbrightpaints.erp.core.audittrail.AuditActionEventCommand;
import com.bigbrightpaints.erp.core.audittrail.AuditActionEventSource;
import com.bigbrightpaints.erp.core.audittrail.AuditActionEventStatus;
import com.bigbrightpaints.erp.core.audittrail.EnterpriseAuditTrailService;
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
  private final EnterpriseAuditTrailService enterpriseAuditTrailService;

  public ExportApprovalService(
      CompanyContextService companyContextService,
      UserAccountRepository userAccountRepository,
      ExportRequestRepository exportRequestRepository,
      SystemSettingsService systemSettingsService,
      EnterpriseAuditTrailService enterpriseAuditTrailService) {
    this.companyContextService = companyContextService;
    this.userAccountRepository = userAccountRepository;
    this.exportRequestRepository = exportRequestRepository;
    this.systemSettingsService = systemSettingsService;
    this.enterpriseAuditTrailService = enterpriseAuditTrailService;
  }

  public boolean isApprovalRequired() {
    return systemSettingsService.isExportApprovalRequired();
  }

  @Transactional
  public ExportRequestDto createRequest(ExportRequestCreateRequest request) {
    Company company = companyContextService.requireCurrentCompany();
    UserAccount actor = resolveActor(company);

    String reportType = ValidationUtils.requireNotBlank(request.reportType(), "reportType");
    String format = normalizeFormat(request.format());
    String parameters = normalizeParameters(request.parameters(), format);

    ExportRequest exportRequest = new ExportRequest();
    exportRequest.setCompany(company);
    exportRequest.setUserId(actor.getId());
    exportRequest.setReportType(reportType.toUpperCase(Locale.ROOT));
    exportRequest.setParameters(parameters);
    exportRequest.setStatus(ExportApprovalStatus.PENDING);

    ExportRequest saved = exportRequestRepository.save(exportRequest);
    recordExportBusinessEvent(saved, actor, "EXPORT_REQUESTED", format);
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
  public ExportDownloadPayload resolveDownload(Long requestId) {
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
      return buildDownloadPayload(request, actor);
    }

    if (request.getStatus() != ExportApprovalStatus.APPROVED) {
      throw new ApplicationException(
              ErrorCode.AUTH_INSUFFICIENT_PERMISSIONS,
              "Export request is not approved for download")
          .withDetail("requestId", requestId)
          .withDetail("status", request.getStatus().name());
    }

    return buildDownloadPayload(request, actor);
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

  private ExportDownloadPayload buildDownloadPayload(ExportRequest request, UserAccount actor) {
    String format = resolveFormat(request.getParameters());
    ExportFileContent exportFile = buildFileContent(request, format);
    recordExportBusinessEvent(request, actor, "EXPORT_DOWNLOADED", format);
    return new ExportDownloadPayload(
        exportFile.content(), exportFile.contentType(), exportFile.fileName());
  }

  private ExportFileContent buildFileContent(ExportRequest request, String format) {
    String resolvedFormat = normalizeFormat(format);
    String normalizedReportType = safeToken(request.getReportType(), "report");
    String filename =
        normalizedReportType.toLowerCase(Locale.ROOT)
            + "-"
            + request.getId()
            + "."
            + fileExtension(resolvedFormat);
    String contentType = mimeType(resolvedFormat);
    byte[] content;
    if ("CSV".equals(resolvedFormat)) {
      String csv =
          "requestId,reportType,status,parameters\n"
              + request.getId()
              + ","
              + request.getReportType()
              + ","
              + request.getStatus()
              + ","
              + (request.getParameters() != null ? request.getParameters().replace(",", ";") : "")
              + "\n";
      content = csv.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    } else {
      String pdfBody =
          "%PDF-1.4\n"
              + "1 0 obj << /Type /Catalog /Pages 2 0 R >> endobj\n"
              + "2 0 obj << /Type /Pages /Count 1 /Kids [3 0 R] >> endobj\n"
              + "3 0 obj << /Type /Page /Parent 2 0 R /MediaBox [0 0 300 144] /Contents 4 0 R"
              + " >> endobj\n"
              + "4 0 obj << /Length 60 >> stream\n"
              + "BT /F1 12 Tf 20 100 Td ("
              + normalizedReportType
              + " export "
              + request.getId()
              + ") Tj ET\n"
              + "endstream endobj\n"
              + "xref\n0 5\n0000000000 65535 f \n"
              + "trailer << /Root 1 0 R /Size 5 >>\nstartxref\n0\n%%EOF";
      content = pdfBody.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }
    return new ExportFileContent(content, contentType, filename);
  }

  private String normalizeParameters(String parameters, String format) {
    String trimmed = StringUtils.hasText(parameters) ? parameters.trim() : null;
    if (!StringUtils.hasText(trimmed)) {
      return "format=" + normalizeFormat(format);
    }
    if (trimmed.toLowerCase(Locale.ROOT).contains("format=")) {
      return trimmed;
    }
    return trimmed + ";format=" + normalizeFormat(format);
  }

  private String normalizeFormat(String format) {
    if (!StringUtils.hasText(format)) {
      return "PDF";
    }
    return format.trim().toUpperCase(Locale.ROOT);
  }

  private String resolveFormat(String parameters) {
    if (!StringUtils.hasText(parameters)) {
      return "PDF";
    }
    String[] tokens = parameters.split("[;&]");
    for (String token : tokens) {
      if (!StringUtils.hasText(token)) {
        continue;
      }
      String[] keyValue = token.split("=", 2);
      if (keyValue.length != 2) {
        continue;
      }
      if ("format".equalsIgnoreCase(keyValue[0].trim())) {
        return normalizeFormat(keyValue[1]);
      }
    }
    return "PDF";
  }

  private String fileExtension(String format) {
    return switch (normalizeFormat(format)) {
      case "CSV" -> "csv";
      default -> "pdf";
    };
  }

  private String mimeType(String format) {
    return switch (normalizeFormat(format)) {
      case "CSV" -> "text/csv";
      default -> "application/pdf";
    };
  }

  private String safeToken(String value, String fallback) {
    if (!StringUtils.hasText(value)) {
      return fallback;
    }
    return value.trim().replaceAll("[^A-Za-z0-9_-]", "-");
  }

  private void recordExportBusinessEvent(
      ExportRequest request, UserAccount actor, String action, String format) {
    if (enterpriseAuditTrailService == null || request == null || request.getCompany() == null) {
      return;
    }
    Map<String, String> metadata =
        Map.of(
            "reportType", request.getReportType(),
            "status", request.getStatus() != null ? request.getStatus().name() : "UNKNOWN",
            "format", normalizeFormat(format));
    AuditActionEventCommand command =
        new AuditActionEventCommand(
            request.getCompany(),
            AuditActionEventSource.BACKEND,
            "EXPORT",
            action,
            "EXPORT_REQUEST",
            String.valueOf(request.getId()),
            "EXP-" + request.getId(),
            AuditActionEventStatus.SUCCESS,
            null,
            null,
            request.getCompany().getBaseCurrency(),
            null,
            null,
            null,
            null,
            null,
            actor,
            false,
            null,
            metadata,
            CompanyTime.now(request.getCompany()));

    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              enterpriseAuditTrailService.recordBusinessEvent(command);
            }
          });
      return;
    }
    enterpriseAuditTrailService.recordBusinessEvent(command);
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

  private record ExportFileContent(byte[] content, String contentType, String fileName) {}

  public record ExportDownloadPayload(byte[] content, String contentType, String fileName) {}
}
