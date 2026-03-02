package com.bigbrightpaints.erp.modules.hr.domain;

import com.bigbrightpaints.erp.core.domain.VersionedEntity;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
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

    @Column(name = "date_of_birth")
    private LocalDate dateOfBirth;

    @Enumerated(EnumType.STRING)
    @Column(name = "gender")
    private Gender gender;

    @Column(name = "emergency_contact_name")
    private String emergencyContactName;

    @Column(name = "emergency_contact_phone")
    private String emergencyContactPhone;

    @Column(name = "department")
    private String department;

    @Column(name = "designation")
    private String designation;

    @Column(name = "date_of_joining")
    private LocalDate dateOfJoining;

    @Enumerated(EnumType.STRING)
    @Column(name = "employment_type")
    private EmploymentType employmentType = EmploymentType.FULL_TIME;

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

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "salary_structure_template_id")
    private SalaryStructureTemplate salaryStructureTemplate;

    @Column(name = "pf_number")
    private String pfNumber;

    @Column(name = "esi_number")
    private String esiNumber;

    @Column(name = "pan_number")
    private String panNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "tax_regime")
    private TaxRegime taxRegime = TaxRegime.NEW;

    // Bank details for payment
    @Column(name = "bank_account_number")
    private String bankAccountNumber;

    @Column(name = "bank_name")
    private String bankName;

    @Column(name = "ifsc_code")
    private String ifscCode;

    @Column(name = "bank_account_number_encrypted")
    private String bankAccountNumberEncrypted;

    @Column(name = "bank_name_encrypted")
    private String bankNameEncrypted;

    @Column(name = "ifsc_code_encrypted")
    private String ifscCodeEncrypted;

    @Column(name = "bank_branch_encrypted")
    private String bankBranchEncrypted;

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
    public LocalDate getDateOfBirth() { return dateOfBirth; }
    public void setDateOfBirth(LocalDate dateOfBirth) { this.dateOfBirth = dateOfBirth; }
    public Gender getGender() { return gender; }
    public void setGender(Gender gender) { this.gender = gender; }
    public String getEmergencyContactName() { return emergencyContactName; }
    public void setEmergencyContactName(String emergencyContactName) { this.emergencyContactName = emergencyContactName; }
    public String getEmergencyContactPhone() { return emergencyContactPhone; }
    public void setEmergencyContactPhone(String emergencyContactPhone) { this.emergencyContactPhone = emergencyContactPhone; }
    public String getDepartment() { return department; }
    public void setDepartment(String department) { this.department = department; }
    public String getDesignation() { return designation; }
    public void setDesignation(String designation) { this.designation = designation; }
    public LocalDate getDateOfJoining() { return dateOfJoining; }
    public void setDateOfJoining(LocalDate dateOfJoining) { this.dateOfJoining = dateOfJoining; }
    public EmploymentType getEmploymentType() { return employmentType; }
    public void setEmploymentType(EmploymentType employmentType) { this.employmentType = employmentType; }
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
    public SalaryStructureTemplate getSalaryStructureTemplate() { return salaryStructureTemplate; }
    public void setSalaryStructureTemplate(SalaryStructureTemplate salaryStructureTemplate) { this.salaryStructureTemplate = salaryStructureTemplate; }
    public String getPfNumber() { return pfNumber; }
    public void setPfNumber(String pfNumber) { this.pfNumber = pfNumber; }
    public String getEsiNumber() { return esiNumber; }
    public void setEsiNumber(String esiNumber) { this.esiNumber = esiNumber; }
    public String getPanNumber() { return panNumber; }
    public void setPanNumber(String panNumber) { this.panNumber = panNumber; }
    public TaxRegime getTaxRegime() { return taxRegime; }
    public void setTaxRegime(TaxRegime taxRegime) { this.taxRegime = taxRegime; }
    public String getBankAccountNumber() { return bankAccountNumber; }
    public void setBankAccountNumber(String bankAccountNumber) { this.bankAccountNumber = bankAccountNumber; }
    public String getBankName() { return bankName; }
    public void setBankName(String bankName) { this.bankName = bankName; }
    public String getIfscCode() { return ifscCode; }
    public void setIfscCode(String ifscCode) { this.ifscCode = ifscCode; }
    public String getBankAccountNumberEncrypted() { return bankAccountNumberEncrypted; }
    public void setBankAccountNumberEncrypted(String bankAccountNumberEncrypted) {
        this.bankAccountNumberEncrypted = bankAccountNumberEncrypted;
    }
    public String getBankNameEncrypted() { return bankNameEncrypted; }
    public void setBankNameEncrypted(String bankNameEncrypted) { this.bankNameEncrypted = bankNameEncrypted; }
    public String getIfscCodeEncrypted() { return ifscCodeEncrypted; }
    public void setIfscCodeEncrypted(String ifscCodeEncrypted) { this.ifscCodeEncrypted = ifscCodeEncrypted; }
    public String getBankBranchEncrypted() { return bankBranchEncrypted; }
    public void setBankBranchEncrypted(String bankBranchEncrypted) { this.bankBranchEncrypted = bankBranchEncrypted; }
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
    public BigDecimal getConfiguredStandardHoursPerDay() { return standardHoursPerDay; }
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

    public enum Gender {
        MALE,
        FEMALE,
        OTHER,
        UNDISCLOSED
    }

    public enum EmploymentType {
        FULL_TIME,
        PART_TIME,
        CONTRACT,
        INTERN
    }

    public enum TaxRegime {
        OLD,
        NEW
    }
}
