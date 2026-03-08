package com.bigbrightpaints.erp.modules.inventory.domain;

import com.bigbrightpaints.erp.core.util.CompanyTime;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrder;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import com.bigbrightpaints.erp.core.domain.VersionedEntity;
import java.util.UUID;

@Entity
@Table(name = "packaging_slips", uniqueConstraints = @UniqueConstraint(columnNames = {"company_id", "slip_number"}))
public class PackagingSlip extends VersionedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "public_id", nullable = false)
    private UUID publicId;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "company_id")
    private Company company;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "sales_order_id")
    private SalesOrder salesOrder;

    @Column(name = "slip_number", nullable = false)
    private String slipNumber;

    @Column(nullable = false)
    private String status = "PENDING";

    @Column(name = "is_backorder", nullable = false)
    private boolean isBackorder;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "dispatched_at")
    private Instant dispatchedAt;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    @Column(name = "confirmed_by")
    private String confirmedBy;

    @Column(name = "dispatch_notes", length = 1000)
    private String dispatchNotes;

    @Column(name = "transporter_name", length = 255)
    private String transporterName;

    @Column(name = "driver_name", length = 255)
    private String driverName;

    @Column(name = "vehicle_number", length = 120)
    private String vehicleNumber;

    @Column(name = "challan_reference", length = 255)
    private String challanReference;

    @Column(name = "journal_entry_id")
    private Long journalEntryId;

    @Column(name = "cogs_journal_entry_id")
    private Long cogsJournalEntryId;

    @Column(name = "invoice_id")
    private Long invoiceId;

    @OneToMany(mappedBy = "packagingSlip", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<PackagingSlipLine> lines = new ArrayList<>();

    @PrePersist
    public void prePersist() {
        if (publicId == null) {
            publicId = UUID.randomUUID();
        }
        if (createdAt == null) {
            createdAt = CompanyTime.now(company);
        }
    }

    public Long getId() { return id; }
    public UUID getPublicId() { return publicId; }
    public Company getCompany() { return company; }
    public void setCompany(Company company) { this.company = company; }
    public SalesOrder getSalesOrder() { return salesOrder; }
    public void setSalesOrder(SalesOrder salesOrder) { this.salesOrder = salesOrder; }
    public String getSlipNumber() { return slipNumber; }
    public void setSlipNumber(String slipNumber) { this.slipNumber = slipNumber; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public boolean isBackorder() { return isBackorder; }
    public void setBackorder(boolean backorder) { isBackorder = backorder; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getDispatchedAt() { return dispatchedAt; }
    public void setDispatchedAt(Instant dispatchedAt) { this.dispatchedAt = dispatchedAt; }
    public Instant getConfirmedAt() { return confirmedAt; }
    public void setConfirmedAt(Instant confirmedAt) { this.confirmedAt = confirmedAt; }
    public String getConfirmedBy() { return confirmedBy; }
    public void setConfirmedBy(String confirmedBy) { this.confirmedBy = confirmedBy; }
    public String getDispatchNotes() { return dispatchNotes; }
    public void setDispatchNotes(String dispatchNotes) { this.dispatchNotes = dispatchNotes; }
    public String getTransporterName() { return transporterName; }
    public void setTransporterName(String transporterName) { this.transporterName = transporterName; }
    public String getDriverName() { return driverName; }
    public void setDriverName(String driverName) { this.driverName = driverName; }
    public String getVehicleNumber() { return vehicleNumber; }
    public void setVehicleNumber(String vehicleNumber) { this.vehicleNumber = vehicleNumber; }
    public String getChallanReference() { return challanReference; }
    public void setChallanReference(String challanReference) { this.challanReference = challanReference; }
    public Long getJournalEntryId() { return journalEntryId; }
    public void setJournalEntryId(Long journalEntryId) { this.journalEntryId = journalEntryId; }
    public Long getCogsJournalEntryId() { return cogsJournalEntryId; }
    public void setCogsJournalEntryId(Long cogsJournalEntryId) { this.cogsJournalEntryId = cogsJournalEntryId; }
    public Long getInvoiceId() { return invoiceId; }
    public void setInvoiceId(Long invoiceId) { this.invoiceId = invoiceId; }
    public List<PackagingSlipLine> getLines() { return lines; }
}
