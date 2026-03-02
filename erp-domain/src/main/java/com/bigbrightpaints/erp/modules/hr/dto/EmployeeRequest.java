package com.bigbrightpaints.erp.modules.hr.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;

public record EmployeeRequest(
        @NotBlank String firstName,
        @NotBlank String lastName,
        @Email @NotBlank String email,
        String phone,
        String role,
        LocalDate hiredDate,
        LocalDate dateOfBirth,
        String gender,
        @Size(max = 128) String emergencyContactName,
        @Size(max = 32) String emergencyContactPhone,
        @Size(max = 128) String department,
        @Size(max = 128) String designation,
        LocalDate dateOfJoining,
        String employmentType,
        // Payroll fields
        String employeeType,        // STAFF or LABOUR
        String paymentSchedule,     // MONTHLY or WEEKLY
        Long salaryStructureTemplateId,
        BigDecimal monthlySalary,   // For STAFF
        BigDecimal dailyWage,       // For LABOUR
        Integer workingDaysPerMonth,
        Integer weeklyOffDays,
        BigDecimal standardHoursPerDay,
        BigDecimal overtimeRateMultiplier,
        BigDecimal doubleOtRateMultiplier,
        @Size(max = 64) String pfNumber,
        @Size(max = 64) String esiNumber,
        @Size(max = 16) String panNumber,
        String taxRegime,
        // Bank details (stored encrypted)
        String bankAccountNumber,
        String bankName,
        String ifscCode,
        String bankBranch
) {}
