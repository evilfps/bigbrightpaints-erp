package com.bigbrightpaints.erp.e2e.accounting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import com.bigbrightpaints.erp.core.security.CompanyContextHolder;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountType;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.sales.domain.Dealer;
import com.bigbrightpaints.erp.modules.sales.domain.DealerRepository;
import com.bigbrightpaints.erp.test.AbstractIntegrationTest;

@DisplayName("Database RLS tenant isolation on accounting truth tables")
class AccountingTenantIsolationRlsIT extends AbstractIntegrationTest {

  private static final String RLS_PROBE_ROLE = "erp_rls_probe";

  private static final List<String> ACCOUNTING_RLS_TABLES =
      List.of(
          "accounts",
          "accounting_events",
          "accounting_periods",
          "accounting_period_snapshots",
          "accounting_period_trial_balance_lines",
          "journal_entries",
          "journal_lines",
          "journal_reference_mappings",
          "dealer_ledger_entries",
          "supplier_ledger_entries",
          "partner_settlement_allocations",
          "opening_balance_imports",
          "tally_imports",
          "bank_reconciliation_sessions",
          "bank_reconciliation_items",
          "reconciliation_discrepancies",
          "period_close_requests",
          "closed_period_posting_exceptions");

  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private AccountRepository accountRepository;
  @Autowired private DealerRepository dealerRepository;

  @AfterEach
  void clearCompanyContext() {
    CompanyContextHolder.clear();
  }

  @Test
  void accountingTruthTables_enableForcedRlsAndPolicies() {
    for (String table : ACCOUNTING_RLS_TABLES) {
      var tableFlags =
          jdbcTemplate.queryForMap(
              """
              SELECT c.relrowsecurity AS row_security,
                     c.relforcerowsecurity AS force_row_security
              FROM pg_class c
              JOIN pg_namespace n ON n.oid = c.relnamespace
              WHERE n.nspname = 'public'
                AND c.relname = ?
              """,
              table);

      assertThat(asBoolean(tableFlags.get("row_security")))
          .as("row-level security enabled on %s", table)
          .isTrue();
      assertThat(asBoolean(tableFlags.get("force_row_security")))
          .as("forced row-level security enabled on %s", table)
          .isTrue();

      Integer policyCount =
          jdbcTemplate.queryForObject(
              "SELECT COUNT(*) FROM pg_policies WHERE schemaname = 'public' AND tablename = ?",
              Integer.class,
              table);
      assertThat(policyCount).as("policy count for %s", table).isNotNull().isGreaterThan(0);
    }
  }

