package com.bigbrightpaints.erp.codered;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import com.bigbrightpaints.erp.core.security.CompanyContextHolder;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountType;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriod;
import com.bigbrightpaints.erp.modules.accounting.dto.AccountingPeriodDto;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.PeriodCloseRequestActionRequest;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingPeriodService;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingService;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.test.AbstractIntegrationTest;
import com.bigbrightpaints.erp.test.support.TestDateUtils;

@Tag("critical")
@Tag("reconciliation")
class CR_PeriodCloseDriftScansTest extends AbstractIntegrationTest {

  private static final String MISSING_SNAPSHOT_SCAN =
      """
      select
        p.company_id,
        p.id as accounting_period_id,
        p.year,
        p.month,
        p.start_date,
        p.end_date,
        p.closed_at
      from accounting_periods p
      left join accounting_period_snapshots s
        on s.accounting_period_id = p.id
       and s.company_id = p.company_id
      where p.status = 'CLOSED'
        and s.id is null
      order by p.company_id, p.end_date, p.id
      """;

  private static final String LATE_POSTING_SCAN =
      """
      select
        p.company_id,
        p.id as accounting_period_id,
        p.start_date,
        p.end_date,
        p.closed_at,
        je.id as journal_entry_id,
        je.reference_number,
        je.entry_date,
        je.posted_at,
        je.created_at
      from accounting_periods p
      join journal_entries je
        on je.company_id = p.company_id
       and je.status = 'POSTED'
       and je.entry_date between p.start_date and p.end_date
      where p.status = 'CLOSED'
        and p.closed_at is not null
        and coalesce(je.posted_at, je.created_at) > (p.closed_at at time zone 'UTC')
      order by p.company_id, p.end_date, je.id
      """;

  private static final String DRIFT_SCAN =
      """
      with snapshot_periods as (
        select p.id, p.company_id, p.end_date
        from accounting_periods p
        join accounting_period_snapshots s
          on s.accounting_period_id = p.id
         and s.company_id = p.company_id
        where p.status = 'CLOSED'
      ),
      balances as (
        select
          sp.id as period_id,
          sp.company_id as company_id,
          jl.account_id as account_id,
          coalesce(sum(jl.debit), 0) as debit_sum,
          coalesce(sum(jl.credit), 0) as credit_sum
        from snapshot_periods sp
        join journal_entries je
          on je.company_id = sp.company_id
         and je.status = 'POSTED'
         and je.entry_date <= sp.end_date
        join journal_lines jl
          on jl.journal_entry_id = je.id
        group by sp.id, sp.company_id, jl.account_id
      ),
      expected as (
        select
          period_id,
          company_id,
          account_id,
          case
            when (debit_sum - credit_sum) >= 0
              then (debit_sum - credit_sum)
            else 0
          end as expected_debit,
          case
            when (debit_sum - credit_sum) < 0
              then (credit_sum - debit_sum)
            else 0
          end as expected_credit
        from balances
      ),
      snapshot_lines as (
        select
          s.accounting_period_id as period_id,
          s.company_id,
          l.account_id,
          coalesce(l.debit, 0) as snap_debit,
          coalesce(l.credit, 0) as snap_credit
        from accounting_period_snapshots s
        join accounting_period_trial_balance_lines l
          on l.snapshot_id = s.id
        join snapshot_periods sp
          on sp.id = s.accounting_period_id
         and sp.company_id = s.company_id
      )
      select
        coalesce(sl.company_id, e.company_id) as company_id,
        coalesce(sl.period_id, e.period_id) as accounting_period_id,
        coalesce(sl.account_id, e.account_id) as account_id,
        coalesce(sl.snap_debit, 0) as snap_debit,
        coalesce(sl.snap_credit, 0) as snap_credit,
        coalesce(e.expected_debit, 0) as expected_debit,
        coalesce(e.expected_credit, 0) as expected_credit
      from expected e
      full join snapshot_lines sl
        on sl.period_id = e.period_id
       and sl.company_id = e.company_id
       and sl.account_id = e.account_id
      where abs(coalesce(e.expected_debit, 0) - coalesce(sl.snap_debit, 0)) > 0.01
         or abs(coalesce(e.expected_credit, 0) - coalesce(sl.snap_credit, 0)) > 0.01
      order by company_id, accounting_period_id, account_id
      """;

