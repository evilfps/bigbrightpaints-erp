package com.bigbrightpaints.erp.modules.accounting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.bigbrightpaints.erp.core.audit.AuditService;
import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriod;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriodStatus;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryType;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalLine;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryDto;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryRequest;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.test.support.ReflectionFieldAccess;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;

@ExtendWith(MockitoExtension.class)
@Tag("critical")
class JournalEntryMutationServiceTest {

  @Mock private CompanyContextService companyContextService;
  @Mock private AccountRepository accountRepository;
  @Mock private JournalEntryRepository journalEntryRepository;
  @Mock private AccountingPeriodService accountingPeriodService;
  @Mock private CompanyScopedAccountingLookupService accountingLookupService;
  @Mock private EntityManager entityManager;
  @Mock private com.bigbrightpaints.erp.core.config.SystemSettingsService systemSettingsService;
  @Mock private AuditService auditService;
  @Mock private AccountingAuditService accountingAuditService;
  @Mock private PeriodValidationService periodValidationService;
  @Mock private AccountResolutionService accountResolutionService;
  @Mock private JournalReferenceService journalReferenceService;
  @Mock private AccountingDtoMapperService dtoMapperService;
  @Mock private JournalPartnerContextService journalPartnerContextService;
  @Mock private JournalDuplicateGuardService journalDuplicateGuardService;
  @Mock private JournalLinePostingService journalLinePostingService;
  @Mock private AccountingComplianceAuditService accountingComplianceAuditService;
  @Mock private Query advisoryLockQuery;

  private JournalEntryMutationService service;
  private Company company;
  private Account debitAccount;
  private Account creditAccount;

  @BeforeEach
  void setUp() {
    service =
        new JournalEntryMutationService(
            companyContextService,
            accountRepository,
            journalEntryRepository,
            accountingPeriodService,
            accountingLookupService,
            entityManager,
            systemSettingsService,
            auditService,
            accountingAuditService,
            periodValidationService,
            accountResolutionService,
            journalReferenceService,
            dtoMapperService,
            journalPartnerContextService,
            journalDuplicateGuardService,
            journalLinePostingService);
    ReflectionTestUtils.setField(
        service, "accountingComplianceAuditService", accountingComplianceAuditService);

    company = new Company();
    ReflectionFieldAccess.setField(company, "id", 7L);
    company.setCode("ACME");
    company.setBaseCurrency("INR");
    debitAccount = account(1L, "DEBIT-1");
    creditAccount = account(2L, "CREDIT-1");

    when(companyContextService.requireCurrentCompany()).thenReturn(company);
    when(journalReferenceService.resolveCurrency(any(), eq(company))).thenReturn("INR");
    when(journalReferenceService.resolveFxRate(eq("INR"), eq(company), any()))
        .thenReturn(BigDecimal.ONE);
    when(journalReferenceService.resolveJournalReference(eq(company), any())).thenReturn("JRN-1");
    when(journalEntryRepository.findByCompanyAndReferenceNumber(eq(company), any()))
        .thenReturn(Optional.empty());
    when(entityManager.createNativeQuery(anyString())).thenReturn(advisoryLockQuery);
    when(advisoryLockQuery.setParameter(anyInt(), any())).thenReturn(advisoryLockQuery);
    when(advisoryLockQuery.getSingleResult()).thenReturn(0);
    when(systemSettingsService.isPeriodLockEnforced()).thenReturn(false);

    AccountingPeriod period = new AccountingPeriod();
    ReflectionFieldAccess.setField(period, "id", 31L);
    period.setStatus(AccountingPeriodStatus.OPEN);
    when(accountingPeriodService.ensurePeriod(eq(company), any(LocalDate.class)))
        .thenReturn(period);

    when(accountRepository.lockByCompanyAndId(company, 1L)).thenReturn(Optional.of(debitAccount));
    when(accountRepository.lockByCompanyAndId(company, 2L)).thenReturn(Optional.of(creditAccount));
    when(accountRepository.updateBalanceAtomic(eq(company), any(Long.class), any(BigDecimal.class)))
        .thenReturn(1);

    when(journalPartnerContextService.resolve(eq(company), eq(null), eq(null), anyList()))
        .thenReturn(
            new JournalPartnerContextService.ResolvedPartnerContext(
                null, null, null, null, false, false));

    when(accountingAuditService.resolveCurrentUsername()).thenReturn("tester@acme.test");
    when(accountingAuditService.recordJournalEntryPostedEventSafe(any(), anyMap()))
        .thenReturn(true);
    lenient()
        .when(dtoMapperService.toJournalEntryDto(any()))
        .thenReturn(journalEntryDto(901L, "JRN-1"));

    when(journalLinePostingService.buildPostedLine(any(), any(), anyMap(), eq(BigDecimal.ONE)))
        .thenAnswer(
            invocation -> {
              JournalEntry entry = invocation.getArgument(0);
              JournalEntryRequest.JournalLineRequest lineRequest = invocation.getArgument(1);
              @SuppressWarnings("unchecked")
              Map<Long, Account> lockedAccounts = invocation.getArgument(2);
              JournalLine line = new JournalLine();
              line.setJournalEntry(entry);
              line.setAccount(lockedAccounts.get(lineRequest.accountId()));
              line.setDescription(lineRequest.description());
              line.setDebit(lineRequest.debit());
              line.setCredit(lineRequest.credit());
              entry.addLine(line);
              return line;
            });
  }

