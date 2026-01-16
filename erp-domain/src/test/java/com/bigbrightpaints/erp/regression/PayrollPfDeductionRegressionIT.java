package com.bigbrightpaints.erp.regression;

import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryRepository;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.modules.hr.domain.PayrollRun;
import com.bigbrightpaints.erp.modules.hr.domain.PayrollRunLine;
import com.bigbrightpaints.erp.modules.hr.domain.PayrollRunLineRepository;
import com.bigbrightpaints.erp.modules.hr.domain.PayrollRunRepository;
import com.bigbrightpaints.erp.test.AbstractIntegrationTest;
import com.bigbrightpaints.erp.test.support.TestDateUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Regression: Payroll PF deduction respects company setting")
class PayrollPfDeductionRegressionIT extends AbstractIntegrationTest {

    private static final String COMPANY_CODE = "LF-019";
    private static final String COMPANY_CODE_NO_PF = "LF-019-NO-PF";
    private static final String ADMIN_EMAIL = "lf019@erp.test";
    private static final String ADMIN_PASSWORD = "lf019";

    @Autowired private TestRestTemplate rest;
    @Autowired private CompanyRepository companyRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private PayrollRunRepository payrollRunRepository;
    @Autowired private PayrollRunLineRepository payrollRunLineRepository;
    @Autowired private JournalEntryRepository journalEntryRepository;