  private static final String SNAPSHOT_TOTALS_SCAN =
      """
      with snapshot_totals as (
        select
          s.id as snapshot_id,
          s.company_id,
          s.accounting_period_id,
          coalesce(sum(l.debit), 0) as line_debit,
          coalesce(sum(l.credit), 0) as line_credit
        from accounting_period_snapshots s
        join accounting_periods p
          on p.id = s.accounting_period_id
         and p.company_id = s.company_id
        left join accounting_period_trial_balance_lines l
          on l.snapshot_id = s.id
        where p.status = 'CLOSED'
        group by s.id, s.company_id, s.accounting_period_id
      )
      select
        s.company_id,
        s.accounting_period_id,
        s.id as snapshot_id,
        s.trial_balance_total_debit,
        s.trial_balance_total_credit,
        st.line_debit,
        st.line_credit
      from accounting_period_snapshots s
      join snapshot_totals st
        on st.snapshot_id = s.id
      where abs(coalesce(s.trial_balance_total_debit, 0) - coalesce(st.line_debit, 0)) > 0.01
         or abs(coalesce(s.trial_balance_total_credit, 0) - coalesce(st.line_credit, 0)) > 0.01
      order by s.company_id, s.accounting_period_id, s.id
      """;

  @Autowired private AccountingPeriodService accountingPeriodService;
  @Autowired private AccountingService accountingService;
  @Autowired private AccountRepository accountRepository;
  @Autowired private JdbcTemplate jdbcTemplate;

  @AfterEach
  void clearCompanyContext() {
    CompanyContextHolder.clear();
    SecurityContextHolder.clearContext();
  }

  @Test
  void predeployScans_areCleanForClosedPeriod() {
    String companyCode = "CR-SCAN-CLEAN-" + System.nanoTime();
    Company company = dataSeeder.ensureCompany(companyCode, companyCode + " Ltd");
    CompanyContextHolder.setCompanyCode(companyCode);
    try {
      LocalDate today = TestDateUtils.safeDate(company);
      LocalDate periodDate = today.minusMonths(1);
      AccountingPeriod period = accountingPeriodService.ensurePeriod(company, periodDate);
      Account cash = ensureAccount(company, "CASH-SCAN", "Cash", AccountType.ASSET);
      Account revenue = ensureAccount(company, "REV-SCAN", "Revenue", AccountType.REVENUE);

      postJournal(
          period.getEndDate().minusDays(1),
          List.of(
              line(cash.getId(), new BigDecimal("100.00"), BigDecimal.ZERO),
              line(revenue.getId(), BigDecimal.ZERO, new BigDecimal("100.00"))));

      forceClosePeriod(
          period.getId(), "CODE-RED scan clean request", "CODE-RED scan clean approval");

      assertScanEmpty(MISSING_SNAPSHOT_SCAN, company.getId());
      assertScanEmpty(LATE_POSTING_SCAN, company.getId());
      assertScanEmpty(DRIFT_SCAN, company.getId());
      assertScanEmpty(SNAPSHOT_TOTALS_SCAN, company.getId());
    } finally {
      CompanyContextHolder.clear();
    }
  }