  @Test
  void directDbTenantContext_blocksCrossTenantReadsAndWrites() {
    ensureRlsProbeRole();

    String suffix = Long.toString(System.nanoTime(), 36).toUpperCase(Locale.ROOT);
    Company companyA = dataSeeder.ensureCompany("RLSA-" + suffix, "RLS A " + suffix);
    Company companyB = dataSeeder.ensureCompany("RLSB-" + suffix, "RLS B " + suffix);

    Account accountA = ensureAccount(companyA, "RLS-CASH-A-" + suffix);
    Account accountB = ensureAccount(companyB, "RLS-CASH-B-" + suffix);
    Dealer dealerA = ensureDealer(companyA, "RLS-DEALER-A-" + suffix);
    Dealer dealerB = ensureDealer(companyB, "RLS-DEALER-B-" + suffix);

    long entryA =
        insertJournalEntry(
            companyA.getId(), "RLS-JE-A-" + suffix, "tenant A journal seed " + suffix);
    long entryB =
        insertJournalEntry(
            companyB.getId(), "RLS-JE-B-" + suffix, "tenant B journal seed " + suffix);
    insertJournalLine(entryA, accountA.getId(), new BigDecimal("10.00"), BigDecimal.ZERO);
    insertJournalLine(entryB, accountB.getId(), new BigDecimal("12.00"), BigDecimal.ZERO);

    long dealerLedgerA =
        insertDealerLedgerEntry(
            companyA.getId(), dealerA.getId(), entryA, "RLS-LEDGER-A-" + suffix);
    long dealerLedgerB =
        insertDealerLedgerEntry(
            companyB.getId(), dealerB.getId(), entryB, "RLS-LEDGER-B-" + suffix);

    TenantProbe tenantAProbe =
        withTenantContext(
            companyA.getId(),
            connection ->
                new TenantProbe(
                    queryForLong(
                        connection,
                        "SELECT COUNT(*) FROM journal_entries WHERE company_id = ?",
                        companyA.getId()),
                    queryForLong(
                        connection,
                        "SELECT COUNT(*) FROM journal_entries WHERE company_id = ?",
                        companyB.getId()),
                    queryForLong(
                        connection,
                        """
                        SELECT COUNT(*)
                        FROM journal_lines jl
                        JOIN journal_entries je ON je.id = jl.journal_entry_id
                        WHERE je.company_id = ?
                        """,
                        companyB.getId()),
                    queryForLong(
                        connection,
                        "SELECT COUNT(*) FROM dealer_ledger_entries WHERE company_id = ?",
                        companyB.getId()),
                    executeUpdate(
                        connection,
                        "UPDATE journal_entries SET memo = memo || ' / own' WHERE id = ?",
                        entryA),
                    executeUpdate(
                        connection,
                        "UPDATE journal_entries SET memo = memo || ' / foreign' WHERE id = ?",
                        entryB),
                    executeUpdate(
                        connection,
                        "UPDATE dealer_ledger_entries SET memo = 'foreign update' WHERE id = ?",
                        dealerLedgerB),
                    executeUpdate(
                        connection,
                        "UPDATE dealer_ledger_entries SET memo = 'own update' WHERE id = ?",
                        dealerLedgerA)));

    assertThat(tenantAProbe.visibleOwnJournalEntries()).isGreaterThanOrEqualTo(1L);
    assertThat(tenantAProbe.visibleForeignJournalEntries()).isZero();
    assertThat(tenantAProbe.visibleForeignJournalLines()).isZero();
    assertThat(tenantAProbe.visibleForeignDealerLedgerRows()).isZero();
    assertThat(tenantAProbe.ownJournalEntryUpdates()).isEqualTo(1);
    assertThat(tenantAProbe.foreignJournalEntryUpdates()).isZero();
    assertThat(tenantAProbe.foreignDealerLedgerUpdates()).isZero();
    assertThat(tenantAProbe.ownDealerLedgerUpdates()).isEqualTo(1);

    assertThatThrownBy(
            () ->
                withTenantContext(
                    companyA.getId(),
                    connection -> {
                      insertJournalEntry(
                          connection,
                          companyB.getId(),
                          "RLS-JE-BLOCK-" + suffix,
                          "blocked cross-tenant write");
                      return null;
                    }))
        .isInstanceOf(DataAccessException.class);

    assertThatThrownBy(
            () ->
                withTenantContext(
                    companyA.getId(),
                    connection -> {
                      insertDealerLedgerEntry(
                          connection,
                          companyB.getId(),
                          dealerB.getId(),
                          entryB,
                          "RLS-LEDGER-BLOCK-" + suffix);
                      return null;
                    }))
        .isInstanceOf(DataAccessException.class);

    long tenantBOwnVisible =
        withTenantContext(
            companyB.getId(),
            connection ->
                queryForLong(
                    connection,
                    "SELECT COUNT(*) FROM journal_entries WHERE company_id = ?",
                    companyB.getId()));
    long tenantBForeignVisible =
        withTenantContext(
            companyB.getId(),
            connection ->
                queryForLong(
                    connection,
                    "SELECT COUNT(*) FROM journal_entries WHERE company_id = ?",
                    companyA.getId()));

    assertThat(tenantBOwnVisible).isGreaterThanOrEqualTo(1L);
    assertThat(tenantBForeignVisible).isZero();
  }

