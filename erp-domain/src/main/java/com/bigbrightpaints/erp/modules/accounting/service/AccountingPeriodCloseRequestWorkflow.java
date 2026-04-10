package com.bigbrightpaints.erp.modules.accounting.service;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.StringUtils;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.core.security.SecurityActorResolver;
import com.bigbrightpaints.erp.core.util.CompanyTime;
import com.bigbrightpaints.erp.core.validation.ValidationUtils;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriod;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriodRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriodStatus;
import com.bigbrightpaints.erp.modules.accounting.domain.PeriodCloseRequest;
import com.bigbrightpaints.erp.modules.accounting.domain.PeriodCloseRequestRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.PeriodCloseRequestStatus;
import com.bigbrightpaints.erp.modules.accounting.dto.AccountingPeriodDto;
import com.bigbrightpaints.erp.modules.accounting.dto.PeriodCloseRequestActionRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.PeriodCloseRequestDto;
import com.bigbrightpaints.erp.modules.accounting.dto.PeriodStatusChangeRequest;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;

final class AccountingPeriodCloseRequestWorkflow {

  private final AccountingPeriodRepository accountingPeriodRepository;
  private final CompanyContextService companyContextService;
  private final PeriodCloseRequestRepository periodCloseRequestRepository;
  private final AccountingPeriodStatusWorkflow statusWorkflow;

  AccountingPeriodCloseRequestWorkflow(
      AccountingPeriodRepository accountingPeriodRepository,
      CompanyContextService companyContextService,
      PeriodCloseRequestRepository periodCloseRequestRepository,
      AccountingPeriodStatusWorkflow statusWorkflow) {
    this.accountingPeriodRepository = accountingPeriodRepository;
    this.companyContextService = companyContextService;
    this.periodCloseRequestRepository = periodCloseRequestRepository;
    this.statusWorkflow = statusWorkflow;
  }

  PeriodCloseRequestDto requestPeriodClose(
      Long periodId,
      PeriodCloseRequestActionRequest request,
      AccountingComplianceAuditService accountingComplianceAuditService) {
    Company company = companyContextService.requireCurrentCompany();
    AccountingPeriod period =
        accountingPeriodRepository
            .lockByCompanyAndId(company, periodId)
            .orElseThrow(
                () ->
                    new ApplicationException(
                        ErrorCode.VALIDATION_INVALID_REFERENCE, "Accounting period not found"));
    if (AccountingPeriodStatus.CLOSED.equals(period.getStatus())) {
      throw ValidationUtils.invalidState(
          "Accounting period " + period.getLabel() + " is already closed");
    }
    if (periodCloseRequestRepository == null) {
      throw ValidationUtils.invalidState("Period close request workflow is not configured");
    }
    String requester = resolveCurrentUsername();
    String note =
        normalizeRequiredNote(
            request != null ? request.note() : null, "Close request note is required");
    boolean force = request != null && Boolean.TRUE.equals(request.force());

    PeriodCloseRequest pending =
        periodCloseRequestRepository
            .lockByCompanyAndAccountingPeriodAndStatus(
                company, period, PeriodCloseRequestStatus.PENDING)
            .orElse(null);
    if (pending != null) {
      if (!requester.equalsIgnoreCase(normalizeActor(pending.getRequestedBy(), "requestedBy"))) {
        throw ValidationUtils.invalidState(
            "A pending period close request already exists for " + period.getLabel());
      }
      pending.setRequestNote(note);
      pending.setForceRequested(force);
      pending.setRequestedAt(CompanyTime.now(company));
      pending.setReviewedBy(null);
      pending.setReviewedAt(null);
      pending.setReviewNote(null);
      pending.setApprovalNote(null);
      PeriodCloseRequest saved = periodCloseRequestRepository.save(pending);
      if (accountingComplianceAuditService != null) {
        accountingComplianceAuditService.recordPeriodCloseRequestLifecycle(
            company,
            saved,
            PeriodCloseRequestStatus.PENDING,
            PeriodCloseRequestStatus.PENDING,
            "PERIOD_CLOSE_REQUEST_UPDATED",
            "PERIOD_CLOSE_REQUEST_UPDATED",
            requester,
            note,
            false);
      }
      return toPeriodCloseRequestDto(saved);
    }

    PeriodCloseRequest created = new PeriodCloseRequest();
    created.setCompany(company);
    created.setAccountingPeriod(period);
    created.setStatus(PeriodCloseRequestStatus.PENDING);
    created.setRequestedBy(requester);
    created.setRequestNote(note);
    created.setForceRequested(force);
    created.setRequestedAt(CompanyTime.now(company));
    PeriodCloseRequest saved = periodCloseRequestRepository.save(created);
    if (accountingComplianceAuditService != null) {
      accountingComplianceAuditService.recordPeriodCloseRequestLifecycle(
          company,
          saved,
          null,
          PeriodCloseRequestStatus.PENDING,
          "PERIOD_CLOSE_REQUESTED",
          "PERIOD_CLOSE_REQUESTED",
          requester,
          note,
          false);
    }
    return toPeriodCloseRequestDto(saved);
  }