  @Test
  void createJournalEntry_recordsEmptyFxMetadataWhenNoRoundingAdjustmentExists() {
    when(journalEntryRepository.save(any(JournalEntry.class)))
        .thenAnswer(
            invocation -> {
              JournalEntry entry = invocation.getArgument(0);
              ReflectionFieldAccess.setField(entry, "id", 101L);
              long lineId = 2001L;
              for (JournalLine line : entry.getLines()) {
                ReflectionFieldAccess.setField(line, "id", lineId++);
              }
              return entry;
            });

    JournalEntryDto result = service.createJournalEntry(request("100.00", "100.00"));

    assertThat(result.id()).isEqualTo(901L);
    ArgumentCaptor<Map<String, String>> metadataCaptor = ArgumentCaptor.forClass(Map.class);
    verify(accountingComplianceAuditService)
        .recordJournalCreation(eq(company), any(JournalEntry.class), metadataCaptor.capture());
    assertThat(metadataCaptor.getValue()).isEmpty();
  }

  @Test
  void createJournalEntry_recordsEmptyFxMetadataWhenRoundingAdjustedLineIsMissing() {
    when(journalLinePostingService.absorbRoundingDelta(any(), anyList(), anyMap()))
        .thenAnswer(
            invocation -> {
              @SuppressWarnings("unchecked")
              List<JournalLine> postedLines = invocation.getArgument(1);
              postedLines.get(1).setCredit(new BigDecimal("100.00"));
              return new JournalLinePostingService.RoundingAdjustment(
                  null, new BigDecimal("99.99"), new BigDecimal("100.00"), "FX_ROUNDING");
            });
    when(journalEntryRepository.save(any(JournalEntry.class)))
        .thenAnswer(
            invocation -> {
              JournalEntry entry = invocation.getArgument(0);
              ReflectionFieldAccess.setField(entry, "id", 106L);
              return entry;
            });

    service.createJournalEntry(request("100.00", "99.99"));

    ArgumentCaptor<Map<String, String>> metadataCaptor = ArgumentCaptor.forClass(Map.class);
    verify(accountingComplianceAuditService)
        .recordJournalCreation(eq(company), any(JournalEntry.class), metadataCaptor.capture());
    assertThat(metadataCaptor.getValue()).isEmpty();
    verify(entityManager, never()).flush();
  }

  @Test
  void createJournalEntry_usesPresetAdjustedLineIdWithoutFlush() {
    when(journalLinePostingService.absorbRoundingDelta(any(), anyList(), anyMap()))
        .thenAnswer(
            invocation -> {
              @SuppressWarnings("unchecked")
              List<JournalLine> postedLines = invocation.getArgument(1);
              JournalLine adjustedLine = postedLines.get(1);
              ReflectionFieldAccess.setField(adjustedLine, "id", 8801L);
              adjustedLine.setCredit(new BigDecimal("100.00"));
              return new JournalLinePostingService.RoundingAdjustment(
                  adjustedLine,
                  new BigDecimal("99.99"),
                  new BigDecimal("100.00"),
                  "FX_ROUNDING_CREDIT_ADJUSTMENT");
            });
    when(journalEntryRepository.save(any(JournalEntry.class)))
        .thenAnswer(
            invocation -> {
              JournalEntry entry = invocation.getArgument(0);
              ReflectionFieldAccess.setField(entry, "id", 107L);
              return entry;
            });

    service.createJournalEntry(request("100.00", "99.99"));

    ArgumentCaptor<Map<String, String>> metadataCaptor = ArgumentCaptor.forClass(Map.class);
    verify(accountingComplianceAuditService)
        .recordJournalCreation(eq(company), any(JournalEntry.class), metadataCaptor.capture());
    assertThat(metadataCaptor.getValue()).containsEntry("adjustedLineId", "8801");
    verify(entityManager, never()).flush();
  }

