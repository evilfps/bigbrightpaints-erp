package com.bigbrightpaints.erp.codered;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import com.bigbrightpaints.erp.codered.support.CoderedConcurrencyHarness;
import com.bigbrightpaints.erp.codered.support.CoderedDbAssertions;
import com.bigbrightpaints.erp.codered.support.CoderedRetry;
import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.security.CompanyContextHolder;
import com.bigbrightpaints.erp.modules.accounting.controller.AccountingController;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountType;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalReferenceMappingRepository;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryDto;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryRequest;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingService;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.test.AbstractIntegrationTest;
import com.bigbrightpaints.erp.test.support.TestDateUtils;

class CR_ManualJournalSafetyTest extends AbstractIntegrationTest {

  @Autowired private CompanyRepository companyRepository;
  @Autowired private AccountRepository accountRepository;
  @Autowired private AccountingService accountingService;
  @Autowired private AccountingController accountingController;
  @Autowired private JournalEntryRepository journalEntryRepository;
  @Autowired private JournalReferenceMappingRepository journalReferenceMappingRepository;
  @Autowired private JdbcTemplate jdbcTemplate;

  @AfterEach
  void clearThreadLocals() {
    CompanyContextHolder.clear();
    SecurityContextHolder.clearContext();
  }

  @Test
  void reservedNamespace_isRejectedAtApiBoundary_andDoesNotWriteState() {
    String companyCode = "CR-MANUAL-" + shortId();
    Company company = bootstrapCompany(companyCode);
    Account bank = ensureAccount(company, "BANK", "Bank", AccountType.ASSET);
    Account expense = ensureAccount(company, "MISC-EXP", "Misc Expense", AccountType.EXPENSE);

    authenticateAdmin();

    String reserved = "PAYROLL-" + shortId();
    JournalEntryRequest apiRequest =
        new JournalEntryRequest(
            reserved,
            TestDateUtils.safeDate(company),
            "Should be rejected",
            null,
            null,
            false,
            List.of(
                new JournalEntryRequest.JournalLineRequest(
                    bank.getId(), "Dr", new BigDecimal("100.00"), BigDecimal.ZERO),
                new JournalEntryRequest.JournalLineRequest(
                    expense.getId(), "Cr", BigDecimal.ZERO, new BigDecimal("100.00"))));

    assertThatThrownBy(() -> accountingController.createJournalEntry(apiRequest))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("reserved for system journals");

    assertThat(
            journalReferenceMappingRepository.findAllByCompanyAndLegacyReferenceIgnoreCase(
                company, reserved))
        .as("No idempotency reservation should be created for rejected reference")
        .isEmpty();

    CoderedDbAssertions.assertNoOrphanJournalEntries(jdbcTemplate, company.getId());
  }

  @Test
  void manualJournal_idempotentUnderConcurrency_bothCallersGetSameId() {
    String companyCode = "CR-MANUAL-CONC-" + shortId();
    Company company = bootstrapCompany(companyCode);
    Account bank = ensureAccount(company, "BANK", "Bank", AccountType.ASSET);
    Account expense = ensureAccount(company, "MISC-EXP", "Misc Expense", AccountType.EXPENSE);

    String idempotencyKey = "MANUAL-" + UUID.randomUUID();
    LocalDate entryDate = TestDateUtils.safeDate(company);
    JournalEntryRequest sanitized =
        new JournalEntryRequest(
            null,
            entryDate,
            "CODE-RED manual journal",
            null,
            null,
            false,
            List.of(
                new JournalEntryRequest.JournalLineRequest(
                    bank.getId(), "Dr", new BigDecimal("100.00"), BigDecimal.ZERO),
                new JournalEntryRequest.JournalLineRequest(
                    expense.getId(), "Cr", BigDecimal.ZERO, new BigDecimal("100.00"))));

    var result =
        CoderedConcurrencyHarness.run(
            2,
            3,
            Duration.ofSeconds(30),
            threadIndex ->
                () -> {
                  CompanyContextHolder.setCompanyCode(companyCode);
                  try {
                    return accountingService.createManualJournalEntry(sanitized, idempotencyKey);
                  } finally {
                    CompanyContextHolder.clear();
                  }
                },
            CoderedRetry::isRetryable);

    assertThat(result.outcomes())
        .allMatch(outcome -> outcome instanceof CoderedConcurrencyHarness.Outcome.Success<?>);

    List<Long> journalIds =
        result.outcomes().stream()
            .map(
                outcome ->
                    ((CoderedConcurrencyHarness.Outcome.Success<JournalEntryDto>) outcome).value())
            .map(JournalEntryDto::id)
            .distinct()
            .toList();
    assertThat(journalIds).as("Both callers receive the same journal id").hasSize(1);

    assertThat(
            journalReferenceMappingRepository.findAllByCompanyAndLegacyReferenceIgnoreCase(
                company, idempotencyKey))
        .as("Single idempotency mapping row")
        .hasSize(1)
        .allSatisfy(
            mapping -> {
              assertThat(mapping.getEntityId()).as("mapping entityId").isNotNull();
              assertThat(mapping.getCanonicalReference())
                  .as("mapping canonical reference")
                  .isNotBlank();
            });

    JournalEntry persisted = journalEntryRepository.findById(journalIds.getFirst()).orElseThrow();
    assertThat(persisted.getJournalType())
        .isEqualTo(com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryType.MANUAL);
    assertThat(persisted.getSourceModule()).isEqualTo("MANUAL");

    CoderedDbAssertions.assertBalancedJournal(journalEntryRepository, journalIds.getFirst());
    CoderedDbAssertions.assertNoOrphanJournalEntries(jdbcTemplate, company.getId());
  }

