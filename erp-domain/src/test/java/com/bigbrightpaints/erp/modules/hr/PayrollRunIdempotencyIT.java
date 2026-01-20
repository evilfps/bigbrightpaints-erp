package com.bigbrightpaints.erp.modules.hr;

import com.bigbrightpaints.erp.core.security.CompanyContextHolder;
import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.hr.domain.PayrollRunRepository;
import com.bigbrightpaints.erp.modules.hr.dto.PayrollRunDto;
import com.bigbrightpaints.erp.modules.hr.dto.PayrollRunRequest;
import com.bigbrightpaints.erp.modules.hr.service.HrService;
import com.bigbrightpaints.erp.test.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("HR: Payroll run idempotency scope")
public class PayrollRunIdempotencyIT extends AbstractIntegrationTest {

    @Autowired
    private HrService hrService;

    @Autowired
    private PayrollRunRepository payrollRunRepository;

    @AfterEach
    void clearContext() {
        CompanyContextHolder.clear();
    }

    @Test
    void idempotency_key_is_scoped_by_company() {
        Company companyA = dataSeeder.ensureCompany("IDEMP-A", "Idempotency A Co");
        Company companyB = dataSeeder.ensureCompany("IDEMP-B", "Idempotency B Co");
        String key = "LF-007-IDEMP";
        LocalDate runDate = LocalDate.now();

        CompanyContextHolder.setCompanyId(companyA.getCode());
        PayrollRunDto runA1 = hrService.createPayrollRun(new PayrollRunRequest(
                runDate,
                new BigDecimal("1500.00"),
                "Payroll run A",
                key
        ));
        PayrollRunDto runA2 = hrService.createPayrollRun(new PayrollRunRequest(
                runDate,
                new BigDecimal("1500.00"),
                "Payroll run A repeat",
                key
        ));
        assertThat(runA2.id()).isEqualTo(runA1.id());

        assertThatThrownBy(() -> hrService.createPayrollRun(new PayrollRunRequest(
                runDate,
                new BigDecimal("1750.00"),
                "Payroll run A updated",
                key
        )))
                .isInstanceOf(ApplicationException.class)
                .extracting(ex -> ((ApplicationException) ex).getErrorCode())
                .isEqualTo(ErrorCode.CONCURRENCY_CONFLICT);

        CompanyContextHolder.setCompanyId(companyB.getCode());
        PayrollRunDto runB1 = hrService.createPayrollRun(new PayrollRunRequest(
                runDate,
                new BigDecimal("1500.00"),
                "Payroll run B",
                key
        ));
        assertThat(runB1.id()).isNotEqualTo(runA1.id());

        assertThat(payrollRunRepository.findByCompanyAndIdempotencyKey(companyA, key)).isPresent();
        assertThat(payrollRunRepository.findByCompanyAndIdempotencyKey(companyB, key)).isPresent();
    }
}
