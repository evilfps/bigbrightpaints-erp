package com.bigbrightpaints.erp.codered;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.security.CompanyContextHolder;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountType;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriod;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryRepository;
import com.bigbrightpaints.erp.modules.accounting.dto.AccountingPeriodCloseRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.AccountingPeriodDto;
import com.bigbrightpaints.erp.modules.accounting.dto.AccountingPeriodReopenRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalCreationRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.PeriodCloseRequestActionRequest;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingFacade;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingPeriodService;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingService;
import com.bigbrightpaints.erp.modules.accounting.service.PeriodCloseHook;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.test.AbstractIntegrationTest;
import com.bigbrightpaints.erp.test.support.TestDateUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

@org.springframework.context.annotation.Import(CR_PeriodCloseAtomicityTest.CloseHookConfig.class)
@Tag("critical")
@Tag("concurrency")
@Tag("reconciliation")
class CR_PeriodCloseAtomicityTest extends AbstractIntegrationTest {

    @Autowired private AccountingPeriodService accountingPeriodService;
    @Autowired private AccountingService accountingService;
    @Autowired private AccountRepository accountRepository;
    @Autowired private JournalEntryRepository journalEntryRepository;
    @Autowired private CompanyRepository companyRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TestPeriodCloseHook closeHook;
    @SpyBean private AccountingFacade accountingFacade;

