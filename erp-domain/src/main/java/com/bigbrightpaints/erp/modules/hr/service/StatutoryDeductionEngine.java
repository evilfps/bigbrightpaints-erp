package com.bigbrightpaints.erp.modules.hr.service;

import com.bigbrightpaints.erp.modules.hr.domain.Employee;
import com.bigbrightpaints.erp.modules.hr.domain.PayrollRun;
import com.bigbrightpaints.erp.modules.hr.domain.SalaryStructureTemplate;
import java.math.BigDecimal;
import java.math.RoundingMode;
import org.springframework.stereotype.Service;

@Service
public class StatutoryDeductionEngine {

    private static final BigDecimal DEFAULT_PF_RATE = new BigDecimal("12.00");
    private static final BigDecimal DEFAULT_ESI_RATE = new BigDecimal("0.75");
    private static final BigDecimal DEFAULT_ESI_THRESHOLD = new BigDecimal("21000.00");
    private static final BigDecimal TDS_RATE = new BigDecimal("0.10");
    private static final BigDecimal OLD_REGIME_EXEMPTION = new BigDecimal("250000");
    private static final BigDecimal NEW_REGIME_EXEMPTION = new BigDecimal("300000");
    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");

    public BigDecimal calculatePfDeduction(BigDecimal basicComponent, Employee employee) {
        if (basicComponent == null || basicComponent.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        SalaryStructureTemplate template = employee != null ? employee.getSalaryStructureTemplate() : null;
        BigDecimal pfRate = template != null && template.getEmployeePfRate() != null
                ? template.getEmployeePfRate()
                : DEFAULT_PF_RATE;
        if (pfRate.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        return money(basicComponent.multiply(pfRate).divide(ONE_HUNDRED, 6, RoundingMode.HALF_UP));
    }

    public BigDecimal calculateEsiDeduction(BigDecimal grossPay, Employee employee) {
        if (grossPay == null || grossPay.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        SalaryStructureTemplate template = employee != null ? employee.getSalaryStructureTemplate() : null;
        BigDecimal esiRate = template != null && template.getEmployeeEsiRate() != null
                ? template.getEmployeeEsiRate()
                : DEFAULT_ESI_RATE;
        if (esiRate.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal threshold = template != null && template.getEsiEligibilityThreshold() != null
                ? template.getEsiEligibilityThreshold()
                : DEFAULT_ESI_THRESHOLD;
        if (grossPay.compareTo(threshold) > 0) {
            return BigDecimal.ZERO;
        }
        return money(grossPay.multiply(esiRate).divide(ONE_HUNDRED, 6, RoundingMode.HALF_UP));
    }

    public BigDecimal calculateTdsDeduction(BigDecimal grossPay, PayrollRun run, Employee employee) {
        if (grossPay == null || grossPay.compareTo(BigDecimal.ZERO) <= 0 || run == null) {
            return BigDecimal.ZERO;
        }
        int periodsPerYear = run.getRunType() == PayrollRun.RunType.WEEKLY ? 52 : 12;
        BigDecimal projectedAnnualGross = grossPay.multiply(BigDecimal.valueOf(periodsPerYear));
        Employee.TaxRegime regime = employee != null && employee.getTaxRegime() != null
                ? employee.getTaxRegime()
                : Employee.TaxRegime.NEW;
        BigDecimal annualExemption = regime == Employee.TaxRegime.OLD
                ? OLD_REGIME_EXEMPTION
                : NEW_REGIME_EXEMPTION;
        BigDecimal taxableAnnual = projectedAnnualGross.subtract(annualExemption);
        if (taxableAnnual.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal annualTax = taxableAnnual.multiply(TDS_RATE);
        return money(annualTax.divide(BigDecimal.valueOf(periodsPerYear), 6, RoundingMode.HALF_UP));
    }

    public BigDecimal calculateProfessionalTaxDeduction(Employee employee, PayrollRun run) {
        if (run != null && run.getRunType() != PayrollRun.RunType.MONTHLY) {
            return BigDecimal.ZERO;
        }
        SalaryStructureTemplate template = employee != null ? employee.getSalaryStructureTemplate() : null;
        if (template == null || template.getProfessionalTax() == null) {
            return BigDecimal.ZERO;
        }
        return money(template.getProfessionalTax());
    }

    private BigDecimal nonNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private BigDecimal money(BigDecimal value) {
        return nonNull(value).setScale(2, RoundingMode.HALF_UP);
    }
}
