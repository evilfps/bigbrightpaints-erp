package com.bigbrightpaints.erp.modules.admin.service;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.core.security.SecurityActorResolver;
import com.bigbrightpaints.erp.modules.admin.domain.SupportTicket;
import com.bigbrightpaints.erp.modules.admin.domain.SupportTicketCategory;
import com.bigbrightpaints.erp.modules.admin.domain.SupportTicketRepository;
import com.bigbrightpaints.erp.modules.admin.dto.SupportTicketCreateRequest;
import com.bigbrightpaints.erp.modules.admin.dto.SupportTicketResponse;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccount;
import com.bigbrightpaints.erp.modules.auth.domain.UserPrincipal;
import com.bigbrightpaints.erp.modules.company.domain.Company;

import jakarta.annotation.Nullable;

@Service
public class SupportTicketAccessSupport {

  private final SupportTicketRepository supportTicketRepository;
  private final SupportTicketGitHubSyncService supportTicketGitHubSyncService;

  public SupportTicketAccessSupport(
      SupportTicketRepository supportTicketRepository,
      SupportTicketGitHubSyncService supportTicketGitHubSyncService) {
    this.supportTicketRepository = supportTicketRepository;
    this.supportTicketGitHubSyncService = supportTicketGitHubSyncService;
  }

  @Transactional
  public SupportTicketResponse createTicket(Company company, SupportTicketCreateRequest request) {
    UserAccount actor = requireCurrentUser();

    SupportTicket ticket = new SupportTicket();
    ticket.setCompany(company);
    ticket.setUserId(actor.getId());
    ticket.setCategory(parseCategory(request.category()));
    ticket.setSubject(normalizeRequired(request.subject(), "subject", 255));
    ticket.setDescription(normalizeRequired(request.description(), "description", 4000));

    SupportTicket saved = supportTicketRepository.save(ticket);
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              supportTicketGitHubSyncService.submitGitHubIssueAsync(saved.getId());
            }
          });
    } else {
      supportTicketGitHubSyncService.submitGitHubIssueAsync(saved.getId());
    }
    return toResponses(List.of(saved), actor.getId()).getFirst();
  }

  public UserAccount requireCurrentUser() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication == null || !authentication.isAuthenticated()) {
      throw new ApplicationException(
          ErrorCode.AUTH_INSUFFICIENT_PERMISSIONS, "Authentication is required");
    }
    Object principal = authentication.getPrincipal();
    if (principal instanceof UserPrincipal userPrincipal && userPrincipal.getUser() != null) {
      return userPrincipal.getUser();
    }
    String actor = SecurityActorResolver.resolveActorOrUnknown();
    if (!StringUtils.hasText(actor)
        || SecurityActorResolver.UNKNOWN_AUTH_ACTOR.equals(actor)
        || SecurityActorResolver.SYSTEM_PROCESS_ACTOR.equals(actor)) {
      throw new ApplicationException(
          ErrorCode.AUTH_INSUFFICIENT_PERMISSIONS, "Authenticated user account is required");
    }
    throw new ApplicationException(
        ErrorCode.AUTH_INSUFFICIENT_PERMISSIONS,
        "Authenticated principal is not a user account: " + actor);
  }

  public Long requireTicketId(@Nullable Long ticketId) {
    if (ticketId == null) {
      throw new ApplicationException(
          ErrorCode.VALIDATION_MISSING_REQUIRED_FIELD, "ticketId is required");
    }
    return ticketId;
  }

  public ApplicationException notFound(Long ticketId) {
    return new ApplicationException(
        ErrorCode.BUSINESS_ENTITY_NOT_FOUND, "Support ticket not found: " + ticketId);
  }

  public List<SupportTicketResponse> toResponses(
      List<SupportTicket> tickets, @Nullable Long actorUserId) {
    if (tickets == null || tickets.isEmpty()) {
      return List.of();
    }

    Map<Long, String> requesterEmails = resolveRequesterEmails(tickets, actorUserId);
    return tickets.stream()
        .map(
            ticket -> {
              String companyCode =
                  ticket.getCompany() != null ? ticket.getCompany().getCode() : null;
              return new SupportTicketResponse(
                  ticket.getId(),
                  ticket.getPublicId(),
                  companyCode,
                  ticket.getUserId(),
                  requesterEmails.get(ticket.getUserId()),
                  ticket.getCategory(),
                  ticket.getSubject(),
                  ticket.getDescription(),
                  ticket.getStatus(),
                  ticket.getGithubIssueNumber(),
                  ticket.getGithubIssueUrl(),
                  ticket.getGithubIssueState(),
                  ticket.getGithubSyncedAt(),
                  ticket.getGithubLastError(),
                  ticket.getResolvedAt(),
                  ticket.getResolvedNotificationSentAt(),
                  ticket.getCreatedAt(),
                  ticket.getUpdatedAt());
            })
        .toList();
  }

  private SupportTicketCategory parseCategory(String rawCategory) {
    String normalized = normalizeRequired(rawCategory, "category", 32).toUpperCase(Locale.ROOT);
    try {
      return SupportTicketCategory.valueOf(normalized);
    } catch (IllegalArgumentException ex) {
      throw new ApplicationException(
          ErrorCode.VALIDATION_INVALID_INPUT, "Invalid category: " + rawCategory);
    }
  }

  private String normalizeRequired(String value, String fieldName, int maxLength) {
    if (!StringUtils.hasText(value)) {
      throw new ApplicationException(
          ErrorCode.VALIDATION_MISSING_REQUIRED_FIELD, fieldName + " is required");
    }
    String trimmed = value.trim();
    if (trimmed.length() > maxLength) {
      throw new ApplicationException(
          ErrorCode.VALIDATION_OUT_OF_RANGE, fieldName + " exceeds max length " + maxLength);
    }
    return trimmed;
  }

  private Map<Long, String> resolveRequesterEmails(
      List<SupportTicket> tickets, @Nullable Long actorUserId) {
    Set<Long> requesterIds =
        tickets.stream()
            .map(SupportTicket::getUserId)
            .filter(id -> id != null && id > 0)
            .collect(Collectors.toSet());
    if (requesterIds.isEmpty()) {
      return Map.of();
    }

    if (actorUserId != null && requesterIds.size() == 1 && requesterIds.contains(actorUserId)) {
      return Map.of(actorUserId, resolveActorEmail());
    }

    return supportTicketRepository.findUsersByIdIn(requesterIds).stream()
        .collect(
            Collectors.toMap(
                UserAccount::getId,
                UserAccount::getEmail,
                (existing, replacement) -> existing,
                java.util.LinkedHashMap::new));
  }

  private String resolveActorEmail() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication != null
        && authentication.getPrincipal() instanceof UserPrincipal userPrincipal
        && userPrincipal.getUser() != null) {
      return userPrincipal.getUser().getEmail();
    }
    String actor = SecurityActorResolver.resolveActorOrUnknown();
    if (StringUtils.hasText(actor)
        && !SecurityActorResolver.UNKNOWN_AUTH_ACTOR.equals(actor)
        && !SecurityActorResolver.SYSTEM_PROCESS_ACTOR.equals(actor)) {
      return actor;
    }
    return null;
  }
}
