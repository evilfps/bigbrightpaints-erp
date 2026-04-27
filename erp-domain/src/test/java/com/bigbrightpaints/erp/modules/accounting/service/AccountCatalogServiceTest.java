package com.bigbrightpaints.erp.modules.accounting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountType;
import com.bigbrightpaints.erp.modules.accounting.dto.AccountDto;
import com.bigbrightpaints.erp.modules.accounting.dto.AccountRequest;
import com.bigbrightpaints.erp.modules.accounting.event.AccountCacheInvalidatedEvent;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;

@ExtendWith(MockitoExtension.class)
class AccountCatalogServiceTest {

  @Mock private CompanyContextService companyContextService;
  @Mock private AccountRepository accountRepository;
  @Mock private AccountingDtoMapperService accountingDtoMapperService;
  @Mock private ApplicationEventPublisher eventPublisher;
  @Mock private ObjectProvider<AccountingComplianceAuditService> auditServiceProvider;

  private AccountCatalogService service;
  private Company company;

  @BeforeEach
  void setUp() {
    when(auditServiceProvider.getIfAvailable()).thenReturn(null);
    service =
        new AccountCatalogService(
            companyContextService,
            accountRepository,
            accountingDtoMapperService,
            eventPublisher,
            auditServiceProvider);
    company = new Company();
    company.setCode("COA");
    ReflectionTestUtils.setField(company, "id", 17L);
    when(companyContextService.requireCurrentCompany()).thenReturn(company);
  }

  @Test
  void createAccount_rejectsCompanyScopedDuplicateCodeIgnoringCase() {
    Account existing = account(91L, company, "CASH-01", AccountType.ASSET);
    when(accountRepository.findByCompanyAndCodeIgnoreCase(company, "cash-01"))
        .thenReturn(Optional.of(existing));

    assertThatThrownBy(
            () ->
                service.createAccount(
                    new AccountRequest(" cash-01 ", "Cash Duplicate", AccountType.ASSET, null)))
        .isInstanceOf(ApplicationException.class)
        .satisfies(
            ex -> {
              ApplicationException applicationException = (ApplicationException) ex;
              assertThat(applicationException.getErrorCode())
                  .isEqualTo(ErrorCode.BUSINESS_DUPLICATE_ENTRY);
              assertThat(applicationException.getDetails())
                  .containsEntry("field", "code")
                  .containsEntry("code", "cash-01")
                  .containsEntry("companyCode", "COA")
                  .containsEntry("existingAccountId", 91L);
            });

    verify(accountRepository, never()).save(any(Account.class));
  }

  @Test
  void createAccount_normalizesFieldsAndPersistsParentHierarchy() {
    Account parent = account(5L, company, "AST", AccountType.ASSET);
    when(accountRepository.findByCompanyAndCodeIgnoreCase(company, "AST-CASH-1"))
        .thenReturn(Optional.empty());
    when(accountRepository.findByCompanyAndId(company, 5L)).thenReturn(Optional.of(parent));
    when(accountRepository.save(any(Account.class)))
        .thenAnswer(
            invocation -> {
              Account saved = invocation.getArgument(0);
              ReflectionTestUtils.setField(saved, "id", 44L);
              ReflectionTestUtils.setField(saved, "publicId", UUID.randomUUID());
              return saved;
            });
    when(accountingDtoMapperService.toAccountDto(any(Account.class)))
        .thenReturn(
            new AccountDto(
                44L,
                UUID.randomUUID(),
                "AST-CASH-1",
                "Cash on Hand",
                AccountType.ASSET,
                BigDecimal.ZERO));

    AccountDto created =
        service.createAccount(
            new AccountRequest(" AST-CASH-1 ", "  Cash on Hand  ", AccountType.ASSET, 5L));

    assertThat(created.code()).isEqualTo("AST-CASH-1");
    assertThat(created.name()).isEqualTo("Cash on Hand");

    ArgumentCaptor<Account> accountCaptor = ArgumentCaptor.forClass(Account.class);
    verify(accountRepository).save(accountCaptor.capture());
    Account persisted = accountCaptor.getValue();
    assertThat(persisted.getCode()).isEqualTo("AST-CASH-1");
    assertThat(persisted.getName()).isEqualTo("Cash on Hand");
    assertThat(persisted.getParent()).isSameAs(parent);
    assertThat(persisted.getHierarchyLevel()).isEqualTo(parent.getHierarchyLevel() + 1);
    verify(eventPublisher).publishEvent(any(AccountCacheInvalidatedEvent.class));
  }

