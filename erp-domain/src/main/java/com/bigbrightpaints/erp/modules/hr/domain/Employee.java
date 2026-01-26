package com.bigbrightpaints.erp.modules.hr.domain;

import com.bigbrightpaints.erp.modules.company.domain.Company;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import com.bigbrightpaints.erp.core.domain.VersionedEntity;
import java.util.UUID;

@Entity
@Table(name = "employees", uniqueConstraints = @UniqueConstraint(columnNames = {"company_id", "email"}))
public class Employee extends VersionedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "public_id", nullable = false)
    private UUID publicId;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "company_id")
    private Company company;

    @Column(name = "first_name", nullable = false)
    private String firstName;

    @Column(name = "last_name", nullable = false)
    private String lastName;

    @Column(nullable = false)
    private String email;

    private String phone;

    private String role;

    @Column(nullable = false)
    private String status = "ACTIVE";

    @Column(name = "hired_date")
    private LocalDate hiredDate;

    // Employee type: STAFF (monthly salary) or LABOUR (daily wage)
    @Enumerated(EnumType.STRING)
    @Column(name = "employee_type", nullable = false)
    private EmployeeType employeeType = EmployeeType.STAFF;

    // Monthly salary for STAFF (e.g., 12000)
    @Column(name = "monthly_salary", precision = 19, scale = 2)
    private BigDecimal monthlySalary;

    // Daily wage for LABOUR (e.g., 500)
    @Column(name = "daily_wage", precision = 19, scale = 2)
    private BigDecimal dailyWage;

    // Payment schedule: MONTHLY (staff) or WEEKLY (labour)
    @Enumerated(EnumType.STRING)
    @Column(name = "payment_schedule", nullable = false)
    private PaymentSchedule paymentSchedule = PaymentSchedule.MONTHLY;

    // Working days per month (default 26, excluding 4 weekends)
    @Column(name = "working_days_per_month")
    private Integer workingDaysPerMonth = 26;

    // Days off per week (e.g., 1 for Sunday only, 2 for Sat+Sun)
    @Column(name = "weekly_off_days")
    private Integer weeklyOffDays = 1;

    // Bank details for payment
    @Column(name = "bank_account_number")
    private String bankAccountNumber;

    @Column(name = "bank_name")
    private String bankName;

    @Column(name = "ifsc_code")
    private String ifscCode;

    // Advance payment balance (deducted from salary)
    @Column(name = "advance_balance", precision = 19, scale = 2)
    private BigDecimal advanceBalance = BigDecimal.ZERO;

    // Overtime rate multipliers
    @Column(name = "overtime_rate_multiplier", precision = 5, scale = 2)
    private BigDecimal overtimeRateMultiplier = new BigDecimal("1.5"); // 1.5x for regular OT

    @Column(name = "double_ot_rate_multiplier", precision = 5, scale = 2)
    private BigDecimal doubleOtRateMultiplier = new BigDecimal("2.0"); // 2x for holiday OT

    @Column(name = "standard_hours_per_day", precision = 5, scale = 2)
    private BigDecimal standardHoursPerDay = new BigDecimal("8"); // Standard work hours

    @PrePersist
    public void prePersist() {
        if (publicId == null) {
            publicId = UUID.randomUUID();
        }
    }

    public Long getId() { return id; }
    public UUID getPublicId() { return publicId; }
    public Company getCompany() { return company; }
    public void setCompany(Company company) { this.company = company; }
    public String getFirstName() { return firstName; }
    public void setFirstName(String firstName) { this.firstName = firstName; }
    public String getLastName() { return lastName; }
    public void setLastName(String lastName) { this.lastName = lastName; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public LocalDate getHiredDate() { return hiredDate; }
    public void setHiredDate(LocalDate hiredDate) { this.hiredDate = hiredDate; }
    
    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
    public EmployeeType getEmployeeType() { return employeeType; }
    public void setEmployeeType(EmployeeType employeeType) { this.employeeType = employeeType; }
    public BigDecimal getMonthlySalary() { return monthlySalary; }
    public void setMonthlySalary(BigDecimal monthlySalary) { this.monthlySalary = monthlySalary; }
    public BigDecimal getDailyWage() { return dailyWage; }
    public void setDailyWage(BigDecimal dailyWage) { this.dailyWage = dailyWage; }
    public PaymentSchedule getPaymentSchedule() { return paymentSchedule; }
    public void setPaymentSchedule(PaymentSchedule paymentSchedule) { this.paymentSchedule = paymentSchedule; }
    public Integer getWorkingDaysPerMonth() {
        int resolved = workingDaysPerMonth != null ? workingDaysPerMonth : 26;
        return Math.max(1, resolved);
    }
    public void setWorkingDaysPerMonth(Integer workingDaysPerMonth) { this.workingDaysPerMonth = workingDaysPerMonth; }
    public Integer getWeeklyOffDays() { return weeklyOffDays != null ? weeklyOffDays : 1; }
    public void setWeeklyOffDays(Integer weeklyOffDays) { this.weeklyOffDays = weeklyOffDays; }
    public String getBankAccountNumber() { return bankAccountNumber; }
    public void setBankAccountNumber(String bankAccountNumber) { this.bankAccountNumber = bankAccountNumber; }
    public String getBankName() { return bankName; }
    public void setBankName(String bankName) { this.bankName = bankName; }
    public String getIfscCode() { return ifscCode; }
    public void setIfscCode(String ifscCode) { this.ifscCode = ifscCode; }
    public BigDecimal getAdvanceBalance() { return advanceBalance != null ? advanceBalance : BigDecimal.ZERO; }
    public void setAdvanceBalance(BigDecimal advanceBalance) { this.advanceBalance = advanceBalance; }
    public BigDecimal getOvertimeRateMultiplier() { return overtimeRateMultiplier != null ? overtimeRateMultiplier : new BigDecimal("1.5"); }
    public void setOvertimeRateMultiplier(BigDecimal overtimeRateMultiplier) { this.overtimeRateMultiplier = overtimeRateMultiplier; }
    public BigDecimal getDoubleOtRateMultiplier() { return doubleOtRateMultiplier != null ? doubleOtRateMultiplier : new BigDecimal("2.0"); }
    public void setDoubleOtRateMultiplier(BigDecimal doubleOtRateMultiplier) { this.doubleOtRateMultiplier = doubleOtRateMultiplier; }
    public BigDecimal getStandardHoursPerDay() {
        BigDecimal resolved = standardHoursPerDay != null ? standardHoursPerDay : new BigDecimal("8");
        return resolved.compareTo(BigDecimal.ZERO) > 0 ? resolved : BigDecimal.ONE;
    }
    public void setStandardHoursPerDay(BigDecimal standardHoursPerDay) { this.standardHoursPerDay = standardHoursPerDay; }

    public String getFullName() {
        return firstName + " " + lastName;
    }

    /**
     * Calculate daily rate from monthly salary
     * Monthly salary / working days per month
     */
    public BigDecimal getDailyRate() {
        if (employeeType == EmployeeType.LABOUR) {
            return dailyWage != null ? dailyWage : BigDecimal.ZERO;
        }
        if (monthlySalary == null || monthlySalary.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        int workDays = getWorkingDaysPerMonth();
        return monthlySalary.divide(new BigDecimal(workDays), 2, RoundingMode.HALF_UP);
    }

    /**
     * Calculate pay for given attendance
     */
    public BigDecimal calculatePay(int presentDays, int halfDays) {
        BigDecimal dailyRate = getDailyRate();
        BigDecimal fullDayPay = dailyRate.multiply(new BigDecimal(presentDays));
        BigDecimal halfDayPay = dailyRate.multiply(new BigDecimal(halfDays)).divide(new BigDecimal("2"), 2, RoundingMode.HALF_UP);
        return fullDayPay.add(halfDayPay);
    }

    public enum EmployeeType {
        STAFF,   // Monthly salary, paid monthly
        LABOUR   // Daily wage, paid weekly
    }

    public enum PaymentSchedule {
        MONTHLY,  // End of month
        WEEKLY    // Every Saturday
    }
}
