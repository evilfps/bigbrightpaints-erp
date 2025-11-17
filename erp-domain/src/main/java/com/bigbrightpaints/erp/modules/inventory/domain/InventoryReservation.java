package com.bigbrightpaints.erp.modules.inventory.domain;

import jakarta.persistence.*;

import java.math.BigDecimal;
import com.bigbrightpaints.erp.core.domain.VersionedEntity;
import java.time.Instant;

@Entity
@Table(name = "inventory_reservations")
public class InventoryReservation extends VersionedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "raw_material_id")
    private RawMaterial rawMaterial;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "finished_good_id")
    private FinishedGood finishedGood;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "finished_good_batch_id")
    private FinishedGoodBatch finishedGoodBatch;

    @Column(name = "reference_type", nullable = false)
    private String referenceType;

    @Column(name = "reference_id", nullable = false)
    private String referenceId;

    @Column(nullable = false)
    private BigDecimal quantity;

    @Column(name = "reserved_quantity")
    private BigDecimal reservedQuantity;

    @Column(name = "fulfilled_quantity")
    private BigDecimal fulfilledQuantity;

    @Column(nullable = false)
    private String status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public Long getId() {
        return id;
    }

    public RawMaterial getRawMaterial() { return rawMaterial; }
    public void setRawMaterial(RawMaterial rawMaterial) { this.rawMaterial = rawMaterial; }
    public FinishedGood getFinishedGood() { return finishedGood; }
    public void setFinishedGood(FinishedGood finishedGood) { this.finishedGood = finishedGood; }
    public FinishedGoodBatch getFinishedGoodBatch() { return finishedGoodBatch; }
    public void setFinishedGoodBatch(FinishedGoodBatch finishedGoodBatch) { this.finishedGoodBatch = finishedGoodBatch; }

    public String getReferenceType() {
        return referenceType;
    }

    public void setReferenceType(String referenceType) {
        this.referenceType = referenceType;
    }

    public String getReferenceId() {
        return referenceId;
    }

    public void setReferenceId(String referenceId) {
        this.referenceId = referenceId;
    }

    public BigDecimal getQuantity() { return quantity; }
    public void setQuantity(BigDecimal quantity) { this.quantity = quantity; }
    public BigDecimal getReservedQuantity() { return reservedQuantity; }
    public void setReservedQuantity(BigDecimal reservedQuantity) { this.reservedQuantity = reservedQuantity; }
    public BigDecimal getFulfilledQuantity() { return fulfilledQuantity; }
    public void setFulfilledQuantity(BigDecimal fulfilledQuantity) { this.fulfilledQuantity = fulfilledQuantity; }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
