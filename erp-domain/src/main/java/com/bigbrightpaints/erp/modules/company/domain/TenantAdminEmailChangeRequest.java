package com.bigbrightpaints.erp.modules.company.domain;

import java.time.Instant;
import java.util.Locale;

import com.bigbrightpaints.erp.core.domain.VersionedEntity;
import com.bigbrightpaints.erp.core.util.CompanyTime;

import jakarta.persistence.*;

@Entity
@Table(name = "tenant_admin_email_change_requests")
public class TenantAdminEmailChangeRequest extends VersionedEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "company_id", nullable = false)
  private Long companyId;

  @Column(name = "admin_user_id", nullable = false)
  private Long adminUserId;

  @Column(name = "requested_by", nullable = false, length = 255)
  private String requestedBy;

  @Column(name = "current_email", nullable = false, length = 255)
  private String currentEmail;

  @Column(name = "requested_email", nullable = false, length = 255)
  private String requestedEmail;

  @Column(name = "verification_token", nullable = false, length = 255)
  private String verificationToken;

  @Column(name = "verification_sent_at", nullable = false)
  private Instant verificationSentAt;

  @Column(name = "verified_at")
  private Instant verifiedAt;

  @Column(name = "confirmed_at")
  private Instant confirmedAt;

  @Column(name = "expires_at", nullable = false)
  private Instant expiresAt;

  @Column(name = "consumed", nullable = false)
  private boolean consumed;

  @PrePersist
  void prePersist() {
    requestedBy = normalizeTrimmed(requestedBy, "UNKNOWN");
    currentEmail = normalizeEmail(currentEmail);
    requestedEmail = normalizeEmail(requestedEmail);
    verificationToken = normalizeTrimmed(verificationToken, null);
    if (verificationSentAt == null) {
      verificationSentAt = CompanyTime.now();
    }
    if (expiresAt == null) {
      expiresAt = verificationSentAt.plusSeconds(60L * 60L * 24L);
    }
  }

  public Long getId() {
    return id;
  }

  public Long getCompanyId() {
    return companyId;
  }

  public void setCompanyId(Long companyId) {
    this.companyId = companyId;
  }

  public Long getAdminUserId() {
    return adminUserId;
  }

  public void setAdminUserId(Long adminUserId) {
    this.adminUserId = adminUserId;
  }

  public String getRequestedBy() {
    return requestedBy;
  }

  public void setRequestedBy(String requestedBy) {
    this.requestedBy = requestedBy;
  }

  public String getCurrentEmail() {
    return currentEmail;
  }

  public void setCurrentEmail(String currentEmail) {
    this.currentEmail = currentEmail;
  }

  public String getRequestedEmail() {
    return requestedEmail;
  }

  public void setRequestedEmail(String requestedEmail) {
    this.requestedEmail = requestedEmail;
  }

  public String getVerificationToken() {
    return verificationToken;
  }

  public void setVerificationToken(String verificationToken) {
    this.verificationToken = verificationToken;
  }

  public Instant getVerificationSentAt() {
    return verificationSentAt;
  }

  public void setVerificationSentAt(Instant verificationSentAt) {
    this.verificationSentAt = verificationSentAt;
  }

  public Instant getVerifiedAt() {
    return verifiedAt;
  }

  public void setVerifiedAt(Instant verifiedAt) {
    this.verifiedAt = verifiedAt;
  }

  public Instant getConfirmedAt() {
    return confirmedAt;
  }

  public void setConfirmedAt(Instant confirmedAt) {
    this.confirmedAt = confirmedAt;
  }

  public Instant getExpiresAt() {
    return expiresAt;
  }

  public void setExpiresAt(Instant expiresAt) {
    this.expiresAt = expiresAt;
  }

  public boolean isConsumed() {
    return consumed;
  }

  public void setConsumed(boolean consumed) {
    this.consumed = consumed;
  }

  private String normalizeEmail(String value) {
    String normalized = normalizeTrimmed(value, null);
    return normalized == null ? null : normalized.toLowerCase(Locale.ROOT);
  }

  private String normalizeTrimmed(String value, String fallback) {
    if (value == null || value.isBlank()) {
      return fallback;
    }
    return value.trim();
  }
}
