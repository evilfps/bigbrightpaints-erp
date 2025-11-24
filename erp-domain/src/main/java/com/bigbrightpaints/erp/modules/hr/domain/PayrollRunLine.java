package com.bigbrightpaints.erp.modules.hr.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;

@Entity
@Table(name = "payroll_run_lines")
public class PayrollRunLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "payroll_run_id", nullable = false)
    private PayrollRun payrollRun;

    @Column(nullable = false)
    private String name;

    @Column(name = "days_worked", nullable = false)
    private Integer daysWorked;

    @Column(name = "daily_wage", nullable = false)
    private BigDecimal dailyWage;

    @Column(name = "advances", nullable = false)
    private BigDecimal advances;

    @Column(name = "line_total", nullable = false)
    private BigDecimal lineTotal;

    @Column(name = "notes")
    private String notes;

    public Long getId() {
        return id;
    }

    public PayrollRun getPayrollRun() {
        return payrollRun;
    }

    public void setPayrollRun(PayrollRun payrollRun) {
        this.payrollRun = payrollRun;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Integer getDaysWorked() {
        return daysWorked;
    }

    public void setDaysWorked(Integer daysWorked) {
        this.daysWorked = daysWorked;
    }

    public BigDecimal getDailyWage() {
        return dailyWage;
    }

    public void setDailyWage(BigDecimal dailyWage) {
        this.dailyWage = dailyWage;
    }

    public BigDecimal getAdvances() {
        return advances;
    }

    public void setAdvances(BigDecimal advances) {
        this.advances = advances;
    }

    public BigDecimal getLineTotal() {
        return lineTotal;
    }

    public void setLineTotal(BigDecimal lineTotal) {
        this.lineTotal = lineTotal;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }
}