  @Test
  void predeployScans_detectLatePostingAndDrift() {
    String companyCode = "CR-SCAN-DRIFT-" + System.nanoTime();
    Company company = dataSeeder.ensureCompany(companyCode, companyCode + " Ltd");
    CompanyContextHolder.setCompanyCode(companyCode);
    try {
      LocalDate today = TestDateUtils.safeDate(company);
      LocalDate periodDate = today.minusMonths(1);
      AccountingPeriod period = accountingPeriodService.ensurePeriod(company, periodDate);
      Account cash = ensureAccount(company, "CASH-DRIFT", "Cash", AccountType.ASSET);
      Account revenue = ensureAccount(company, "REV-DRIFT", "Revenue", AccountType.REVENUE);

      postJournal(
          period.getEndDate().minusDays(1),
          List.of(
              line(cash.getId(), new BigDecimal("50.00"), BigDecimal.ZERO),
              line(revenue.getId(), BigDecimal.ZERO, new BigDecimal("50.00"))));

      AccountingPeriodDto closed =
          forceClosePeriod(
              period.getId(), "CODE-RED scan close request", "CODE-RED scan close approval");

      Instant postedAt = closed.closedAt().plusSeconds(60);
      insertLateJournal(company, period, cash, revenue, postedAt, period.getEndDate().minusDays(1));

      assertScanEmpty(MISSING_SNAPSHOT_SCAN, company.getId());
      assertScanHasRows(LATE_POSTING_SCAN, company.getId());
      assertScanHasRows(DRIFT_SCAN, company.getId());
    } finally {
      CompanyContextHolder.clear();
    }
  }

  @Test
  void predeployScans_detectMissingSnapshot() {
    String companyCode = "CR-SCAN-MISSING-" + System.nanoTime();
    Company company = dataSeeder.ensureCompany(companyCode, companyCode + " Ltd");
    CompanyContextHolder.setCompanyCode(companyCode);
    try {
      LocalDate today = TestDateUtils.safeDate(company);
      LocalDate periodDate = today.minusMonths(1);
      AccountingPeriod period = accountingPeriodService.ensurePeriod(company, periodDate);
      Account cash = ensureAccount(company, "CASH-MISS", "Cash", AccountType.ASSET);
      Account revenue = ensureAccount(company, "REV-MISS", "Revenue", AccountType.REVENUE);

      postJournal(
          period.getEndDate().minusDays(1),
          List.of(
              line(cash.getId(), new BigDecimal("75.00"), BigDecimal.ZERO),
              line(revenue.getId(), BigDecimal.ZERO, new BigDecimal("75.00"))));

      forceClosePeriod(
          period.getId(), "CODE-RED scan missing request", "CODE-RED scan missing approval");

      jdbcTemplate.update(
          "delete from accounting_period_snapshots where accounting_period_id = ?", period.getId());

      assertScanHasRows(MISSING_SNAPSHOT_SCAN, company.getId());
    } finally {
      CompanyContextHolder.clear();
    }
  }

  @Test
  void predeployScans_detectSnapshotTotalsMismatch() {
    String companyCode = "CR-SCAN-SNAP-TOTAL-" + System.nanoTime();
    Company company = dataSeeder.ensureCompany(companyCode, companyCode + " Ltd");
    CompanyContextHolder.setCompanyCode(companyCode);
    try {
      LocalDate today = TestDateUtils.safeDate(company);
      LocalDate periodDate = today.minusMonths(1);
      AccountingPeriod period = accountingPeriodService.ensurePeriod(company, periodDate);
      Account cash = ensureAccount(company, "CASH-SNAP-TOTAL", "Cash", AccountType.ASSET);
      Account revenue = ensureAccount(company, "REV-SNAP-TOTAL", "Revenue", AccountType.REVENUE);

      postJournal(
          period.getEndDate().minusDays(1),
          List.of(
              line(cash.getId(), new BigDecimal("125.00"), BigDecimal.ZERO),
              line(revenue.getId(), BigDecimal.ZERO, new BigDecimal("125.00"))));

      forceClosePeriod(
          period.getId(),
          "CODE-RED scan snapshot totals request",
          "CODE-RED scan snapshot totals approval");

      jdbcTemplate.update(
          "update accounting_period_snapshots set trial_balance_total_debit ="
              + " trial_balance_total_debit + 1 where accounting_period_id = ? and company_id = ?",
          period.getId(),
          company.getId());

      assertScanHasRows(SNAPSHOT_TOTALS_SCAN, company.getId());
    } finally {
      CompanyContextHolder.clear();
    }
  }

