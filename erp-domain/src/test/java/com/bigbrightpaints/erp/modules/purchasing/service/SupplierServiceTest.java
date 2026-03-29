package com.bigbrightpaints.erp.modules.purchasing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.bigbrightpaints.erp.core.security.CryptoService;
import com.bigbrightpaints.erp.core.util.CompanyEntityLookup;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountType;
import com.bigbrightpaints.erp.modules.accounting.service.SupplierLedgerService;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.purchasing.domain.Supplier;
import com.bigbrightpaints.erp.modules.purchasing.domain.SupplierRepository;
import com.bigbrightpaints.erp.modules.purchasing.domain.SupplierStatus;
import com.bigbrightpaints.erp.modules.purchasing.dto.SupplierRequest;
import com.bigbrightpaints.erp.modules.purchasing.dto.SupplierResponse;

@ExtendWith(MockitoExtension.class)
class SupplierServiceTest {

  @Mock private SupplierRepository supplierRepository;
  @Mock private CompanyContextService companyContextService;
  @Mock private AccountRepository accountRepository;
  @Mock private SupplierLedgerService supplierLedgerService;
  @Mock private CompanyEntityLookup companyEntityLookup;
  @Mock private CryptoService cryptoService;

  private SupplierService supplierService;
  private Company company;

  @BeforeEach
  void setUp() {
    supplierService =
        new SupplierService(
            supplierRepository,
            companyContextService,
            accountRepository,
            supplierLedgerService,
            companyEntityLookup,
            cryptoService);

    company = new Company();
    ReflectionTestUtils.setField(company, "id", 1L);
    company.setCode("TEST");

    when(companyContextService.requireCurrentCompany()).thenReturn(company);
    Mockito.lenient().when(supplierRepository.findByCompanyAndCodeIgnoreCase(eq(company), anyString()))
        .thenReturn(Optional.empty());
    Mockito.lenient().when(supplierRepository.save(any(Supplier.class)))
        .thenAnswer(
            invocation -> {
              Supplier supplier = invocation.getArgument(0);
              if (supplier.getId() == null) {
                ReflectionTestUtils.setField(supplier, "id", 77L);
              }
              return supplier;
            });
    Mockito.lenient().when(accountRepository.findByCompanyAndCodeIgnoreCase(eq(company), anyString()))
        .thenReturn(Optional.empty());
    Mockito.lenient().when(accountRepository.save(any(Account.class)))
        .thenAnswer(
            invocation -> {
              Account account = invocation.getArgument(0);
              if (account.getId() == null) {
                ReflectionTestUtils.setField(account, "id", 123L);
              }
              return account;
            });
    Mockito.lenient().when(supplierLedgerService.currentBalance(anyLong())).thenReturn(BigDecimal.ZERO);
  }

  @Test
  void createSupplier_linksPayableAccountUnderApControlWhenPresent() {
    Account apControl = new Account();
    apControl.setType(AccountType.LIABILITY);
    apControl.setCode("AP");
    when(accountRepository.findByCompanyAndCodeIgnoreCase(eq(company), eq("AP")))
        .thenReturn(Optional.of(apControl));

    SupplierRequest request =
        new SupplierRequest(
            "Skeina", "SKEINA", "skeina@example.com", "9999999999", "Main Street", BigDecimal.ZERO);

    var response = supplierService.createSupplier(request);

    ArgumentCaptor<Account> accountCaptor = ArgumentCaptor.forClass(Account.class);
    verify(accountRepository).save(accountCaptor.capture());
    Account created = accountCaptor.getValue();
    assertThat(created.getCode()).startsWith("AP-SKEINA");
    assertThat(created.getParent()).isSameAs(apControl);

    ArgumentCaptor<Supplier> supplierCaptor = ArgumentCaptor.forClass(Supplier.class);
    verify(supplierRepository, times(1)).save(supplierCaptor.capture());
    Supplier savedSupplier = supplierCaptor.getValue();
    assertThat(savedSupplier.getPayableAccount()).isNotNull();
    assertThat(savedSupplier.getStatusEnum()).isEqualTo(SupplierStatus.PENDING);
    assertThat(response.payableAccountId()).isEqualTo(123L);
    assertThat(response.payableAccountCode()).startsWith("AP-SKEINA");
  }

  @Test
  void listSuppliers_redactsBankDetailsWhenSensitiveVisibilityIsDisabled() {
    Supplier supplier = supplierWithEncryptedBankDetails();
    when(supplierRepository.findByCompanyWithPayableAccountOrderByNameAsc(company))
        .thenReturn(List.of(supplier));
    when(supplierLedgerService.currentBalances(List.of(77L)))
        .thenReturn(java.util.Map.of(77L, BigDecimal.ZERO));

    List<SupplierResponse> response = supplierService.listSuppliers(false);

    assertThat(response).hasSize(1);
    SupplierResponse supplierResponse = response.getFirst();
    assertThat(supplierResponse.bankAccountName()).isNull();
    assertThat(supplierResponse.bankAccountNumber()).isNull();
    assertThat(supplierResponse.bankIfsc()).isNull();
    assertThat(supplierResponse.bankBranch()).isNull();
    verify(cryptoService, never()).decrypt(anyString());
  }

  @Test
  void getSupplier_includesBankDetailsWhenSensitiveVisibilityIsEnabled() {
    Supplier supplier = supplierWithEncryptedBankDetails();
    when(companyEntityLookup.requireSupplier(company, 77L)).thenReturn(supplier);
    when(cryptoService.isEncrypted("enc-name")).thenReturn(true);
    when(cryptoService.isEncrypted("enc-number")).thenReturn(true);
    when(cryptoService.isEncrypted("enc-ifsc")).thenReturn(true);
    when(cryptoService.isEncrypted("enc-branch")).thenReturn(true);
    when(cryptoService.decrypt("enc-name")).thenReturn("Primary Supplier");
    when(cryptoService.decrypt("enc-number")).thenReturn("1234567890");
    when(cryptoService.decrypt("enc-ifsc")).thenReturn("HDFC0001234");
    when(cryptoService.decrypt("enc-branch")).thenReturn("Mumbai");

    SupplierResponse response = supplierService.getSupplier(77L, true);

    assertThat(response.bankAccountName()).isEqualTo("Primary Supplier");
    assertThat(response.bankAccountNumber()).isEqualTo("1234567890");
    assertThat(response.bankIfsc()).isEqualTo("HDFC0001234");
    assertThat(response.bankBranch()).isEqualTo("Mumbai");
  }

  private Supplier supplierWithEncryptedBankDetails() {
    Supplier supplier = new Supplier();
    ReflectionTestUtils.setField(supplier, "id", 77L);
    supplier.setCompany(company);
    supplier.setCode("SKEINA");
    supplier.setName("Skeina");
    supplier.setStatus(SupplierStatus.ACTIVE);
    supplier.setBankAccountNameEncrypted("enc-name");
    supplier.setBankAccountNumberEncrypted("enc-number");
    supplier.setBankIfscEncrypted("enc-ifsc");
    supplier.setBankBranchEncrypted("enc-branch");
    Account payableAccount = new Account();
    ReflectionTestUtils.setField(payableAccount, "id", 123L);
    payableAccount.setCode("AP-SKEINA");
    supplier.setPayableAccount(payableAccount);
    return supplier;
  }
}
