package com.bigbrightpaints.erp.regression;

import com.bigbrightpaints.erp.core.security.CompanyContextHolder;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.hr.domain.PayrollRunRepository;
import com.bigbrightpaints.erp.modules.hr.dto.PayrollRunRequest;
import com.bigbrightpaints.erp.modules.hr.service.HrService;
import com.bigbrightpaints.erp.test.AbstractIntegrationTest;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Regression: Payroll idempotency keys are company-scoped")
class PayrollIdempotencyCompanyScopeRegressionIT extends AbstractIntegrationTest {

    private static final String COMPANY_A = "LF-007-A";
    private static final String COMPANY_B = "LF-007-B";

    @Autowired private HrService hrService;
    @Autowired private PayrollRunRepository payrollRunRepository;

    @AfterEach
    void tearDown() {
        CompanyContextHolder.clear();
    }

    @Test
    void sameIdempotencyKeyAcrossCompaniesIsAllowed() {
        Company companyA = dataSeeder.ensureCompany(COMPANY_A, "LF-007 A Ltd");
        Company companyB = dataSeeder.ensureCompany(COMPANY_B, "LF-007 B Ltd");

        PayrollRunRequest request = new PayrollRunRequest(
                LocalDate.of(2026, 1, 10),
                new BigDecimal("1500.00"),
                "Cross-company idempotency",
                "PAYROLL-IDEMP-007"
        );

        CompanyContextHolder.setCompanyId(COMPANY_A);
        hrService.createPayrollRun(request);

        CompanyContextHolder.setCompanyId(COMPANY_B);
        hrService.createPayrollRun(request);

        assertThat(payrollRunRepository.findByCompanyAndIdempotencyKey(companyA, "PAYROLL-IDEMP-007"))
                .isPresent();
        assertThat(payrollRunRepository.findByCompanyAndIdempotencyKey(companyB, "PAYROLL-IDEMP-007"))
                .isPresent();
    }
}
