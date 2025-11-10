package com.bigbrightpaints.erp.modules.invoice.domain;

import com.bigbrightpaints.erp.modules.company.domain.Company;
import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "invoice_sequences", uniqueConstraints = @UniqueConstraint(columnNames = {"company_id", "fiscal_year"}))
public class InvoiceSequence {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "company_id")
    private Company company;

    @Column(name = "fiscal_year", nullable = false)
    private Integer fiscalYear;

    @Column(name = "next_number", nullable = false)
    private Long nextNumber = 1L;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public Long getId() { return id; }
    public Company getCompany() { return company; }
    public void setCompany(Company company) { this.company = company; }
    public Integer getFiscalYear() { return fiscalYear; }
    public void setFiscalYear(Integer fiscalYear) { this.fiscalYear = fiscalYear; }
    public Long getNextNumber() { return nextNumber; }
    public void setNextNumber(Long nextNumber) { this.nextNumber = nextNumber; }
    public Instant getUpdatedAt() { return updatedAt; }

    public Long consumeAndIncrement() {
        Long current = this.nextNumber;
        this.nextNumber = current + 1;
        this.updatedAt = Instant.now();
        return current;
    }
}
