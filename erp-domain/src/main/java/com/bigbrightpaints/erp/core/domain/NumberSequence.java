package com.bigbrightpaints.erp.core.domain;

import com.bigbrightpaints.erp.modules.company.domain.Company;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;

@Entity
@Table(name = "number_sequences",
        uniqueConstraints = @UniqueConstraint(columnNames = {"company_id", "sequence_key"}))
public class NumberSequence extends VersionedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "company_id")
    private Company company;

    @Column(name = "sequence_key", nullable = false, length = 128)
    private String sequenceKey;

    @Column(name = "next_value", nullable = false)
    private Long nextValue = 1L;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public Long getId() {
        return id;
    }

    public Company getCompany() {
        return company;
    }

    public void setCompany(Company company) {
        this.company = company;
    }

    public String getSequenceKey() {
        return sequenceKey;
    }

    public void setSequenceKey(String sequenceKey) {
        this.sequenceKey = sequenceKey;
    }

    public Long getNextValue() {
        return nextValue;
    }

    public void setNextValue(Long nextValue) {
        this.nextValue = nextValue;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Long consumeAndIncrement() {
        Long current = this.nextValue;
        this.nextValue = current + 1;
        this.updatedAt = Instant.now();
        return current;
    }
}