  @Test
  void createJournalEntry_resolvesRoundingAdjustedLineIdByExactLineMatchBeforeFlush() {
    when(journalLinePostingService.absorbRoundingDelta(any(), anyList(), anyMap()))
        .thenAnswer(
            invocation -> {
              @SuppressWarnings("unchecked")
              List<JournalLine> postedLines = invocation.getArgument(1);
              JournalLine creditLine = postedLines.get(1);
              BigDecimal originalAmount = creditLine.getCredit();
              creditLine.setCredit(new BigDecimal("100.00"));
              JournalLine unresolvedAdjustedLine = new JournalLine();
              unresolvedAdjustedLine.setAccount(creditAccount);
              unresolvedAdjustedLine.setDebit(BigDecimal.ZERO);
              unresolvedAdjustedLine.setCredit(new BigDecimal("100.00"));
              unresolvedAdjustedLine.setDescription(creditLine.getDescription());
              return new JournalLinePostingService.RoundingAdjustment(
                  unresolvedAdjustedLine,
                  originalAmount,
                  new BigDecimal("100.00"),
                  "FX_ROUNDING_CREDIT_ADJUSTMENT");
            });
    when(journalEntryRepository.save(any(JournalEntry.class)))
        .thenAnswer(
            invocation -> {
              JournalEntry entry = invocation.getArgument(0);
              ReflectionFieldAccess.setField(entry, "id", 102L);
              long lineId = 3001L;
              for (JournalLine line : entry.getLines()) {
                ReflectionFieldAccess.setField(line, "id", lineId++);
              }
              return entry;
            });

    service.createJournalEntry(request("100.00", "99.99"));

    ArgumentCaptor<Map<String, String>> metadataCaptor = ArgumentCaptor.forClass(Map.class);
    verify(accountingComplianceAuditService)
        .recordJournalCreation(eq(company), any(JournalEntry.class), metadataCaptor.capture());
    Map<String, String> metadata = metadataCaptor.getValue();
    assertThat(metadata).containsEntry("adjustedLineId", "3002");
    assertThat(metadata).containsEntry("originalAmount", "99.99");
    assertThat(metadata).containsEntry("adjustedAmount", "100");
    assertThat(metadata).containsEntry("adjustmentReason", "FX_ROUNDING_CREDIT_ADJUSTMENT");
    verify(entityManager, never()).flush();
  }

  @Test
  void createJournalEntry_resolvesRoundingAdjustedLineIdAfterFlushSetsAdjustedLineIdentity() {
    AtomicReference<JournalLine> adjustedLineRef = new AtomicReference<>();
    when(journalLinePostingService.absorbRoundingDelta(any(), anyList(), anyMap()))
        .thenAnswer(
            invocation -> {
              @SuppressWarnings("unchecked")
              List<JournalLine> postedLines = invocation.getArgument(1);
              JournalLine adjustedLine = postedLines.get(1);
              adjustedLineRef.set(adjustedLine);
              adjustedLine.setCredit(new BigDecimal("100.00"));
              return new JournalLinePostingService.RoundingAdjustment(
                  adjustedLine,
                  new BigDecimal("99.99"),
                  new BigDecimal("100.00"),
                  "FX_ROUNDING_CREDIT_ADJUSTMENT");
            });
    when(journalEntryRepository.save(any(JournalEntry.class)))
        .thenAnswer(
            invocation -> {
              JournalEntry entry = invocation.getArgument(0);
              ReflectionFieldAccess.setField(entry, "id", 103L);
              return entry;
            });
    doAnswer(
            invocation -> {
              ReflectionFieldAccess.setField(adjustedLineRef.get(), "id", 777L);
              return null;
            })
        .when(entityManager)
        .flush();

    service.createJournalEntry(request("100.00", "99.99"));

    ArgumentCaptor<Map<String, String>> metadataCaptor = ArgumentCaptor.forClass(Map.class);
    verify(accountingComplianceAuditService)
        .recordJournalCreation(eq(company), any(JournalEntry.class), metadataCaptor.capture());
    assertThat(metadataCaptor.getValue()).containsEntry("adjustedLineId", "777");
    verify(entityManager).flush();
  }

