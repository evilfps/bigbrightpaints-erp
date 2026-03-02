package com.bigbrightpaints.erp.modules.hr.service;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.modules.hr.domain.Employee;
import java.math.BigDecimal;
import java.math.RoundingMode;
import org.springframework.stereotype.Service;

@Service
public class PayrollCalculationSupport {

    private static final BigDecimal ADVANCE_DEDUCTION_CAP = new BigDecimal("0.20");

    public BigDecimal requireValidStandardHoursPerDay(Employee employee) {
        BigDecimal configuredStandardHours = employee != null ? employee.getConfiguredStandardHoursPerDay() : null;
        if (configuredStandardHours == null || configuredStandardHours.compareTo(BigDecimal.ZERO) <= 0) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                    "Employee standardHoursPerDay must be greater than zero for payroll calculation")
                    .withDetail("employeeId", employee != null ? employee.getId() : null)
                    .withDetail("configuredStandardHoursPerDay", configuredStandardHours);
        }
        return configuredStandardHours;
    }

    public BigDecimal calculateLoanDeduction(BigDecimal grossPay, Employee employee) {
        if (employee == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal balance = employee.getAdvanceBalance();
        if (balance == null || balance.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal cap = grossPay.multiply(ADVANCE_DEDUCTION_CAP).setScale(2, RoundingMode.HALF_UP);
        return money(balance.min(cap));
    }

    private BigDecimal money(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(2, RoundingMode.HALF_UP);
    }
}