  AccountingPeriodDto approvePeriodClose(
      Long periodId,
      PeriodCloseRequestActionRequest request,
      AccountingComplianceAuditService accountingComplianceAuditService) {
    requireAdminRole();
    Company company = companyContextService.requireCurrentCompany();
    AccountingPeriod period =
        accountingPeriodRepository
            .lockByCompanyAndId(company, periodId)
            .orElseThrow(
                () ->
                    new ApplicationException(
                        ErrorCode.VALIDATION_INVALID_REFERENCE, "Accounting period not found"));
    if (AccountingPeriodStatus.CLOSED.equals(period.getStatus())) {
      throw ValidationUtils.invalidState(
          "Accounting period " + period.getLabel() + " is already closed");
    }
    if (periodCloseRequestRepository == null) {
      throw ValidationUtils.invalidState("Period close request workflow is not configured");
    }

    PeriodCloseRequest pending =
        periodCloseRequestRepository
            .lockByCompanyAndAccountingPeriodAndStatus(
                company, period, PeriodCloseRequestStatus.PENDING)
            .orElseThrow(
                () ->
                    ValidationUtils.invalidState(
                        "No pending period close request found for " + period.getLabel()));

    String reviewer = resolveCurrentUsername();
    assertMakerCheckerBoundary(pending, reviewer);
    String approvalNote =
        normalizeRequiredNote(request != null ? request.note() : null, "Approval note is required");
    boolean force =
        request != null && request.force() != null
            ? Boolean.TRUE.equals(request.force())
            : pending.isForceRequested();

    pending.setStatus(PeriodCloseRequestStatus.APPROVED);
    pending.setReviewedBy(reviewer);
    pending.setReviewedAt(CompanyTime.now(company));
    pending.setReviewNote(approvalNote);
    pending.setApprovalNote(approvalNote);
    pending.setForceRequested(force);

    AccountingPeriodDto closed =
        statusWorkflow.closePeriod(
            periodId,
            new PeriodStatusChangeRequest(
                PeriodStatusChangeRequest.PeriodStatusAction.CLOSE, force, approvalNote),
            true,
            pending,
            accountingComplianceAuditService);
    periodCloseRequestRepository.save(pending);
    if (accountingComplianceAuditService != null) {
      accountingComplianceAuditService.recordPeriodCloseRequestLifecycle(
          company,
          pending,
          PeriodCloseRequestStatus.PENDING,
          PeriodCloseRequestStatus.APPROVED,
          "PERIOD_CLOSE_APPROVED",
          "PERIOD_CLOSE_APPROVED",
          reviewer,
          approvalNote,
          true);
    }
    return closed;
  }