  @Test
  void appDatasourceBinding_appliesCompanyContextHolderAndFailsClosedWhenContextIsMissingOrBad() {
    ensureRlsProbeRole();

    String suffix = Long.toString(System.nanoTime(), 36).toUpperCase(Locale.ROOT);
    Company companyA = dataSeeder.ensureCompany("RLSCTXA-" + suffix, "RLS CTX A " + suffix);
    Company companyB = dataSeeder.ensureCompany("RLSCTXB-" + suffix, "RLS CTX B " + suffix);

    Account accountA = ensureAccount(companyA, "RLS-CTX-CASH-A-" + suffix);
    Account accountB = ensureAccount(companyB, "RLS-CTX-CASH-B-" + suffix);
    Dealer dealerA = ensureDealer(companyA, "RLS-CTX-DEALER-A-" + suffix);
    Dealer dealerB = ensureDealer(companyB, "RLS-CTX-DEALER-B-" + suffix);

    long entryA =
        insertJournalEntry(
            companyA.getId(), "RLS-CTX-JE-A-" + suffix, "tenant A session binding seed " + suffix);
    long entryB =
        insertJournalEntry(
            companyB.getId(), "RLS-CTX-JE-B-" + suffix, "tenant B session binding seed " + suffix);
    insertJournalLine(entryA, accountA.getId(), new BigDecimal("15.00"), BigDecimal.ZERO);
    insertJournalLine(entryB, accountB.getId(), new BigDecimal("17.00"), BigDecimal.ZERO);

    long dealerLedgerA =
        insertDealerLedgerEntry(
            companyA.getId(), dealerA.getId(), entryA, "RLS-CTX-LEDGER-A-" + suffix);
    long dealerLedgerB =
        insertDealerLedgerEntry(
            companyB.getId(), dealerB.getId(), entryB, "RLS-CTX-LEDGER-B-" + suffix);

    CompanyContextHolder.clear();
    long visibleWithoutContext =
        withProbeRole(
            connection -> queryForLong(connection, "SELECT COUNT(*) FROM journal_entries"));
    assertThat(visibleWithoutContext).isZero();
    int updatesWithoutContext =
        withProbeRole(
            connection ->
                executeUpdate(
                    connection,
                    "UPDATE journal_entries SET memo = memo || ' / no-context' WHERE id = ?",
                    entryA));
    assertThat(updatesWithoutContext).isZero();
    assertThatThrownBy(
            () ->
                withProbeRole(
                    connection -> {
                      insertJournalEntry(
                          connection,
                          companyA.getId(),
                          "RLS-CTX-NO-CONTEXT-" + suffix,
                          "missing context blocked");
                      return null;
                    }))
        .isInstanceOf(DataAccessException.class);

    CompanyContextHolder.setCompanyCode("BAD CONTEXT !");
    long visibleWithMalformedContext =
        withProbeRole(
            connection -> queryForLong(connection, "SELECT COUNT(*) FROM journal_entries"));
    assertThat(visibleWithMalformedContext).isZero();
    assertThatThrownBy(
            () ->
                withProbeRole(
                    connection -> {
                      insertJournalEntry(
                          connection,
                          companyA.getId(),
                          "RLS-CTX-BAD-CONTEXT-" + suffix,
                          "malformed context blocked");
                      return null;
                    }))
        .isInstanceOf(DataAccessException.class);

    CompanyContextHolder.setCompanyCode(companyA.getCode());
    String boundCompanyContext =
        withProbeRole(
            connection ->
                queryForString(
                    connection, "SELECT current_setting('app.current_company_id', true)"));
    assertThat(boundCompanyContext).isEqualTo(companyA.getCode());

    TenantProbe tenantAProbe =
        withProbeRole(
            connection ->
                new TenantProbe(
                    queryForLong(
                        connection,
                        "SELECT COUNT(*) FROM journal_entries WHERE company_id = ?",
                        companyA.getId()),
                    queryForLong(
                        connection,
                        "SELECT COUNT(*) FROM journal_entries WHERE company_id = ?",
                        companyB.getId()),
                    queryForLong(
                        connection,
                        """
                        SELECT COUNT(*)
                        FROM journal_lines jl
                        JOIN journal_entries je ON je.id = jl.journal_entry_id
                        WHERE je.company_id = ?
                        """,
                        companyB.getId()),
                    queryForLong(
                        connection,
                        "SELECT COUNT(*) FROM dealer_ledger_entries WHERE company_id = ?",
                        companyB.getId()),
                    executeUpdate(
                        connection,
                        "UPDATE journal_entries SET memo = memo || ' / own live context' WHERE id ="
                            + " ?",
                        entryA),
                    executeUpdate(
                        connection,
                        "UPDATE journal_entries SET memo = memo || ' / foreign live context' WHERE"
                            + " id = ?",
                        entryB),
                    executeUpdate(
                        connection,
                        "UPDATE dealer_ledger_entries SET memo = 'foreign live context update'"
                            + " WHERE id = ?",
                        dealerLedgerB),
                    executeUpdate(
                        connection,
                        "UPDATE dealer_ledger_entries SET memo = 'own live context update' WHERE id"
                            + " = ?",
                        dealerLedgerA)));

    assertThat(tenantAProbe.visibleOwnJournalEntries()).isGreaterThanOrEqualTo(1L);
    assertThat(tenantAProbe.visibleForeignJournalEntries()).isZero();
    assertThat(tenantAProbe.visibleForeignJournalLines()).isZero();
    assertThat(tenantAProbe.visibleForeignDealerLedgerRows()).isZero();
    assertThat(tenantAProbe.ownJournalEntryUpdates()).isEqualTo(1);
    assertThat(tenantAProbe.foreignJournalEntryUpdates()).isZero();
    assertThat(tenantAProbe.foreignDealerLedgerUpdates()).isZero();
    assertThat(tenantAProbe.ownDealerLedgerUpdates()).isEqualTo(1);

    assertThatThrownBy(
            () ->
                withProbeRole(
                    connection -> {
                      insertJournalEntry(
                          connection,
                          companyB.getId(),
                          "RLS-CTX-BLOCK-WRITE-JE-" + suffix,
                          "live context cross-tenant write blocked");
                      return null;
                    }))
        .isInstanceOf(DataAccessException.class);

    assertThatThrownBy(
            () ->
                withProbeRole(
                    connection -> {
                      insertDealerLedgerEntry(
                          connection,
                          companyB.getId(),
                          dealerB.getId(),
                          entryB,
                          "RLS-CTX-BLOCK-WRITE-LEDGER-" + suffix);
                      return null;
                    }))
        .isInstanceOf(DataAccessException.class);

    CompanyContextHolder.setCompanyCode(companyB.getCode());
    long tenantBOwnVisible =
        withProbeRole(
            connection ->
                queryForLong(
                    connection,
                    "SELECT COUNT(*) FROM journal_entries WHERE company_id = ?",
                    companyB.getId()));
    long tenantBForeignVisible =
        withProbeRole(
            connection ->
                queryForLong(
                    connection,
                    "SELECT COUNT(*) FROM journal_entries WHERE company_id = ?",
                    companyA.getId()));

    assertThat(tenantBOwnVisible).isGreaterThanOrEqualTo(1L);
    assertThat(tenantBForeignVisible).isZero();
  }

