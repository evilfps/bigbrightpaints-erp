package com.bigbrightpaints.erp.modules.auth.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import com.bigbrightpaints.erp.core.domain.VersionedEntity;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.rbac.domain.Role;

import jakarta.persistence.*;

@Entity
@Table(
    name = "app_users",
    uniqueConstraints = {
      @UniqueConstraint(
          name = "uq_app_users_email_scope",
          columnNames = {"email", "auth_scope_code"})
    })
public class UserAccount extends VersionedEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "public_id", nullable = false)
  private UUID publicId;

  @Column(nullable = false)
  private String email;

  @Column(name = "auth_scope_code", nullable = false, length = 64)
  private String authScopeCode;

  @Column(name = "password_hash", nullable = false)
  private String passwordHash;

  @Column(name = "display_name", nullable = false)
  private String displayName;

  @Column(nullable = false)
  private boolean enabled = true;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @ManyToMany(fetch = FetchType.EAGER)
  @JoinTable(
      name = "user_roles",
      joinColumns = @JoinColumn(name = "user_id"),
      inverseJoinColumns = @JoinColumn(name = "role_id"))
  private Set<Role> roles = new HashSet<>();

  @ManyToOne(fetch = FetchType.EAGER)
  @JoinColumn(name = "company_id")
  private Company company;

  @Column(name = "mfa_secret")
  private String mfaSecret;

  @Column(name = "mfa_enabled", nullable = false)
  private boolean mfaEnabled = false;

  @Column(name = "mfa_recovery_codes")
  private String mfaRecoveryCodes;

  @Column(name = "preferred_name")
  private String preferredName;

  @Column(name = "job_title")
  private String jobTitle;

  @Column(name = "profile_picture_url")
  private String profilePictureUrl;

  @Column(name = "phone_secondary")
  private String phoneSecondary;

  @Column(name = "secondary_email")
  private String secondaryEmail;

  @Column(name = "failed_login_attempts", nullable = false)
  private int failedLoginAttempts = 0;

  @Column(name = "locked_until")
  private Instant lockedUntil;

  @Column(name = "must_change_password", nullable = false)
  private boolean mustChangePassword = false;

  public UserAccount() {}

  public UserAccount(String email, String passwordHash, String displayName) {
    this(
        email, "LEGACY-" + UUID.randomUUID().toString().substring(0, 8), passwordHash, displayName);
  }

  public UserAccount(String email, String authScopeCode, String passwordHash, String displayName) {
    this.publicId = UUID.randomUUID();
    this.email = email;
    this.authScopeCode = normalizeScopeCode(authScopeCode);
    this.passwordHash = passwordHash;
    this.displayName = displayName;
    this.enabled = true;
    this.createdAt = Instant.now();
  }

  @PrePersist
  public void prePersist() {
    if (publicId == null) {
      publicId = UUID.randomUUID();
    }
    if (createdAt == null) {
      createdAt = Instant.now();
    }
  }

  public Long getId() {
    return id;
  }

  public UUID getPublicId() {
    return publicId;
  }

  public String getEmail() {
    return email;
  }

  public void setEmail(String email) {
    this.email = email;
  }

  public String getPasswordHash() {
    return passwordHash;
  }

  public String getAuthScopeCode() {
    return authScopeCode;
  }

  public void setAuthScopeCode(String authScopeCode) {
    this.authScopeCode = normalizeScopeCode(authScopeCode);
  }

  public String getDisplayName() {
    return displayName;
  }

  public boolean isEnabled() {
    return enabled;
  }

  public void setDisplayName(String displayName) {
    this.displayName = displayName;
  }

  public void setPasswordHash(String passwordHash) {
    this.passwordHash = passwordHash;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public String getPreferredName() {
    return preferredName;
  }

  public void setPreferredName(String preferredName) {
    this.preferredName = preferredName;
  }

  public String getJobTitle() {
    return jobTitle;
  }

  public void setJobTitle(String jobTitle) {
    this.jobTitle = jobTitle;
  }

  public String getProfilePictureUrl() {
    return profilePictureUrl;
  }

  public void setProfilePictureUrl(String profilePictureUrl) {
    this.profilePictureUrl = profilePictureUrl;
  }

  public String getPhoneSecondary() {
    return phoneSecondary;
  }

  public void setPhoneSecondary(String phoneSecondary) {
    this.phoneSecondary = phoneSecondary;
  }

  public String getSecondaryEmail() {
    return secondaryEmail;
  }

  public void setSecondaryEmail(String secondaryEmail) {
    this.secondaryEmail = secondaryEmail;
  }

  public int getFailedLoginAttempts() {
    return failedLoginAttempts;
  }

  public void setFailedLoginAttempts(int failedLoginAttempts) {
    this.failedLoginAttempts = failedLoginAttempts;
  }

  public Instant getLockedUntil() {
    return lockedUntil;
  }

  public void setLockedUntil(Instant lockedUntil) {
    this.lockedUntil = lockedUntil;
  }

  public boolean isMustChangePassword() {
    return mustChangePassword;
  }

  public void setMustChangePassword(boolean mustChangePassword) {
    this.mustChangePassword = mustChangePassword;
  }

  public Set<Role> getRoles() {
    return roles;
  }

  public Company getCompany() {
    return company;
  }

  public void setCompany(Company company) {
    this.company = company;
  }

  public void clearCompany() {
    this.company = null;
  }

  public boolean belongsToCompanyCode(String companyCode) {
    if (companyCode == null
        || companyCode.isBlank()
        || company == null
        || company.getCode() == null) {
      return false;
    }
    return company.getCode().equalsIgnoreCase(companyCode);
  }

  public void addRole(Role role) {
    roles.add(role);
  }

  public String getMfaSecret() {
    return mfaSecret;
  }

  public void setMfaSecret(String mfaSecret) {
    this.mfaSecret = mfaSecret;
  }

  public boolean isMfaEnabled() {
    return mfaEnabled;
  }

  public void setMfaEnabled(boolean mfaEnabled) {
    this.mfaEnabled = mfaEnabled;
  }

  public List<String> getMfaRecoveryCodeHashes() {
    if (mfaRecoveryCodes == null || mfaRecoveryCodes.isBlank()) {
      return new ArrayList<>();
    }
    return new ArrayList<>(
        Arrays.stream(mfaRecoveryCodes.split(",")).filter(entry -> !entry.isBlank()).toList());
  }

  public void setMfaRecoveryCodeHashes(List<String> hashes) {
    if (hashes == null || hashes.isEmpty()) {
      this.mfaRecoveryCodes = null;
    } else {
      this.mfaRecoveryCodes = String.join(",", hashes);
    }
  }

  public void removeRecoveryCodeHash(String hash) {
    List<String> hashes = getMfaRecoveryCodeHashes();
    if (hashes.remove(hash)) {
      setMfaRecoveryCodeHashes(hashes);
    }
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  private String normalizeScopeCode(String authScopeCode) {
    if (authScopeCode == null) {
      return null;
    }
    return authScopeCode.trim().toUpperCase(Locale.ROOT);
  }
}
