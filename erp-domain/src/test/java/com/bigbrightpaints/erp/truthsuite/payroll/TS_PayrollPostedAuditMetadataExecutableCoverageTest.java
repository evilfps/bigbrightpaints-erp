package com.bigbrightpaints.erp.truthsuite.payroll;

import com.bigbrightpaints.erp.core.audit.AuditEvent;
import com.bigbrightpaints.erp.core.audit.AuditService;
import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.core.util.CompanyEntityLookup;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryDto;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalLineDto;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingFacade;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.hr.domain.AttendanceRepository;
import com.bigbrightpaints.erp.modules.hr.domain.EmployeeRepository;
import com.bigbrightpaints.erp.modules.hr.domain.PayrollRun;
import com.bigbrightpaints.erp.modules.hr.domain.PayrollRunLineRepository;
import com.bigbrightpaints.erp.modules.hr.domain.PayrollRunRepository;
import com.bigbrightpaints.erp.modules.hr.service.PayrollService;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

@Tag("critical")
class TS_PayrollPostedAuditMetadataExecutableCoverageTest {

    private PayrollService payrollService;

    @BeforeEach
    void setUp() {
        payrollService = new PayrollService(
                mock(PayrollRunRepository.class),
                mock(PayrollRunLineRepository.class),
                mock(EmployeeRepository.class),
                mock(AttendanceRepository.class),
                mock(AccountingFacade.class),
                mock(AccountRepository.class),
                mock(CompanyContextService.class),
                mock(CompanyEntityLookup.class),
                mock(CompanyClock.class),
                mock(AuditService.class));
    }

    @Test
    void requiredPayrollPostedAuditMetadataBuildsCanonicalPayload() {
        PayrollRun run = baseRun("RUN-2026-001");
        JournalEntryDto journal = postedJournal(9001L);
        LocalDate postingDate = LocalDate.of(2026, 2, 20);

        Map<String, String> metadata = invokeRequiredPayrollPostedAuditMetadata(
                run,
                journal,
                postingDate,
                new BigDecimal("1000.00"),
                new BigDecimal("120.00"),
                new BigDecimal("880.00"));

        assertThat(metadata)
                .containsEntry("payrollRunId", "42")
                .containsEntry("runNumber", "RUN-2026-001")
                .containsEntry("runType", PayrollRun.RunType.WEEKLY.name())
                .containsEntry("periodStart", "2026-02-10")
                .containsEntry("periodEnd", "2026-02-16")
                .containsEntry("journalEntryId", "9001")
                .containsEntry("postingDate", "2026-02-20")
                .containsEntry("totalGrossPay", "1000.00")
                .containsEntry("totalAdvances", "120.00")
                .containsEntry("netPayable", "880.00");
    }

    @Test
    void requiredPayrollPostedAuditMetadataFailsClosedWhenValueIsNull() {
        PayrollRun run = baseRun(null);
        JournalEntryDto journal = postedJournal(9001L);

        assertThatThrownBy(() -> invokeRequiredPayrollPostedAuditMetadata(
                run,
                journal,
                LocalDate.of(2026, 2, 20),
                new BigDecimal("1000.00"),
                new BigDecimal("120.00"),
                new BigDecimal("880.00")))
                .isInstanceOf(ApplicationException.class)
                .satisfies(ex -> {
                    ApplicationException appEx = (ApplicationException) ex;
                    assertThat(appEx.getErrorCode()).isEqualTo(ErrorCode.BUSINESS_INVALID_STATE);
                    assertThat(appEx.getMessage()).contains("runNumber");
                    assertThat(appEx.getDetails())
                            .containsEntry("auditEvent", AuditEvent.PAYROLL_POSTED.name())
                            .containsEntry("metadataKey", "runNumber");
                });
    }

    @Test
    void requiredPayrollPostedAuditMetadataFailsClosedWhenValueIsBlank() {
        PayrollRun run = baseRun("   ");
        JournalEntryDto journal = postedJournal(9001L);

        assertThatThrownBy(() -> invokeRequiredPayrollPostedAuditMetadata(
                run,
                journal,
                LocalDate.of(2026, 2, 20),
                new BigDecimal("1000.00"),
                new BigDecimal("120.00"),
                new BigDecimal("880.00")))
                .isInstanceOf(ApplicationException.class)
                .satisfies(ex -> {
                    ApplicationException appEx = (ApplicationException) ex;
                    assertThat(appEx.getErrorCode()).isEqualTo(ErrorCode.BUSINESS_INVALID_STATE);
                    assertThat(appEx.getMessage()).contains("runNumber");
                    assertThat(appEx.getDetails())
                            .containsEntry("auditEvent", AuditEvent.PAYROLL_POSTED.name())
                            .containsEntry("metadataKey", "runNumber");
                });
    }

    private Map<String, String> invokeRequiredPayrollPostedAuditMetadata(PayrollRun run,
                                                                          JournalEntryDto journal,
                                                                          LocalDate postingDate,
                                                                          BigDecimal totalGrossPay,
                                                                          BigDecimal totalAdvances,
                                                                          BigDecimal salaryPayableAmount) {
        try {
            Method method = PayrollService.class.getDeclaredMethod(
                    "requiredPayrollPostedAuditMetadata",
                    PayrollRun.class,
                    JournalEntryDto.class,
                    LocalDate.class,
                    BigDecimal.class,
                    BigDecimal.class,
                    BigDecimal.class);
            method.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<String, String> metadata = (Map<String, String>) method.invoke(
                    payrollService,
                    run,
                    journal,
                    postingDate,
                    totalGrossPay,
                    totalAdvances,
                    salaryPayableAmount);
            return metadata;
        } catch (InvocationTargetException ex) {
            if (ex.getCause() instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new RuntimeException(ex.getCause());
        } catch (ReflectiveOperationException ex) {
            throw new RuntimeException(ex);
        }
    }

    private PayrollRun baseRun(String runNumber) {
        PayrollRun run = new PayrollRun();
        ReflectionTestUtils.setField(run, "id", 42L);
        run.setRunNumber(runNumber);
        run.setRunType(PayrollRun.RunType.WEEKLY);
        run.setPeriodStart(LocalDate.of(2026, 2, 10));
        run.setPeriodEnd(LocalDate.of(2026, 2, 16));
        return run;
    }

    private JournalEntryDto postedJournal(Long journalId) {
        return new JournalEntryDto(
                journalId,
                UUID.randomUUID(),
                "JRN-" + journalId,
                LocalDate.of(2026, 2, 16),
                "Payroll posting",
                "POSTED",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                List.<JournalLineDto>of(),
                Instant.now(),
                Instant.now(),
                Instant.now(),
                "system",
                "system",
                "system");
    }
}
