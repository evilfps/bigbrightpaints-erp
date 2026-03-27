package com.bigbrightpaints.erp.regression;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.bigbrightpaints.erp.core.security.CompanyContextHolder;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountType;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalReferenceMapping;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalReferenceMappingRepository;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryDto;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryRequest;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingService;
import com.bigbrightpaints.erp.modules.accounting.service.JournalReferenceResolver;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.test.AbstractIntegrationTest;

@DisplayName("Regression: Journal reference mappings resolve duplicates safely")
class JournalReferenceMappingRegressionIT extends AbstractIntegrationTest {

  private static final String COMPANY_CODE = "JRM-001";

  @Autowired private AccountingService accountingService;

  @Autowired private AccountRepository accountRepository;

  @Autowired private JournalReferenceMappingRepository mappingRepository;

  @Autowired private JournalReferenceResolver journalReferenceResolver;

  private Company company;
  private Account cash;
  private Account revenue;

  @BeforeEach
  void setUp() {
    company = dataSeeder.ensureCompany(COMPANY_CODE, "Journal Ref Mapping Ltd");
    CompanyContextHolder.setCompanyCode(COMPANY_CODE);
    cash = ensureAccount("CASH", "Cash", AccountType.ASSET);
    revenue = ensureAccount("REV", "Revenue", AccountType.REVENUE);
  }

  @AfterEach
  void tearDown() {
    CompanyContextHolder.clear();
  }

  @Test
  void canonicalReferenceResolvesLegacyEntryEvenWithDuplicateMappings() {
    LocalDate entryDate = LocalDate.now().minusDays(1);
    JournalEntryDto entry =
        accountingService.createJournalEntry(
            new JournalEntryRequest(
                "INV-LEGACY-1",
                entryDate,
                "Legacy sales journal",
                null,
                null,
                Boolean.FALSE,
                List.of(
                    new JournalEntryRequest.JournalLineRequest(
                        cash.getId(), "AR", new BigDecimal("100.00"), BigDecimal.ZERO),
                    new JournalEntryRequest.JournalLineRequest(
                        revenue.getId(), "Revenue", BigDecimal.ZERO, new BigDecimal("100.00")))));

    JournalReferenceMapping mappingWithEntry = new JournalReferenceMapping();
    mappingWithEntry.setCompany(company);
    mappingWithEntry.setLegacyReference("INV-LEGACY-1");
    mappingWithEntry.setCanonicalReference("INV-ORDER-1");
    mappingRepository.save(mappingWithEntry);

    JournalReferenceMapping mappingWithoutEntry = new JournalReferenceMapping();
    mappingWithoutEntry.setCompany(company);
    mappingWithoutEntry.setLegacyReference("SALE-ORDER-1");
    mappingWithoutEntry.setCanonicalReference("INV-ORDER-1");
    mappingRepository.save(mappingWithoutEntry);

    Optional<JournalEntry> resolved =
        journalReferenceResolver.findExistingEntry(company, "INV-ORDER-1");
    assertThat(resolved).isPresent();
    assertThat(resolved.get().getId()).isEqualTo(entry.id());
  }

  private Account ensureAccount(String code, String name, AccountType type) {
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
}
