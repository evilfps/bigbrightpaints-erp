package com.bigbrightpaints.erp.modules.hr.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.bigbrightpaints.erp.modules.hr.domain.Employee;
import com.bigbrightpaints.erp.modules.hr.domain.PayrollRun;
import com.bigbrightpaints.erp.modules.hr.domain.SalaryStructureTemplate;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class StatutoryDeductionEngineTest {

    private final StatutoryDeductionEngine engine = new StatutoryDeductionEngine();

    @Test
    void calculatePfDeduction_usesTemplateRateWhenPresent() {
        Employee employee = new Employee();
        SalaryStructureTemplate template = new SalaryStructureTemplate();
        template.setEmployeePfRate(new BigDecimal("10.00"));
        employee.setSalaryStructureTemplate(template);

        BigDecimal deduction = engine.calculatePfDeduction(new BigDecimal("15000.00"), employee);

        assertThat(deduction).isEqualByComparingTo("1500.00");
    }

    @Test
    void calculateEsiDeduction_appliesOnlyBelowEligibilityThreshold() {
        Employee employee = new Employee();
        SalaryStructureTemplate template = new SalaryStructureTemplate();
        template.setEmployeeEsiRate(new BigDecimal("0.75"));
        template.setEsiEligibilityThreshold(new BigDecimal("21000.00"));
        employee.setSalaryStructureTemplate(template);

        BigDecimal eligibleDeduction = engine.calculateEsiDeduction(new BigDecimal("20000.00"), employee);
        BigDecimal ineligibleDeduction = engine.calculateEsiDeduction(new BigDecimal("22000.00"), employee);

        assertThat(eligibleDeduction).isEqualByComparingTo("150.00");
        assertThat(ineligibleDeduction).isEqualByComparingTo("0.00");
    }

    @Test
    void calculateTdsDeduction_projectsAnnualGrossByRunTypeAndTaxRegime() {
        Employee employee = new Employee();
        employee.setTaxRegime(Employee.TaxRegime.OLD);

        PayrollRun monthlyRun = new PayrollRun();
        monthlyRun.setRunType(PayrollRun.RunType.MONTHLY);
        PayrollRun weeklyRun = new PayrollRun();
        weeklyRun.setRunType(PayrollRun.RunType.WEEKLY);

        BigDecimal monthlyTds = engine.calculateTdsDeduction(new BigDecimal("30000.00"), monthlyRun, employee);
        BigDecimal weeklyTds = engine.calculateTdsDeduction(new BigDecimal("6000.00"), weeklyRun, employee);

        assertThat(monthlyTds).isEqualByComparingTo("916.67");
        assertThat(weeklyTds).isEqualByComparingTo("119.23");
    }

    @Test
    void calculateProfessionalTaxDeduction_appliesOnlyForMonthlyRuns() {
        Employee employee = new Employee();
        SalaryStructureTemplate template = new SalaryStructureTemplate();
        template.setProfessionalTax(new BigDecimal("200.00"));
        employee.setSalaryStructureTemplate(template);

        PayrollRun monthlyRun = new PayrollRun();
        monthlyRun.setRunType(PayrollRun.RunType.MONTHLY);
        PayrollRun weeklyRun = new PayrollRun();
        weeklyRun.setRunType(PayrollRun.RunType.WEEKLY);

        BigDecimal monthly = engine.calculateProfessionalTaxDeduction(employee, monthlyRun);
        BigDecimal weekly = engine.calculateProfessionalTaxDeduction(employee, weeklyRun);
        BigDecimal summaryMode = engine.calculateProfessionalTaxDeduction(employee, null);

        assertThat(monthly).isEqualByComparingTo("200.00");
        assertThat(weekly).isEqualByComparingTo("0.00");
        assertThat(summaryMode).isEqualByComparingTo("200.00");
    }
}
