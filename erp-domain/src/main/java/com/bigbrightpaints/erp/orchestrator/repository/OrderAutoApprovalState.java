package com.bigbrightpaints.erp.orchestrator.repository;

import com.bigbrightpaints.erp.core.domain.VersionedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;

@Entity
@Table(name = "order_auto_approval_state",
        uniqueConstraints = @UniqueConstraint(columnNames = {"company_code", "order_id"}))
public class OrderAutoApprovalState extends VersionedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "company_code", nullable = false, length = 64)
    private String companyCode;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(nullable = false)
    private String status = "PENDING";

    @Column(name = "inventory_reserved", nullable = false)
    private boolean inventoryReserved;

    @Column(name = "sales_journal_posted", nullable = false)
    private boolean salesJournalPosted;

    @Column(name = "dispatch_finalized", nullable = false)
    private boolean dispatchFinalized;

    @Column(name = "invoice_issued", nullable = false)
    private boolean invoiceIssued;

    @Column(name = "order_status_updated", nullable = false)
    private boolean orderStatusUpdated;

    @Column(name = "last_error", length = 1024)
    private String lastError;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected OrderAutoApprovalState() {
    }

    public OrderAutoApprovalState(String companyCode, Long orderId) {
        this.companyCode = companyCode;
        this.orderId = orderId;
    }

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
        if (status == null) {
            status = "PENDING";
        }
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getCompanyCode() {
        return companyCode;
    }

    public Long getOrderId() {
        return orderId;
    }

    public String getStatus() {
        return status;
    }

    public boolean isInventoryReserved() {
        return inventoryReserved;
    }

    public boolean isSalesJournalPosted() {
        return salesJournalPosted;
    }

    public boolean isDispatchFinalized() {
        return dispatchFinalized;
    }

    public boolean isInvoiceIssued() {
        return invoiceIssued;
    }

    public boolean isOrderStatusUpdated() {
        return orderStatusUpdated;
    }

    public String getLastError() {
        return lastError;
    }

    public boolean isCompleted() {
        return "COMPLETED".equalsIgnoreCase(status);
    }

    public void startAttempt() {
        status = "RUNNING";
        lastError = null;
    }

    public void markInventoryReserved() {
        inventoryReserved = true;
    }

    public void markSalesJournalPosted() {
        salesJournalPosted = true;
    }

    public void markDispatchFinalized() {
        dispatchFinalized = true;
    }

    public void markInvoiceIssued() {
        invoiceIssued = true;
    }

    public void markOrderStatusUpdated() {
        orderStatusUpdated = true;
    }

    public void markCompleted() {
        status = "COMPLETED";
        lastError = null;
    }

    public void markFailed(String error) {
        status = "FAILED";
        lastError = error;
    }
}