  @Test
  void createAccount_rejectsParentTypeMismatch() {
    Account parent = account(6L, company, "LIAB", AccountType.LIABILITY);
    when(accountRepository.findByCompanyAndCodeIgnoreCase(company, "AR-CHILD"))
        .thenReturn(Optional.empty());
    when(accountRepository.findByCompanyAndId(company, 6L)).thenReturn(Optional.of(parent));

    assertThatThrownBy(
            () ->
                service.createAccount(
                    new AccountRequest("AR-CHILD", "Receivable Child", AccountType.ASSET, 6L)))
        .isInstanceOf(ApplicationException.class)
        .satisfies(
            ex -> {
              ApplicationException applicationException = (ApplicationException) ex;
              assertThat(applicationException.getErrorCode())
                  .isEqualTo(ErrorCode.VALIDATION_INVALID_INPUT);
              assertThat(applicationException.getUserMessage())
                  .contains("Child account must have same type as parent");
            });
  }

  @Test
  void createAccount_mapsUniqueConstraintConflictsToBusinessDuplicateEntry() {
    when(accountRepository.findByCompanyAndCodeIgnoreCase(company, "CASH-RACE"))
        .thenReturn(Optional.empty());
    when(accountRepository.save(any(Account.class)))
        .thenThrow(
            new DataIntegrityViolationException(
                "duplicate key value violates unique constraint \"accounts_company_id_code_key\""));

    assertThatThrownBy(
            () ->
                service.createAccount(
                    new AccountRequest("CASH-RACE", "Cash Race", AccountType.ASSET)))
        .isInstanceOf(ApplicationException.class)
        .satisfies(
            ex -> {
              ApplicationException applicationException = (ApplicationException) ex;
              assertThat(applicationException.getErrorCode())
                  .isEqualTo(ErrorCode.BUSINESS_DUPLICATE_ENTRY);
              assertThat(applicationException.getDetails())
                  .containsEntry("field", "code")
                  .containsEntry("code", "CASH-RACE")
                  .containsEntry("companyCode", "COA");
            });
  }

  @Test
  void createAccount_mapsCaseInsensitiveUniqueIndexConflictsToBusinessDuplicateEntry() {
    when(accountRepository.findByCompanyAndCodeIgnoreCase(company, "cash-race-ci"))
        .thenReturn(Optional.empty());
    when(accountRepository.save(any(Account.class)))
        .thenThrow(
            new DataIntegrityViolationException(
                "duplicate key value violates unique constraint \"uq_accounts_company_code_ci\""));

    assertThatThrownBy(
            () ->
                service.createAccount(
                    new AccountRequest("cash-race-ci", "Cash Race CI", AccountType.ASSET)))
        .isInstanceOf(ApplicationException.class)
        .satisfies(
            ex -> {
              ApplicationException applicationException = (ApplicationException) ex;
              assertThat(applicationException.getErrorCode())
                  .isEqualTo(ErrorCode.BUSINESS_DUPLICATE_ENTRY);
              assertThat(applicationException.getDetails())
                  .containsEntry("field", "code")
                  .containsEntry("code", "cash-race-ci")
                  .containsEntry("companyCode", "COA");
            });
  }

  private Account account(Long id, Company accountCompany, String code, AccountType type) {
    Account account = new Account();
    ReflectionTestUtils.setField(account, "id", id);
    account.setCompany(accountCompany);
    account.setCode(code);
    account.setName(code + " Name");
    account.setType(type);
    return account;
  }
}