  @Test
  void retryAfterFailure_doesNotLeaveReservedKey_andSucceedsOnRetry() {
    String companyCode = "CR-MANUAL-RETRY-" + shortId();
    Company company = bootstrapCompany(companyCode);
    Account bank = ensureAccount(company, "BANK", "Bank", AccountType.ASSET);
    Account expense = ensureAccount(company, "MISC-EXP", "Misc Expense", AccountType.EXPENSE);

    String idempotencyKey = "MANUAL-" + UUID.randomUUID();
    LocalDate entryDate = TestDateUtils.safeDate(company);

    JournalEntryRequest invalidUnbalanced =
        new JournalEntryRequest(
            null,
            entryDate,
            "CODE-RED invalid manual journal (unbalanced)",
            null,
            null,
            false,
            List.of(
                new JournalEntryRequest.JournalLineRequest(
                    bank.getId(), "Dr", new BigDecimal("100.00"), BigDecimal.ZERO),
                new JournalEntryRequest.JournalLineRequest(
                    expense.getId(), "Cr", BigDecimal.ZERO, new BigDecimal("99.90"))));

    CompanyContextHolder.setCompanyCode(companyCode);
    assertThatThrownBy(
            () -> accountingService.createManualJournalEntry(invalidUnbalanced, idempotencyKey))
        .isInstanceOf(RuntimeException.class);
    CompanyContextHolder.clear();

    assertThat(
            journalReferenceMappingRepository.findAllByCompanyAndLegacyReferenceIgnoreCase(
                company, idempotencyKey))
        .as("Failed attempt should not leave a reserved mapping behind")
        .isEmpty();

    JournalEntryRequest valid =
        new JournalEntryRequest(
            null,
            entryDate,
            "CODE-RED manual journal",
            null,
            null,
            false,
            List.of(
                new JournalEntryRequest.JournalLineRequest(
                    bank.getId(), "Dr", new BigDecimal("100.00"), BigDecimal.ZERO),
                new JournalEntryRequest.JournalLineRequest(
                    expense.getId(), "Cr", BigDecimal.ZERO, new BigDecimal("100.00"))));

    CompanyContextHolder.setCompanyCode(companyCode);
    JournalEntryDto first = accountingService.createManualJournalEntry(valid, idempotencyKey);
    JournalEntryDto second = accountingService.createManualJournalEntry(valid, idempotencyKey);
    CompanyContextHolder.clear();

    assertThat(first.id()).isNotNull();
    assertThat(second.id()).isEqualTo(first.id());
    CoderedDbAssertions.assertBalancedJournal(journalEntryRepository, first.id());
    CoderedDbAssertions.assertNoOrphanJournalEntries(jdbcTemplate, company.getId());
  }

  private Company bootstrapCompany(String companyCode) {
    dataSeeder.ensureCompany(companyCode, companyCode + " Ltd");
    CompanyContextHolder.setCompanyCode(companyCode);
    Company company = companyRepository.findByCodeIgnoreCase(companyCode).orElseThrow();
    company.setTimezone("UTC");
    company.setBaseCurrency("INR");
    return companyRepository.save(company);
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
              account.setActive(true);
              account.setBalance(BigDecimal.ZERO);
              return accountRepository.save(account);
            });
  }

  private void authenticateAdmin() {
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken(
                "codered",
                "N/A",
                List.of(
                    new SimpleGrantedAuthority("ROLE_ADMIN"),
                    new SimpleGrantedAuthority("ROLE_ACCOUNTING"))));
  }

  private static String shortId() {
    return UUID.randomUUID().toString().substring(0, 8);
  }
}
