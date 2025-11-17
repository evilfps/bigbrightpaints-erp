package com.bigbrightpaints.erp.modules.auth.domain;

import jakarta.persistence.*;
import com.bigbrightpaints.erp.core.domain.VersionedEntity;
import java.time.LocalDateTime;

/**
 * Entity representing an MFA recovery code.
 * Each code can be used only once and is stored as a BCrypt hash.
 */
@Entity
@Table(name = "mfa_recovery_codes")
public class MfaRecoveryCode extends VersionedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private UserAccount user;

    @Column(name = "code_hash", nullable = false)
    private String codeHash;

    @Column(name = "used_at")
    private LocalDateTime usedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    // Constructors
    public MfaRecoveryCode() {
    }

    public MfaRecoveryCode(UserAccount user, String codeHash) {
        this.user = user;
        this.codeHash = codeHash;
        this.createdAt = LocalDateTime.now();
    }

    // Business methods
    public boolean isUsed() {
        return usedAt != null;
    }

    public void markAsUsed() {
        if (this.usedAt == null) {
            this.usedAt = LocalDateTime.now();
        }
    }

    // Getters and Setters
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public UserAccount getUser() {
        return user;
    }

    public void setUser(UserAccount user) {
        this.user = user;
    }

    public String getCodeHash() {
        return codeHash;
    }

    public void setCodeHash(String codeHash) {
        this.codeHash = codeHash;
    }

    public LocalDateTime getUsedAt() {
        return usedAt;
    }

    public void setUsedAt(LocalDateTime usedAt) {
        this.usedAt = usedAt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}