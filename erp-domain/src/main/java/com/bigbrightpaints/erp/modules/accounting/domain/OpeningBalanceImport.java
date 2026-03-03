package com.bigbrightpaints.erp.modules.accounting.domain;

import com.bigbrightpaints.erp.core.domain.VersionedEntity;
import com.bigbrightpaints.erp.core.util.CompanyTime;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;

@Entity
@Table(name = "opening_balance_imports",
        uniqueConstraints = @UniqueConstraint(name = "uq_opening_balance_import_company_key",
                columnNames = {"company_id", "idempotency_key"}))
public class OpeningBalanceImport extends VersionedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "company_id")
    private Company company;

    @Column(name = "idempotency_key", nullable = false, length = 128)
    private String idempotencyKey;

    @Column(name = "idempotency_hash", length = 64)
    private String idempotencyHash;

    @Column(name = "reference_number", length = 128)
    private String referenceNumber;

    @Column(name = "file_hash", length = 64)
    private String fileHash;

    @Column(name = "file_name", length = 256)
    private String fileName;

    @Column(name = "journal_entry_id")
    private Long journalEntryId;

    @Column(name = "rows_processed", nullable = false)
    private int rowsProcessed;

    @Column(name = "accounts_created", nullable = false)
    private int accountsCreated;

    @Column(name = "errors_json", columnDefinition = "text")
    private String errorsJson;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) {
            createdAt = CompanyTime.now(company);
        }
    }

    public Long getId() {
        return id;
    }

    public Company getCompany() {
        return company;
    }

    public void setCompany(Company company) {
        this.company = company;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }

    public String getIdempotencyHash() {
        return idempotencyHash;
    }

    public void setIdempotencyHash(String idempotencyHash) {
        this.idempotencyHash = idempotencyHash;
    }

    public String getReferenceNumber() {
        return referenceNumber;
    }

    public void setReferenceNumber(String referenceNumber) {
        this.referenceNumber = referenceNumber;
    }

    public String getFileHash() {
        return fileHash;
    }

    public void setFileHash(String fileHash) {
        this.fileHash = fileHash;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public Long getJournalEntryId() {
        return journalEntryId;
    }

    public void setJournalEntryId(Long journalEntryId) {
        this.journalEntryId = journalEntryId;
    }

    public int getRowsProcessed() {
        return rowsProcessed;
    }

    public void setRowsProcessed(int rowsProcessed) {
        this.rowsProcessed = rowsProcessed;
    }

    public int getAccountsCreated() {
        return accountsCreated;
    }

    public void setAccountsCreated(int accountsCreated) {
        this.accountsCreated = accountsCreated;
    }

    public String getErrorsJson() {
        return errorsJson;
    }

    public void setErrorsJson(String errorsJson) {
        this.errorsJson = errorsJson;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