    @Test
    void payrollPfDeductionAppliedWhenEnabled() {
        Company company = seedCompany(COMPANY_CODE, true);
        HttpHeaders headers = headersForCompany(COMPANY_CODE);

        Long employeeId = createStaffEmployee(headers, new BigDecimal("20000.00"), 1);
        LocalDate entryDate = TestDateUtils.safeDate(company);
        markAttendance(headers, employeeId, entryDate);

        Long runId = runPayroll(headers, entryDate);
        PayrollRun run = payrollRunRepository.findById(runId)
                .orElseThrow(() -> new AssertionError("Payroll run missing: " + runId));
        PayrollRunLine line = payrollRunLineRepository.findByPayrollRun(run).stream()
                .filter(candidate -> candidate.getEmployee().getId().equals(employeeId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Payroll line missing for employee " + employeeId));

        assertThat(line.getGrossPay()).isEqualByComparingTo(new BigDecimal("20000.00"));
        assertThat(line.getPfDeduction()).isEqualByComparingTo(new BigDecimal("2400"));
        assertThat(line.getTotalDeductions()).isEqualByComparingTo(new BigDecimal("2400"));
        assertThat(line.getNetPay()).isEqualByComparingTo(new BigDecimal("17600.00"));

        JournalEntry journal = journalEntryRepository.findById(run.getJournalEntryId())
                .orElseThrow(() -> new AssertionError("Payroll journal missing: " + run.getJournalEntryId()));
        Account salaryExpense = requireAccount(company, "SALARY-EXP");
        Account salaryPayable = requireAccount(company, "SALARY-PAYABLE");
        Account pfPayable = requireAccount(company, "PF-PAYABLE");

        assertThat(sumDebits(journal, salaryExpense)).isEqualByComparingTo(new BigDecimal("20000.00"));
        assertThat(sumCredits(journal, salaryPayable)).isEqualByComparingTo(new BigDecimal("17600.00"));
        assertThat(sumCredits(journal, pfPayable)).isEqualByComparingTo(new BigDecimal("2400"));
    }

    @Test
    void payrollPfDeductionSkippedWhenDisabled() {
        Company company = seedCompany(COMPANY_CODE_NO_PF, false);
        HttpHeaders headers = headersForCompany(COMPANY_CODE_NO_PF);

        Long employeeId = createStaffEmployee(headers, new BigDecimal("20000.00"), 1);
        LocalDate entryDate = TestDateUtils.safeDate(company);
        markAttendance(headers, employeeId, entryDate);

        Long runId = runPayroll(headers, entryDate);
        PayrollRun run = payrollRunRepository.findById(runId)
                .orElseThrow(() -> new AssertionError("Payroll run missing: " + runId));
        PayrollRunLine line = payrollRunLineRepository.findByPayrollRun(run).stream()
                .filter(candidate -> candidate.getEmployee().getId().equals(employeeId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Payroll line missing for employee " + employeeId));

        assertThat(line.getGrossPay()).isEqualByComparingTo(new BigDecimal("20000.00"));
        assertThat(line.getPfDeduction()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(line.getTotalDeductions()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(line.getNetPay()).isEqualByComparingTo(new BigDecimal("20000.00"));

        JournalEntry journal = journalEntryRepository.findById(run.getJournalEntryId())
                .orElseThrow(() -> new AssertionError("Payroll journal missing: " + run.getJournalEntryId()));
        Account salaryExpense = requireAccount(company, "SALARY-EXP");
        Account salaryPayable = requireAccount(company, "SALARY-PAYABLE");
        Account pfPayable = accountRepository.findByCompanyAndCodeIgnoreCase(company, "PF-PAYABLE").orElse(null);

        assertThat(sumDebits(journal, salaryExpense)).isEqualByComparingTo(new BigDecimal("20000.00"));
        assertThat(sumCredits(journal, salaryPayable)).isEqualByComparingTo(new BigDecimal("20000.00"));
        assertThat(pfPayable).as("PF payable account should not be created when PF is disabled").isNull();
    }

    private Company seedCompany(String companyCode, boolean pfEnabled) {
        dataSeeder.ensureUser(ADMIN_EMAIL, ADMIN_PASSWORD, "LF-019 Admin", companyCode,
                List.of("ROLE_ADMIN", "ROLE_ACCOUNTING"));
        Company company = companyRepository.findByCodeIgnoreCase(companyCode).orElseThrow();
        company.setPfEnabled(pfEnabled);
        return companyRepository.save(company);
    }

    private HttpHeaders headersForCompany(String companyCode) {
        String token = login(companyCode);
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Company-Id", companyCode);
        return headers;
    }

    private String login(String companyCode) {
        Map<String, Object> request = Map.of(
                "email", ADMIN_EMAIL,
                "password", ADMIN_PASSWORD,
                "companyCode", companyCode
        );
        ResponseEntity<Map> response = rest.postForEntity("/api/v1/auth/login", request, Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody().get("accessToken").toString();
    }

    private Long createStaffEmployee(HttpHeaders headers, BigDecimal monthlySalary, int workingDays) {
        Map<String, Object> request = new HashMap<>();
        request.put("firstName", "PF");
        request.put("lastName", "Employee");
        request.put("email", "pf.employee." + UUID.randomUUID() + "@erp.test");
        request.put("role", "ACCOUNTING");
        request.put("employeeType", "STAFF");
        request.put("paymentSchedule", "MONTHLY");
        request.put("monthlySalary", monthlySalary);
        request.put("workingDaysPerMonth", workingDays);
        request.put("weeklyOffDays", 0);
        request.put("standardHoursPerDay", new BigDecimal("8"));
        request.put("overtimeRateMultiplier", new BigDecimal("1.5"));
        request.put("doubleOtRateMultiplier", new BigDecimal("2.0"));

        ResponseEntity<Map> response = rest.exchange(
                "/api/v1/hr/employees",
                HttpMethod.POST,
                new HttpEntity<>(request, headers),
                Map.class
        );
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> data = (Map<?, ?>) response.getBody().get("data");
        return ((Number) data.get("id")).longValue();
    }

    private void markAttendance(HttpHeaders headers, Long employeeId, LocalDate date) {
        Map<String, Object> request = new HashMap<>();
        request.put("date", date);
        request.put("status", "PRESENT");
        request.put("regularHours", new BigDecimal("8"));
        request.put("overtimeHours", BigDecimal.ZERO);
        request.put("doubleOvertimeHours", BigDecimal.ZERO);
        request.put("holiday", false);
        request.put("weekend", false);
        request.put("remarks", "LF-019 regression");

        ResponseEntity<Map> response = rest.exchange(
                "/api/v1/hr/attendance/mark/" + employeeId,
                HttpMethod.POST,
                new HttpEntity<>(request, headers),
                Map.class
        );
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private Long runPayroll(HttpHeaders headers, LocalDate entryDate) {
        LocalDate periodStart = entryDate.withDayOfMonth(1);
        LocalDate periodEnd = entryDate.withDayOfMonth(entryDate.lengthOfMonth());
        Map<String, Object> runRequest = Map.of(
                "runType", "MONTHLY",
                "periodStart", periodStart,
                "periodEnd", periodEnd,
                "remarks", "LF-019 payroll"
        );
        ResponseEntity<Map> runResp = rest.exchange(
                "/api/v1/payroll/runs",
                HttpMethod.POST,
                new HttpEntity<>(runRequest, headers),
                Map.class
        );
        assertThat(runResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> runData = (Map<?, ?>) runResp.getBody().get("data");
        Long runId = ((Number) runData.get("id")).longValue();

        rest.exchange("/api/v1/payroll/runs/" + runId + "/calculate",
                HttpMethod.POST, new HttpEntity<>(headers), Map.class);
        rest.exchange("/api/v1/payroll/runs/" + runId + "/approve",
                HttpMethod.POST, new HttpEntity<>(headers), Map.class);
        rest.exchange("/api/v1/payroll/runs/" + runId + "/post",
                HttpMethod.POST, new HttpEntity<>(headers), Map.class);

        return runId;
    }

    private Account requireAccount(Company company, String code) {
        return accountRepository.findByCompanyAndCodeIgnoreCase(company, code)
                .orElseThrow(() -> new AssertionError("Account missing: " + code));
    }

    private BigDecimal sumDebits(JournalEntry journal, Account account) {
        return journal.getLines().stream()
                .filter(line -> line.getAccount().getId().equals(account.getId()))
                .map(line -> line.getDebit() == null ? BigDecimal.ZERO : line.getDebit())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal sumCredits(JournalEntry journal, Account account) {
        return journal.getLines().stream()
                .filter(line -> line.getAccount().getId().equals(account.getId()))
                .map(line -> line.getCredit() == null ? BigDecimal.ZERO : line.getCredit())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