    @AfterEach
    void clearCompanyContext() {
        CompanyContextHolder.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    void closePeriod_blocksConcurrentPosting() throws Exception {
        String companyCode = "CR-CLOSE-" + System.nanoTime();
        Company company = dataSeeder.ensureCompany(companyCode, companyCode + " Ltd");
        CompanyContextHolder.setCompanyId(companyCode);
        try {
            LocalDate today = TestDateUtils.safeDate(company);
            AccountingPeriod period = accountingPeriodService.ensurePeriod(company, today);
            Account cash = ensureAccount(company, "CASH-LOCK", "Cash", AccountType.ASSET);
            Account revenue = ensureAccount(company, "REV-LOCK", "Revenue", AccountType.REVENUE);

            postJournal(today.minusDays(1), List.of(
                    line(cash.getId(), new BigDecimal("100.00"), BigDecimal.ZERO),
                    line(revenue.getId(), BigDecimal.ZERO, new BigDecimal("100.00"))
            ));

            closeHook.reset();
            ExecutorService executor = Executors.newFixedThreadPool(2);
            CompletableFuture<AccountingPeriodDto> closeFuture = CompletableFuture.supplyAsync(() -> {
                CompanyContextHolder.setCompanyId(companyCode);
                try {
                    return forceClosePeriod(period.getId(), "CODE-RED close request", "CODE-RED close approval");
                } finally {
                    CompanyContextHolder.clear();
                    SecurityContextHolder.clearContext();
                }
            }, executor);

            closeHook.awaitLocked(Duration.ofSeconds(5));

            CompletableFuture<?> postFuture = CompletableFuture.supplyAsync(() -> {
                CompanyContextHolder.setCompanyId(companyCode);
                try {
                    return postJournal(today, List.of(
                            line(cash.getId(), new BigDecimal("10.00"), BigDecimal.ZERO),
                            line(revenue.getId(), BigDecimal.ZERO, new BigDecimal("10.00"))
                    ));
                } finally {
                    CompanyContextHolder.clear();
                }
            }, executor);

            Thread.sleep(200);
            assertThat(postFuture.isDone()).as("Posting should block while close holds lock").isFalse();

            closeHook.release();
            AccountingPeriodDto closed = closeFuture.get(10, TimeUnit.SECONDS);
            assertThat(closed.status()).isEqualTo("CLOSED");

            assertThatThrownBy(() -> postFuture.get(5, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(ApplicationException.class)
                    .hasMessageContaining("locked/closed");

            executor.shutdownNow();
        } finally {
            CompanyContextHolder.clear();
        }
    }

    @Test
    void requestCloseAndReopen_areIdempotent() {
        String companyCode = "CR-CLOSE-IDEMP-" + System.nanoTime();
        Company company = dataSeeder.ensureCompany(companyCode, companyCode + " Ltd");
        CompanyContextHolder.setCompanyId(companyCode);
        try {
            LocalDate today = TestDateUtils.safeDate(company);
            AccountingPeriod period = accountingPeriodService.ensurePeriod(company, today);
            Account cash = ensureAccount(company, "CASH-IDEMP", "Cash", AccountType.ASSET);
            Account revenue = ensureAccount(company, "REV-IDEMP", "Revenue", AccountType.REVENUE);
            Account expense = ensureAccount(company, "EXP-IDEMP", "Expense", AccountType.EXPENSE);

            postJournal(today.minusDays(2), List.of(
                    line(cash.getId(), new BigDecimal("200.00"), BigDecimal.ZERO),
                    line(revenue.getId(), BigDecimal.ZERO, new BigDecimal("200.00"))
            ));
            postJournal(today.minusDays(1), List.of(
                    line(expense.getId(), new BigDecimal("50.00"), BigDecimal.ZERO),
                    line(cash.getId(), BigDecimal.ZERO, new BigDecimal("50.00"))
            ));

            authenticate("maker.user", "ROLE_ACCOUNTING");
            var firstRequest = accountingPeriodService.requestPeriodClose(
                    period.getId(),
                    new PeriodCloseRequestActionRequest("CODE-RED close request", true));
            var secondRequest = accountingPeriodService.requestPeriodClose(
                    period.getId(),
                    new PeriodCloseRequestActionRequest("CODE-RED close request retry", true));

            assertThat(secondRequest.id()).isEqualTo(firstRequest.id());

            authenticate("checker.user", "ROLE_ADMIN");
            AccountingPeriodDto closed = accountingPeriodService.approvePeriodClose(
                    period.getId(),
                    new PeriodCloseRequestActionRequest("CODE-RED close approval", true));

            assertThat(closed.status()).isEqualTo("CLOSED");
            assertThat(countClosingJournals(company, period)).isEqualTo(1);
            verify(accountingFacade).createStandardJournal(argThat(request ->
                    request != null
                            && closingReference(period).equals(request.sourceReference())
                            && Boolean.TRUE.equals(request.adminOverride())
                            && "ACCOUNTING_PERIOD".equals(request.sourceModule())));

            authenticate("super.admin", "ROLE_SUPER_ADMIN");
            AccountingPeriodDto reopened = accountingPeriodService.reopenPeriod(
                    period.getId(),
                    new AccountingPeriodReopenRequest("CODE-RED reopen"));
            assertThat(reopened.status()).isEqualTo("OPEN");
            assertThat(reopened.closingJournalEntryId()).isNull();

            authenticate("super.admin", "ROLE_SUPER_ADMIN");
            AccountingPeriodDto reopenedAgain = accountingPeriodService.reopenPeriod(
                    period.getId(),
                    new AccountingPeriodReopenRequest("CODE-RED reopen retry"));
            assertThat(reopenedAgain.status()).isEqualTo("OPEN");
            assertThat(reopenedAgain.closingJournalEntryId()).isNull();
            verify(accountingFacade).reverseClosingEntryForPeriodReopen(
                    any(JournalEntry.class),
                    any(AccountingPeriod.class),
                    eq("CODE-RED reopen"));

            JournalEntry closing = journalEntryRepository.findByCompanyAndReferenceNumber(
                    company, closingReference(period)).orElseThrow();
            assertThat(closing.getStatus()).isEqualTo("REVERSED");
            Integer reversalCount = jdbcTemplate.queryForObject(
                    "select count(*) from journal_entries where reversal_of_id = ?",
                    Integer.class,
                    closing.getId()
            );
            assertThat(reversalCount).isNotNull().isEqualTo(1);
        } finally {
            CompanyContextHolder.clear();
        }
    }

    private Long postJournal(LocalDate entryDate, List<JournalEntryRequest.JournalLineRequest> lines) {
        JournalEntryRequest request = new JournalEntryRequest(
                "TEST-" + System.nanoTime(),
                entryDate,
                "CODE-RED seed",
                null,
                null,
                Boolean.FALSE,
                lines
        );
        return accountingService.createJournalEntry(request).id();
    }

    private JournalEntryRequest.JournalLineRequest line(Long accountId, BigDecimal debit, BigDecimal credit) {
        return new JournalEntryRequest.JournalLineRequest(accountId, "line", debit, credit);
    }

    private Account ensureAccount(Company company, String code, String name, AccountType type) {
        return accountRepository.findByCompanyAndCodeIgnoreCase(company, code)
                .orElseGet(() -> {
                    Account account = new Account();
                    account.setCompany(company);
                    account.setCode(code);
                    account.setName(name);
                    account.setType(type);
                    return accountRepository.save(account);
                });
    }

    private String closingReference(AccountingPeriod period) {
        return "PERIOD-CLOSE-" + period.getYear() + String.format("%02d", period.getMonth());
    }

    private int countClosingJournals(Company company, AccountingPeriod period) {
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from journal_entries where company_id = ? and reference_number = ?",
                Integer.class,
                company.getId(),
                closingReference(period)
        );
        return count != null ? count : 0;
    }

    private AccountingPeriodDto forceClosePeriod(Long periodId, String requestNote, String approvalNote) {
        authenticate("maker.user", "ROLE_ACCOUNTING");
        accountingPeriodService.requestPeriodClose(periodId, new PeriodCloseRequestActionRequest(requestNote, true));
        authenticate("checker.user", "ROLE_ADMIN");
        return accountingPeriodService.approvePeriodClose(periodId, new PeriodCloseRequestActionRequest(approvalNote, true));
    }

    private void authenticate(String username, String... roles) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        username,
                        "N/A",
                        java.util.Arrays.stream(roles)
                                .map(SimpleGrantedAuthority::new)
                                .toList()
                )
        );
    }

    @TestConfiguration
    static class CloseHookConfig {
        @Bean
        @Primary
        TestPeriodCloseHook periodCloseHook() {
            return new TestPeriodCloseHook();
        }
    }

    static class TestPeriodCloseHook implements PeriodCloseHook {
        private volatile CountDownLatch locked = new CountDownLatch(1);
        private volatile CountDownLatch release = new CountDownLatch(1);

        void reset() {
            locked = new CountDownLatch(1);
            release = new CountDownLatch(1);
        }

        void awaitLocked(Duration timeout) throws InterruptedException {
            boolean ok = locked.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
            assertThat(ok).as("close hook did not lock in time").isTrue();
        }

        void release() {
            release.countDown();
        }

        @Override
        public void onPeriodCloseLocked(Company company, AccountingPeriod period) {
            locked.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