  @Test
  void createJournalEntry_resolvesRoundingAdjustedLineIdUsingAdjustedAmountFallback() {
    when(journalLinePostingService.absorbRoundingDelta(any(), anyList(), anyMap()))
        .thenAnswer(
            invocation -> {
              @SuppressWarnings("unchecked")
              List<JournalLine> postedLines = invocation.getArgument(1);
              postedLines.get(1).setCredit(new BigDecimal("100.00"));
              JournalLine unresolvedAdjustedLine = new JournalLine();
              unresolvedAdjustedLine.setAccount(creditAccount);
              unresolvedAdjustedLine.setDebit(new BigDecimal("1.00"));
              unresolvedAdjustedLine.setCredit(BigDecimal.ZERO);
              unresolvedAdjustedLine.setDescription("different-description");
              return new JournalLinePostingService.RoundingAdjustment(
                  unresolvedAdjustedLine,
                  new BigDecimal("99.99"),
                  new BigDecimal("100.00"),
                  "FX_ROUNDING_CREDIT_ADJUSTMENT");
            });
    when(journalEntryRepository.save(any(JournalEntry.class)))
        .thenAnswer(
            invocation -> {
              JournalEntry entry = invocation.getArgument(0);
              ReflectionFieldAccess.setField(entry, "id", 104L);
              long lineId = 4001L;
              for (JournalLine line : entry.getLines()) {
                ReflectionFieldAccess.setField(line, "id", lineId++);
              }
              return entry;
            });

    service.createJournalEntry(request("100.00", "99.99"));

    ArgumentCaptor<Map<String, String>> metadataCaptor = ArgumentCaptor.forClass(Map.class);
    verify(accountingComplianceAuditService)
        .recordJournalCreation(eq(company), any(JournalEntry.class), metadataCaptor.capture());
    assertThat(metadataCaptor.getValue()).containsEntry("adjustedLineId", "4002");
    verify(entityManager, never()).flush();
  }

  @Test
  void createJournalEntry_failsClosedWhenRoundingAdjustedLineCannotBeResolved() {
    when(journalLinePostingService.absorbRoundingDelta(any(), anyList(), anyMap()))
        .thenAnswer(
            invocation -> {
              @SuppressWarnings("unchecked")
              List<JournalLine> postedLines = invocation.getArgument(1);
              postedLines.get(1).setCredit(new BigDecimal("100.00"));
              JournalLine unresolvedAdjustedLine = new JournalLine();
              unresolvedAdjustedLine.setAccount(null);
              unresolvedAdjustedLine.setDebit(BigDecimal.ZERO);
              unresolvedAdjustedLine.setCredit(BigDecimal.ZERO);
              return new JournalLinePostingService.RoundingAdjustment(
                  unresolvedAdjustedLine,
                  new BigDecimal("99.99"),
                  new BigDecimal("100.00"),
                  "FX_ROUNDING_CREDIT_ADJUSTMENT");
            });
    when(journalEntryRepository.save(any(JournalEntry.class)))
        .thenAnswer(
            invocation -> {
              JournalEntry entry = invocation.getArgument(0);
              ReflectionFieldAccess.setField(entry, "id", 105L);
              return entry;
            });

    assertThatThrownBy(() -> service.createJournalEntry(request("100.00", "99.99")))
        .isInstanceOfSatisfying(
            ApplicationException.class,
            ex -> {
              assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INTERNAL_CONCURRENCY_FAILURE);
              assertThat(ex.getMessage()).contains("could not resolve persisted journal line id");
            });
    verify(accountingComplianceAuditService, never())
        .recordJournalCreation(any(Company.class), any(JournalEntry.class), anyMap());
  }

  private JournalEntryRequest request(String debitAmount, String creditAmount) {
    return new JournalEntryRequest(
        null,
        LocalDate.of(2026, 4, 8),
        "rounding metadata test",
        null,
        null,
        false,
        List.of(
            new JournalEntryRequest.JournalLineRequest(
                1L, "Debit line", new BigDecimal(debitAmount), BigDecimal.ZERO),
            new JournalEntryRequest.JournalLineRequest(
                2L, "Credit line", BigDecimal.ZERO, new BigDecimal(creditAmount))),
        "INR",
        BigDecimal.ONE,
        "MANUAL",
        "ROUNDING-TEST",
        JournalEntryType.MANUAL.name(),
        List.of());
  }

  private Account account(Long id, String code) {
    Account account = new Account();
    ReflectionFieldAccess.setField(account, "id", id);
    account.setCode(code);
    account.setBalance(BigDecimal.ZERO);
    return account;
  }

  private JournalEntryDto journalEntryDto(Long id, String referenceNumber) {
    return new JournalEntryDto(
        id,
        null,
        referenceNumber,
        LocalDate.of(2026, 4, 8),
        "rounding metadata test",
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
        List.of(),
        null,
        null,
        null,
        null,
        null,
        null);
  }
}