  private Account ensureAccount(Company company, String code) {
    return accountRepository
        .findByCompanyAndCodeIgnoreCase(company, code)
        .orElseGet(
            () -> {
              Account account = new Account();
              account.setCompany(company);
              account.setCode(code);
              account.setName("RLS " + code);
              account.setType(AccountType.ASSET);
              account.setBalance(BigDecimal.ZERO);
              return accountRepository.save(account);
            });
  }

  private Dealer ensureDealer(Company company, String code) {
    return dealerRepository
        .findByCompanyAndCodeIgnoreCase(company, code)
        .orElseGet(
            () -> {
              Dealer dealer = new Dealer();
              dealer.setCompany(company);
              dealer.setCode(code);
              dealer.setName("RLS " + code);
              dealer.setStatus("ACTIVE");
              dealer.setCreditLimit(new BigDecimal("100000.00"));
              dealer.setOutstandingBalance(BigDecimal.ZERO);
              dealer.setReceivableAccount(ensureAccount(company, "RLS-AR-" + code));
              return dealerRepository.save(dealer);
            });
  }

  private long insertJournalEntry(Long companyId, String referenceNumber, String memo) {
    Long insertedId =
        jdbcTemplate.queryForObject(
            """
INSERT INTO journal_entries
    (company_id, reference_number, memo, status, entry_date, created_at, updated_at, version, currency)
VALUES
    (?, ?, ?, 'POSTED', ?, NOW(), NOW(), 0, 'INR')
RETURNING id
""",
            Long.class,
            companyId,
            referenceNumber,
            memo,
            Date.valueOf(LocalDate.now()));
    assertThat(insertedId).isNotNull();
    return insertedId;
  }

