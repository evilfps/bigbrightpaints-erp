package com.bigbrightpaints.erp.modules.hr.domain;

import com.bigbrightpaints.erp.modules.company.domain.Company;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Represents a payroll run/batch for a period.
 * Can be WEEKLY (for labourers) or MONTHLY (for staff).
 */
@Entity
@Table(name = "payroll_runs",
       indexes = {
           @Index(name = "idx_payroll_run_period", columnList = "company_id, period_start, period_end"),
           @Index(name = "idx_payroll_run_status", columnList = "company_id, status")
       })
public class PayrollRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "public_id", nullable = false)
    private UUID publicId;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "company_id")
    private Company company;

    @Column(name = "run_number", nullable = false)
    private String runNumber; // e.g., PR-2024-W52, PR-2024-12

    @Enumerated(EnumType.STRING)
    @Column(name = "run_type", nullable = false)
    private RunType runType; // WEEKLY or MONTHLY

    @Column(name = "period_start", nullable = false)
    private LocalDate periodStart;

    @Column(name = "period_end", nullable = false)
    private LocalDate periodEnd;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PayrollStatus status = PayrollStatus.DRAFT;

    // Summary totals
    @Column(name = "total_employees")
    private Integer totalEmployees = 0;

    @Column(name = "total_present_days", precision = 10, scale = 2)
    private BigDecimal totalPresentDays = BigDecimal.ZERO;

    @Column(name = "total_overtime_hours", precision = 10, scale = 2)
    private BigDecimal totalOvertimeHours = BigDecimal.ZERO;

    @Column(name = "total_base_pay", precision = 19, scale = 2)
    private BigDecimal totalBasePay = BigDecimal.ZERO;

    @Column(name = "total_overtime_pay", precision = 19, scale = 2)
    private BigDecimal totalOvertimePay = BigDecimal.ZERO;

    @Column(name = "total_deductions", precision = 19, scale = 2)
    private BigDecimal totalDeductions = BigDecimal.ZERO;

    @Column(name = "total_net_pay", precision = 19, scale = 2)
    private BigDecimal totalNetPay = BigDecimal.ZERO;

    // Journal entry reference when posted
    @Column(name = "journal_entry_id")
    private Long journalEntryId;

    // Reference to accounting journal entry (for backward compatibility)
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "journal_entry_ref_id")
    private com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry journalEntry;

    // Backward compatibility fields from old PayrollRun
    @Column(name = "run_date")
    private java.time.LocalDate runDate;

    private String notes;

    @Column(name = "total_amount", precision = 19, scale = 2)
    private java.math.BigDecimal totalAmount;

    @Column(name = "processed_by")
    private String processedBy;

    @Column(name = "idempotency_key")
    private String idempotencyKey;

    @Column(name = "created_by")
    private String createdBy;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "approved_by")
    private String approvedBy;

    @Column(name = "approved_at")
    private Instant approvedAt;

    @Column(name = "posted_by")
    private String postedBy;

    @Column(name = "posted_at")
    private Instant postedAt;

    private String remarks;

    @PrePersist
    public void prePersist() {
        if (publicId == null) publicId = UUID.randomUUID();
        if (createdAt == null) createdAt = Instant.now();
    }

    // Getters and Setters
    public Long getId() { return id; }
    public UUID getPublicId() { return publicId; }
    public Company getCompany() { return company; }
    public void setCompany(Company company) { this.company = company; }
    public String getRunNumber() { return runNumber; }
    public void setRunNumber(String runNumber) { this.runNumber = runNumber; }
    public RunType getRunType() { return runType; }
    public void setRunType(RunType runType) { this.runType = runType; }
    public LocalDate getPeriodStart() { return periodStart; }
    public void setPeriodStart(LocalDate periodStart) { this.periodStart = periodStart; }
    public LocalDate getPeriodEnd() { return periodEnd; }
    public void setPeriodEnd(LocalDate periodEnd) { this.periodEnd = periodEnd; }
    public PayrollStatus getStatus() { return status; }
    public void setStatus(PayrollStatus status) { this.status = status; }
    // Backward compatibility: accept String status
    public void setStatus(String status) { 
        try {
            this.status = PayrollStatus.valueOf(status);
        } catch (IllegalArgumentException e) {
            // Map old statuses to new ones
            this.status = switch(status.toUpperCase()) {
                case "PROCESSING" -> PayrollStatus.CALCULATED;
                case "PAID" -> PayrollStatus.PAID;
                default -> PayrollStatus.DRAFT;
            };
        }
    }
    public String getStatusString() { return status != null ? status.name() : "DRAFT"; }
    public Integer getTotalEmployees() { return totalEmployees; }
    public void setTotalEmployees(Integer totalEmployees) { this.totalEmployees = totalEmployees; }
    public BigDecimal getTotalPresentDays() { return totalPresentDays; }
    public void setTotalPresentDays(BigDecimal totalPresentDays) { this.totalPresentDays = totalPresentDays; }
    public BigDecimal getTotalOvertimeHours() { return totalOvertimeHours; }
    public void setTotalOvertimeHours(BigDecimal totalOvertimeHours) { this.totalOvertimeHours = totalOvertimeHours; }
    public BigDecimal getTotalBasePay() { return totalBasePay; }
    public void setTotalBasePay(BigDecimal totalBasePay) { this.totalBasePay = totalBasePay; }
    public BigDecimal getTotalOvertimePay() { return totalOvertimePay; }
    public void setTotalOvertimePay(BigDecimal totalOvertimePay) { this.totalOvertimePay = totalOvertimePay; }
    public BigDecimal getTotalDeductions() { return totalDeductions; }
    public void setTotalDeductions(BigDecimal totalDeductions) { this.totalDeductions = totalDeductions; }
    public BigDecimal getTotalNetPay() { return totalNetPay; }
    public void setTotalNetPay(BigDecimal totalNetPay) { this.totalNetPay = totalNetPay; }
    public Long getJournalEntryId() { return journalEntryId; }
    public void setJournalEntryId(Long journalEntryId) { this.journalEntryId = journalEntryId; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public String getApprovedBy() { return approvedBy; }
    public void setApprovedBy(String approvedBy) { this.approvedBy = approvedBy; }
    public Instant getApprovedAt() { return approvedAt; }
    public void setApprovedAt(Instant approvedAt) { this.approvedAt = approvedAt; }
    public String getPostedBy() { return postedBy; }
    public void setPostedBy(String postedBy) { this.postedBy = postedBy; }
    public Instant getPostedAt() { return postedAt; }
    public void setPostedAt(Instant postedAt) { this.postedAt = postedAt; }
    public String getRemarks() { return remarks; }
    public void setRemarks(String remarks) { this.remarks = remarks; }
    
    // Backward compatibility getters and setters
    public com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry getJournalEntry() { return journalEntry; }
    public void setJournalEntry(com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry journalEntry) { this.journalEntry = journalEntry; }
    public java.time.LocalDate getRunDate() { return runDate != null ? runDate : periodStart; }
    public void setRunDate(java.time.LocalDate runDate) { this.runDate = runDate; }
    public String getNotes() { return notes != null ? notes : remarks; }
    public void setNotes(String notes) { this.notes = notes; }
    public java.math.BigDecimal getTotalAmount() { return totalAmount != null ? totalAmount : totalNetPay; }
    public void setTotalAmount(java.math.BigDecimal totalAmount) { this.totalAmount = totalAmount; }
    public String getProcessedBy() { return processedBy != null ? processedBy : createdBy; }
    public void setProcessedBy(String processedBy) { this.processedBy = processedBy; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }

    public enum RunType {
        WEEKLY,   // For labourers (every week)
        MONTHLY   // For staff (end of month)
    }

    public enum PayrollStatus {
        DRAFT,      // Being prepared
        CALCULATED, // Pay calculated but not approved
        APPROVED,   // Approved for payment
        POSTED,     // Journal entries posted
        PAID,       // Payments made
        CANCELLED   // Cancelled
    }
}
