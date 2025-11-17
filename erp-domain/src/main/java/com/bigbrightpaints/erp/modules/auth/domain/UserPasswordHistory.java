package com.bigbrightpaints.erp.modules.auth.domain;

import jakarta.persistence.*;

import java.time.Instant;
import com.bigbrightpaints.erp.core.domain.VersionedEntity;

@Entity
@Table(name = "user_password_history")
public class UserPasswordHistory extends VersionedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private UserAccount user;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "changed_at", nullable = false)
    private Instant changedAt;

    protected UserPasswordHistory() {
    }

    public UserPasswordHistory(UserAccount user, String passwordHash) {
        this.user = user;
        this.passwordHash = passwordHash;
        this.changedAt = Instant.now();
    }

    @PrePersist
    public void prePersist() {
        if (changedAt == null) {
            changedAt = Instant.now();
        }
    }

    public Long getId() {
        return id;
    }

    public UserAccount getUser() {
        return user;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public Instant getChangedAt() {
        return changedAt;
    }
}