  private void assertScanEmpty(String sql, Long companyId) {
    assertThat(scanRows(sql, companyId)).isEmpty();
  }

  private void assertScanHasRows(String sql, Long companyId) {
    assertThat(scanRows(sql, companyId)).isNotEmpty();
  }

  private List<java.util.Map<String, Object>> scanRows(String sql, Long companyId) {
    return jdbcTemplate.queryForList(sql).stream()
        .filter(row -> matchesCompany(row, companyId))
        .toList();
  }

  private boolean matchesCompany(java.util.Map<String, Object> row, Long companyId) {
    Object value = row.get("company_id");
    if (value instanceof Number number) {
      return number.longValue() == companyId;
    }
    return false;
  }

  private Long insertLateJournal(
      Company company,
      AccountingPeriod period,
      Account debitAccount,
      Account creditAccount,
      Instant postedAt,
      LocalDate entryDate) {
    String reference = "LATE-POST-" + System.nanoTime();
    Timestamp ts = Timestamp.from(postedAt);
    Long journalId =
        jdbcTemplate.queryForObject(
            "insert into journal_entries (company_id, reference_number, status, entry_date,"
                + " created_at, updated_at, posted_at, accounting_period_id) values (?, ?,"
                + " 'POSTED', ?, ?, ?, ?, ?) returning id",
            Long.class,
            company.getId(),
            reference,
            entryDate,
            ts,
            ts,
            ts,
            period.getId());

    jdbcTemplate.update(
        "insert into journal_lines (journal_entry_id, account_id, description, debit, credit)"
            + " values (?,?,?,?,?)",
        journalId,
        debitAccount.getId(),
        "late",
        new BigDecimal("25.00"),
        BigDecimal.ZERO);
    jdbcTemplate.update(
        "insert into journal_lines (journal_entry_id, account_id, description, debit, credit)"
            + " values (?,?,?,?,?)",
        journalId,
        creditAccount.getId(),
        "late",
        BigDecimal.ZERO,
        new BigDecimal("25.00"));

    return journalId;
  }

  private Long postJournal(
      LocalDate entryDate, List<JournalEntryRequest.JournalLineRequest> lines) {
    Authentication previous = SecurityContextHolder.getContext().getAuthentication();
    JournalEntryRequest request =
        new JournalEntryRequest(
            "SCAN-" + System.nanoTime(),
            entryDate,
            "CODE-RED scan",
            null,
            null,
            Boolean.TRUE,
            lines);
    try {
      authenticate("admin.user", "ROLE_ADMIN");
      return accountingService.createJournalEntry(request).id();
    } finally {
      SecurityContextHolder.getContext().setAuthentication(previous);
    }
  }

  private JournalEntryRequest.JournalLineRequest line(
      Long accountId, BigDecimal debit, BigDecimal credit) {
    return new JournalEntryRequest.JournalLineRequest(accountId, "line", debit, credit);
  }

  private Account ensureAccount(Company company, String code, String name, AccountType type) {
    return accountRepository
        .findByCompanyAndCodeIgnoreCase(company, code)
        .orElseGet(
            () -> {
              Account account = new Account();
              account.setCompany(company);
              account.setCode(code);
              account.setName(name);
              account.setType(type);
              return accountRepository.save(account);
            });
  }

  private AccountingPeriodDto forceClosePeriod(
      Long periodId, String requestNote, String approvalNote) {
    authenticate("maker.user", "ROLE_ACCOUNTING");
    accountingPeriodService.requestPeriodClose(
        periodId, new PeriodCloseRequestActionRequest(requestNote, true));
    authenticate("checker.user", "ROLE_ADMIN");
    return accountingPeriodService.approvePeriodClose(
        periodId, new PeriodCloseRequestActionRequest(approvalNote, true));
  }

  private void authenticate(String username, String... roles) {
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken(
                username,
                "N/A",
                java.util.Arrays.stream(roles).map(SimpleGrantedAuthority::new).toList()));
  }
}
