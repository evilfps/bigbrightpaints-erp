package com.bigbrightpaints.erp.modules.hr.domain;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EmployeeTest {

    @Test
    void dailyRateClampsWorkingDaysToMinimum() {
        Employee employee = new Employee();
        employee.setEmployeeType(Employee.EmployeeType.STAFF);
        employee.setMonthlySalary(new BigDecimal("2600"));
        employee.setWorkingDaysPerMonth(0);

        assertThat(employee.getWorkingDaysPerMonth()).isEqualTo(1);
        assertThat(employee.getDailyRate()).isEqualByComparingTo(new BigDecimal("2600.00"));
    }

    @Test
    void standardHoursPerDayClampedWhenZero() {
        Employee employee = new Employee();
        employee.setStandardHoursPerDay(BigDecimal.ZERO);

        assertThat(employee.getStandardHoursPerDay()).isEqualByComparingTo(BigDecimal.ONE);
    }
}
