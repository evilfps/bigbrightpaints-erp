package com.bigbrightpaints.erp.modules.inventory.domain;

import jakarta.persistence.*;

import java.math.BigDecimal;
import com.bigbrightpaints.erp.core.domain.VersionedEntity;
import java.time.Instant;

@Entity
@Table(name = "inventory_movements")
public class InventoryMovement extends VersionedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "finished_good_id")
    private FinishedGood finishedGood;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "finished_good_batch_id")
    private FinishedGoodBatch finishedGoodBatch;

    @Column(name = "reference_type", nullable = false)
    private String referenceType;

    @Column(name = "reference_id", nullable = false)
    private String referenceId;

    @Column(name = "movement_type", nullable = false)
    private String movementType;

    @Column(nullable = false)
    private BigDecimal quantity;

    @Column(name = "unit_cost", nullable = false)
    private BigDecimal unitCost = BigDecimal.ZERO;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "journal_entry_id")
    private Long journalEntryId;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public Long getId() { return id; }
    public FinishedGood getFinishedGood() { return finishedGood; }
    public void setFinishedGood(FinishedGood finishedGood) { this.finishedGood = finishedGood; }
    public FinishedGoodBatch getFinishedGoodBatch() { return finishedGoodBatch; }
    public void setFinishedGoodBatch(FinishedGoodBatch finishedGoodBatch) { this.finishedGoodBatch = finishedGoodBatch; }
    public String getReferenceType() { return referenceType; }
    public void setReferenceType(String referenceType) { this.referenceType = referenceType; }
    public String getReferenceId() { return referenceId; }
    public void setReferenceId(String referenceId) { this.referenceId = referenceId; }
    public String getMovementType() { return movementType; }
    public void setMovementType(String movementType) { this.movementType = movementType; }
    public BigDecimal getQuantity() { return quantity; }
    public void setQuantity(BigDecimal quantity) { this.quantity = quantity; }
    public BigDecimal getUnitCost() { return unitCost; }
    public void setUnitCost(BigDecimal unitCost) { this.unitCost = unitCost; }
    public Instant getCreatedAt() { return createdAt; }
    public Long getJournalEntryId() { return journalEntryId; }
    public void setJournalEntryId(Long journalEntryId) { this.journalEntryId = journalEntryId; }
}