  private long insertJournalEntry(
      Connection connection, Long companyId, String referenceNumber, String memo)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            """
INSERT INTO journal_entries
    (company_id, reference_number, memo, status, entry_date, created_at, updated_at, version, currency)
VALUES
    (?, ?, ?, 'POSTED', ?, NOW(), NOW(), 0, 'INR')
RETURNING id
""")) {
      statement.setLong(1, companyId);
      statement.setString(2, referenceNumber);
      statement.setString(3, memo);
      statement.setDate(4, Date.valueOf(LocalDate.now()));
      try (ResultSet resultSet = statement.executeQuery()) {
        resultSet.next();
        return resultSet.getLong(1);
      }
    }
  }

  private void insertJournalLine(
      long journalEntryId, long accountId, BigDecimal debit, BigDecimal credit) {
    int inserted =
        jdbcTemplate.update(
            """
            INSERT INTO journal_lines
                (journal_entry_id, account_id, description, debit, credit, version)
            VALUES
                (?, ?, ?, ?, ?, 0)
            """,
            journalEntryId,
            accountId,
            "rls line",
            debit,
            credit);
    assertThat(inserted).isEqualTo(1);
  }

  private long insertDealerLedgerEntry(
      Long companyId, Long dealerId, long journalEntryId, String referenceNumber) {
    Long insertedId =
        jdbcTemplate.queryForObject(
            """
INSERT INTO dealer_ledger_entries
    (company_id, dealer_id, journal_entry_id, entry_date, reference_number, memo, debit, credit, version)
VALUES
    (?, ?, ?, ?, ?, ?, ?, ?, 0)
RETURNING id
""",
            Long.class,
            companyId,
            dealerId,
            journalEntryId,
            Date.valueOf(LocalDate.now()),
            referenceNumber,
            "rls dealer ledger",
            new BigDecimal("25.00"),
            BigDecimal.ZERO);
    assertThat(insertedId).isNotNull();
    return insertedId;
  }

  private long insertDealerLedgerEntry(
      Connection connection,
      Long companyId,
      Long dealerId,
      long journalEntryId,
      String referenceNumber)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            """
INSERT INTO dealer_ledger_entries
    (company_id, dealer_id, journal_entry_id, entry_date, reference_number, memo, debit, credit, version)
VALUES
    (?, ?, ?, ?, ?, ?, ?, ?, 0)
RETURNING id
""")) {
      statement.setLong(1, companyId);
      statement.setLong(2, dealerId);
      statement.setLong(3, journalEntryId);
      statement.setDate(4, Date.valueOf(LocalDate.now()));
      statement.setString(5, referenceNumber);
      statement.setString(6, "rls dealer ledger");
      statement.setBigDecimal(7, new BigDecimal("25.00"));
      statement.setBigDecimal(8, BigDecimal.ZERO);
      try (ResultSet resultSet = statement.executeQuery()) {
        resultSet.next();
        return resultSet.getLong(1);
      }
    }
  }

  private long queryForLong(Connection connection, String sql, Object... params)
      throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      for (int i = 0; i < params.length; i++) {
        statement.setObject(i + 1, params[i]);
      }
      try (ResultSet resultSet = statement.executeQuery()) {
        resultSet.next();
        return resultSet.getLong(1);
      }
    }
  }

  private String queryForString(Connection connection, String sql, Object... params)
      throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      for (int i = 0; i < params.length; i++) {
        statement.setObject(i + 1, params[i]);
      }
      try (ResultSet resultSet = statement.executeQuery()) {
        resultSet.next();
        return resultSet.getString(1);
      }
    }
  }

  private int executeUpdate(Connection connection, String sql, Object... params)
      throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      for (int i = 0; i < params.length; i++) {
        statement.setObject(i + 1, params[i]);
      }
      return statement.executeUpdate();
    }
  }

  private <T> T withProbeRole(TenantConnectionCallback<T> callback) {
    return jdbcTemplate.execute(
        (Connection connection) -> {
          try (PreparedStatement setRole =
              connection.prepareStatement("SET ROLE " + RLS_PROBE_ROLE)) {
            setRole.execute();
          }
          try {
            return callback.doInConnection(connection);
          } finally {
            try (PreparedStatement resetRole = connection.prepareStatement("RESET ROLE")) {
              resetRole.execute();
            }
          }
        });
  }

  private <T> T withTenantContext(Long companyId, TenantConnectionCallback<T> callback) {
    return jdbcTemplate.execute(
        (Connection connection) -> {
          try (PreparedStatement setRole =
              connection.prepareStatement("SET ROLE " + RLS_PROBE_ROLE)) {
            setRole.execute();
          }
          try (PreparedStatement setCompanyContext =
              connection.prepareStatement(
                  "SELECT set_config('app.current_company_id', ?, false)")) {
            setCompanyContext.setString(1, companyId.toString());
            setCompanyContext.execute();
          }
          try {
            return callback.doInConnection(connection);
          } finally {
            try (PreparedStatement clearCompanyContext =
                connection.prepareStatement(
                    "SELECT set_config('app.current_company_id', '', false)")) {
              clearCompanyContext.execute();
            }
            try (PreparedStatement resetRole = connection.prepareStatement("RESET ROLE")) {
              resetRole.execute();
            }
          }
        });
  }

  private void ensureRlsProbeRole() {
    jdbcTemplate.execute(
        """
        DO $$
        BEGIN
            IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'erp_rls_probe') THEN
                CREATE ROLE erp_rls_probe;
            END IF;
        END;
        $$;
        """);
    jdbcTemplate.execute("GRANT USAGE ON SCHEMA public TO " + RLS_PROBE_ROLE);
    jdbcTemplate.execute(
        "GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE "
            + String.join(", ", ACCOUNTING_RLS_TABLES)
            + " TO "
            + RLS_PROBE_ROLE);
    jdbcTemplate.execute(
        "GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO " + RLS_PROBE_ROLE);
  }

  private boolean asBoolean(Object value) {
    if (value instanceof Boolean bool) {
      return bool;
    }
    return Boolean.parseBoolean(String.valueOf(value));
  }

  @FunctionalInterface
  private interface TenantConnectionCallback<T> {
    T doInConnection(Connection connection) throws SQLException;
  }

  private record TenantProbe(
      long visibleOwnJournalEntries,
      long visibleForeignJournalEntries,
      long visibleForeignJournalLines,
      long visibleForeignDealerLedgerRows,
      int ownJournalEntryUpdates,
      int foreignJournalEntryUpdates,
      int foreignDealerLedgerUpdates,
      int ownDealerLedgerUpdates) {}
}