  PeriodCloseRequestDto rejectPeriodClose(
      Long periodId,
      PeriodCloseRequestActionRequest request,
      AccountingComplianceAuditService accountingComplianceAuditService) {
    requireAdminRole();
    Company company = companyContextService.requireCurrentCompany();
    AccountingPeriod period =
        accountingPeriodRepository
            .lockByCompanyAndId(company, periodId)
            .orElseThrow(
                () ->
                    new ApplicationException(
                        ErrorCode.VALIDATION_INVALID_REFERENCE, "Accounting period not found"));
    if (periodCloseRequestRepository == null) {
      throw ValidationUtils.invalidState("Period close request workflow is not configured");
    }

    PeriodCloseRequest pending =
        periodCloseRequestRepository
            .lockByCompanyAndAccountingPeriodAndStatus(
                company, period, PeriodCloseRequestStatus.PENDING)
            .orElseThrow(
                () ->
                    ValidationUtils.invalidState(
                        "No pending period close request found for " + period.getLabel()));

    String reviewer = resolveCurrentUsername();
    assertMakerCheckerBoundary(pending, reviewer);
    String rejectionNote =
        normalizeRequiredNote(
            request != null ? request.note() : null, "Rejection note is required");

    pending.setStatus(PeriodCloseRequestStatus.REJECTED);
    pending.setReviewedBy(reviewer);
    pending.setReviewedAt(CompanyTime.now(company));
    pending.setReviewNote(rejectionNote);
    pending.setApprovalNote(null);

    PeriodCloseRequest saved = periodCloseRequestRepository.save(pending);
    if (accountingComplianceAuditService != null) {
      accountingComplianceAuditService.recordPeriodCloseRequestLifecycle(
          company,
          saved,
          PeriodCloseRequestStatus.PENDING,
          PeriodCloseRequestStatus.REJECTED,
          "PERIOD_CLOSE_REJECTED",
          "PERIOD_CLOSE_REJECTED",
          reviewer,
          rejectionNote,
          true);
    }
    return toPeriodCloseRequestDto(saved);
  }

  private PeriodCloseRequestDto toPeriodCloseRequestDto(PeriodCloseRequest request) {
    if (request == null) {
      return null;
    }
    AccountingPeriod period = request.getAccountingPeriod();
    String periodStatus =
        period != null && period.getStatus() != null ? period.getStatus().name() : null;
    return new PeriodCloseRequestDto(
        request.getId(),
        request.getPublicId(),
        period != null ? period.getId() : null,
        period != null ? period.getLabel() : null,
        periodStatus,
        request.getStatus() != null ? request.getStatus().name() : null,
        request.isForceRequested(),
        request.getRequestedBy(),
        request.getRequestNote(),
        request.getRequestedAt(),
        request.getReviewedBy(),
        request.getReviewedAt(),
        request.getReviewNote(),
        request.getApprovalNote());
  }

  private String normalizeRequiredNote(String note, String message) {
    String normalized = note == null ? null : note.trim();
    if (!StringUtils.hasText(normalized)) {
      throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT, message);
    }
    return normalized;
  }

  private void assertMakerCheckerBoundary(PeriodCloseRequest request, String reviewer) {
    String requester =
        normalizeActor(request != null ? request.getRequestedBy() : null, "requestedBy");
    String normalizedReviewer = normalizeActor(reviewer, "reviewedBy");
    if (requester.equalsIgnoreCase(normalizedReviewer)) {
      throw new ApplicationException(
              ErrorCode.VALIDATION_INVALID_INPUT,
              "Maker-checker violation: requester and reviewer cannot be the same actor")
          .withDetail("resourceType", "period_close_request")
          .withDetail("requestedBy", requester)
          .withDetail("reviewedBy", normalizedReviewer);
    }
  }

  private String normalizeActor(String actor, String fieldName) {
    String normalized = actor != null ? actor.trim() : "";
    if (normalized.isEmpty()) {
      throw new ApplicationException(
          ErrorCode.VALIDATION_INVALID_INPUT, fieldName + " is required");
    }
    return normalized;
  }

  private void requireAdminRole() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication == null || !authentication.isAuthenticated()) {
      throw new ApplicationException(
          ErrorCode.AUTH_INSUFFICIENT_PERMISSIONS,
          "ROLE_ADMIN authority required to approve or reject period close requests");
    }
    boolean hasAdmin =
        authentication.getAuthorities().stream()
            .anyMatch(
                authority ->
                    "ROLE_ADMIN".equalsIgnoreCase(authority.getAuthority())
                        || "ROLE_SUPER_ADMIN".equalsIgnoreCase(authority.getAuthority()));
    if (!hasAdmin) {
      throw new ApplicationException(
          ErrorCode.AUTH_INSUFFICIENT_PERMISSIONS,
          "ROLE_ADMIN authority required to approve or reject period close requests");
    }
  }

  private String resolveCurrentUsername() {
    return SecurityActorResolver.resolveActorWithSystemProcessFallback();
  }
}
