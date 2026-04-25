package com.bigbrightpaints.erp.modules.admin.service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

import com.bigbrightpaints.erp.core.audittrail.AuditActionEventCommand;
import com.bigbrightpaints.erp.core.audittrail.AuditActionEventSource;
import com.bigbrightpaints.erp.core.audittrail.AuditActionEventStatus;
import com.bigbrightpaints.erp.core.audittrail.EnterpriseAuditTrailService;
import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.core.security.SensitiveDisclosurePolicyOwner;
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
  private final SensitiveDisclosurePolicyOwner sensitiveDisclosurePolicyOwner;
  private final EnterpriseAuditTrailService enterpriseAuditTrailService;

  public ExportApprovalService(
      CompanyContextService companyContextService,
      UserAccountRepository userAccountRepository,
      ExportRequestRepository exportRequestRepository,
      SensitiveDisclosurePolicyOwner sensitiveDisclosurePolicyOwner,
      EnterpriseAuditTrailService enterpriseAuditTrailService) {
    this.companyContextService = companyContextService;
    this.userAccountRepository = userAccountRepository;
    this.exportRequestRepository = exportRequestRepository;
    this.sensitiveDisclosurePolicyOwner = sensitiveDisclosurePolicyOwner;
    this.enterpriseAuditTrailService = enterpriseAuditTrailService;
  }

  public boolean isApprovalRequired() {
    return sensitiveDisclosurePolicyOwner.exportApprovalRequired();
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

  @Transactional(readOnly = true)
  public long countPending() {
    Company company = companyContextService.requireCurrentCompany();
    return exportRequestRepository.countByCompanyAndStatus(company, ExportApprovalStatus.PENDING);
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
    sensitiveDisclosurePolicyOwner.enforceRequesterOwnedDownload(request, actor);
    sensitiveDisclosurePolicyOwner.enforceApprovalGate(request);

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
      content = csv.getBytes(StandardCharsets.UTF_8);
    } else {
      content = renderExportPdf(request, normalizedReportType);
    }
    return new ExportFileContent(content, contentType, filename);
  }

  private byte[] renderExportPdf(ExportRequest request, String normalizedReportType) {
    try (PDDocument document = new PDDocument();
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      PDPage page = new PDPage(PDRectangle.LETTER);
      document.addPage(page);

      try (PDPageContentStream content = new PDPageContentStream(document, page)) {
        float margin = 56f;
        float y = page.getMediaBox().getUpperRightY() - margin;

        writePdfLine(
            content, PDType1Font.HELVETICA_BOLD, 18f, margin, y, normalizedReportType + " export");
        y -= 24f;
        writePdfLine(
            content,
            PDType1Font.HELVETICA,
            12f,
            margin,
            y,
            "Request ID: " + safePdfValue(request != null ? request.getId() : null));
        y -= 18f;
        writePdfLine(
            content,
            PDType1Font.HELVETICA,
            12f,
            margin,
            y,
            "Status: " + safePdfValue(request != null ? request.getStatus() : null));
        y -= 18f;
        writePdfLine(
            content,
            PDType1Font.HELVETICA,
            12f,
            margin,
            y,
            "Parameters: " + safePdfValue(request != null ? request.getParameters() : null));
      }

      document.save(out);
      return out.toByteArray();
    } catch (IOException ex) {
      throw ValidationUtils.invalidState("Failed to render export PDF", ex);
    }
  }

  private void writePdfLine(
      PDPageContentStream content, PDType1Font font, float fontSize, float x, float y, String text)
      throws IOException {
    content.beginText();
    content.setFont(font, fontSize);
    content.newLineAtOffset(x, y);
    content.showText(safePdfValue(text));
    content.endText();
  }

  private String normalizeParameters(String parameters, String format) {
    String normalized = StringUtils.hasText(parameters) ? parameters.trim() : "";
    if (normalized.isEmpty()) {
      return "format=" + normalizeFormat(format);
    }
    if (normalized.toLowerCase(Locale.ROOT).contains("format=")) {
      return normalized;
    }
    return normalized + ";format=" + normalizeFormat(format);
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

  private String safePdfValue(Object value) {
    if (value == null) {
      return "";
    }
    return value.toString().replaceAll("[\\p{Cntrl}]", " ").replaceAll("\\s+", " ").trim();
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
