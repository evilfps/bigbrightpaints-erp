package com.bigbrightpaints.erp.modules.rbac.domain;

import jakarta.persistence.*;
import com.bigbrightpaints.erp.core.domain.VersionedEntity;

@Entity
@Table(name = "permissions")
public class Permission extends VersionedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String code;

    private String description;

    public Long getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }
}
