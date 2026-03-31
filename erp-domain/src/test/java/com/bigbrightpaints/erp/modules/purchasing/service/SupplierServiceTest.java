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
import org.junit.jupiter.api.Tag;
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

@Tag("critical")
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
  void listSuppliers_includesBankDetailsWhenSensitiveVisibilityIsEnabled() {
    Supplier supplier = supplierWithEncryptedBankDetails();
    when(supplierRepository.findByCompanyWithPayableAccountOrderByNameAsc(company))
        .thenReturn(List.of(supplier));
    when(supplierLedgerService.currentBalances(List.of(77L)))
        .thenReturn(java.util.Map.of(77L, BigDecimal.ZERO));
    when(cryptoService.isEncrypted("enc-name")).thenReturn(true);
    when(cryptoService.isEncrypted("enc-number")).thenReturn(true);
    when(cryptoService.isEncrypted("enc-ifsc")).thenReturn(true);
    when(cryptoService.isEncrypted("enc-branch")).thenReturn(true);
    when(cryptoService.decrypt("enc-name")).thenReturn("Primary Supplier");
    when(cryptoService.decrypt("enc-number")).thenReturn("1234567890");
    when(cryptoService.decrypt("enc-ifsc")).thenReturn("HDFC0001234");
    when(cryptoService.decrypt("enc-branch")).thenReturn("Mumbai");

    List<SupplierResponse> response = supplierService.listSuppliers(true);

    assertThat(response).hasSize(1);
    SupplierResponse supplierResponse = response.getFirst();
    assertThat(supplierResponse.bankAccountName()).isEqualTo("Primary Supplier");
    assertThat(supplierResponse.bankAccountNumber()).isEqualTo("1234567890");
    assertThat(supplierResponse.bankIfsc()).isEqualTo("HDFC0001234");
    assertThat(supplierResponse.bankBranch()).isEqualTo("Mumbai");
    verify(cryptoService, times(4)).decrypt(anyString());
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

  @Test
  void createSupplier_returnsSensitiveBankDetailsWhenProvided() {
    when(cryptoService.encrypt("Primary Supplier")).thenReturn("enc-name");
    when(cryptoService.encrypt("1234567890")).thenReturn("enc-number");
    when(cryptoService.encrypt("HDFC0001234")).thenReturn("enc-ifsc");
    when(cryptoService.encrypt("Mumbai")).thenReturn("enc-branch");
    stubDecryptBankDetails();

    SupplierResponse response =
        supplierService.createSupplier(
            new SupplierRequest(
                "Skeina",
                "SKEINA",
                "skeina@example.com",
                "9999999999",
                "Main Street",
                BigDecimal.ZERO,
                null,
                null,
                null,
                null,
                "Primary Supplier",
                "1234567890",
                "HDFC0001234",
                "Mumbai"));

    assertThat(response.bankAccountName()).isEqualTo("Primary Supplier");
    assertThat(response.bankAccountNumber()).isEqualTo("1234567890");
    assertThat(response.bankIfsc()).isEqualTo("HDFC0001234");
    assertThat(response.bankBranch()).isEqualTo("Mumbai");
  }

  @Test
  void updateSupplier_returnsSensitiveBankDetailsWhenPrivilegedFlowReadsBackSupplier() {
    Supplier supplier = supplierWithEncryptedBankDetails();
    when(companyEntityLookup.requireSupplier(company, 77L)).thenReturn(supplier);
    when(cryptoService.encrypt("Updated Supplier")).thenReturn("enc-name");
    when(cryptoService.encrypt("9999999999")).thenReturn("enc-number");
    when(cryptoService.encrypt("ICIC0009999")).thenReturn("enc-ifsc");
    when(cryptoService.encrypt("Pune")).thenReturn("enc-branch");
    when(cryptoService.isEncrypted("enc-name")).thenReturn(true);
    when(cryptoService.isEncrypted("enc-number")).thenReturn(true);
    when(cryptoService.isEncrypted("enc-ifsc")).thenReturn(true);
    when(cryptoService.isEncrypted("enc-branch")).thenReturn(true);
    when(cryptoService.decrypt("enc-name")).thenReturn("Updated Supplier");
    when(cryptoService.decrypt("enc-number")).thenReturn("9999999999");
    when(cryptoService.decrypt("enc-ifsc")).thenReturn("ICIC0009999");
    when(cryptoService.decrypt("enc-branch")).thenReturn("Pune");

    SupplierResponse response =
        supplierService.updateSupplier(
            77L,
            new SupplierRequest(
                "Skeina Updated",
                "SKEINA",
                "skeina@example.com",
                "9999999999",
                "Main Street",
                BigDecimal.ZERO,
                null,
                null,
                null,
                null,
                "Updated Supplier",
                "9999999999",
                "ICIC0009999",
                "Pune"));

    assertThat(response.bankAccountName()).isEqualTo("Updated Supplier");
    assertThat(response.bankAccountNumber()).isEqualTo("9999999999");
    assertThat(response.bankIfsc()).isEqualTo("ICIC0009999");
    assertThat(response.bankBranch()).isEqualTo("Pune");
  }

  @Test
  void statusTransitions_keepSensitiveBankDetailsVisibleForPrivilegedFlows() {
    Supplier supplier = supplierWithEncryptedBankDetails();
    when(companyEntityLookup.requireSupplier(company, 77L)).thenReturn(supplier);
    stubDecryptBankDetails();

    supplier.setStatus(SupplierStatus.PENDING);
    SupplierResponse approved = supplierService.approveSupplier(77L);
    assertThat(approved.bankAccountName()).isEqualTo("Primary Supplier");

    supplier.setStatus(SupplierStatus.SUSPENDED);
    SupplierResponse activated = supplierService.activateSupplier(77L);
    assertThat(activated.bankAccountNumber()).isEqualTo("1234567890");

    supplier.setStatus(SupplierStatus.ACTIVE);
    SupplierResponse suspended = supplierService.suspendSupplier(77L);
    assertThat(suspended.bankIfsc()).isEqualTo("HDFC0001234");
    assertThat(suspended.bankBranch()).isEqualTo("Mumbai");
  }

  @Test
  void getSupplier_redactsBankDetailsWhenSensitiveVisibilityIsDisabled() {
    Supplier supplier = supplierWithEncryptedBankDetails();
    when(companyEntityLookup.requireSupplier(company, 77L)).thenReturn(supplier);
    when(supplierLedgerService.currentBalance(77L)).thenReturn(BigDecimal.ZERO);

    SupplierResponse response = supplierService.getSupplier(77L, false);

    assertThat(response.bankAccountName()).isNull();
    assertThat(response.bankAccountNumber()).isNull();
    assertThat(response.bankIfsc()).isNull();
    assertThat(response.bankBranch()).isNull();
    verify(cryptoService, never()).decrypt(anyString());
  }

  @Test
  void listSuppliers_defaultsMissingBalanceAndKeepsPlaintextBankValuesForPrivilegedReads() {
    Supplier supplier = supplierWithEncryptedBankDetails();
    supplier.setBankAccountNameEncrypted("Primary Supplier");
    supplier.setBankAccountNumberEncrypted("1234567890");
    supplier.setBankIfscEncrypted("HDFC0001234");
    supplier.setBankBranchEncrypted("Mumbai");
    when(supplierRepository.findByCompanyWithPayableAccountOrderByNameAsc(company))
        .thenReturn(List.of(supplier));
    when(supplierLedgerService.currentBalances(List.of(77L))).thenReturn(java.util.Map.of());
    when(cryptoService.isEncrypted(anyString())).thenReturn(false);

    List<SupplierResponse> response = supplierService.listSuppliers(true);

    assertThat(response).hasSize(1);
    SupplierResponse supplierResponse = response.getFirst();
    assertThat(supplierResponse.balance()).isEqualByComparingTo(BigDecimal.ZERO);
    assertThat(supplierResponse.bankAccountName()).isEqualTo("Primary Supplier");
    assertThat(supplierResponse.bankAccountNumber()).isEqualTo("1234567890");
    assertThat(supplierResponse.bankIfsc()).isEqualTo("HDFC0001234");
    assertThat(supplierResponse.bankBranch()).isEqualTo("Mumbai");
    verify(cryptoService, never()).decrypt(anyString());
  }

  @Test
  void createSupplier_defaultsNullCreditLimitAndBlankBankDetails() {
    SupplierResponse response =
        supplierService.createSupplier(
            new SupplierRequest(
                "Skeina",
                "SKEINA",
                "skeina@example.com",
                "9999999999",
                "Main Street",
                null,
                null,
                null,
                null,
                null,
                "   ",
                null,
                " ",
                ""));

    ArgumentCaptor<Supplier> supplierCaptor = ArgumentCaptor.forClass(Supplier.class);
    verify(supplierRepository).save(supplierCaptor.capture());
    Supplier savedSupplier = supplierCaptor.getValue();
    assertThat(savedSupplier.getCreditLimit()).isEqualByComparingTo(BigDecimal.ZERO);
    assertThat(savedSupplier.getBankAccountNameEncrypted()).isNull();
    assertThat(savedSupplier.getBankAccountNumberEncrypted()).isNull();
    assertThat(savedSupplier.getBankIfscEncrypted()).isNull();
    assertThat(savedSupplier.getBankBranchEncrypted()).isNull();
    assertThat(response.bankAccountName()).isNull();
    assertThat(response.bankAccountNumber()).isNull();
    assertThat(response.bankIfsc()).isNull();
    assertThat(response.bankBranch()).isNull();
  }

  @Test
  void updateSupplier_defaultsMissingCreditLimitAndKeepsExistingCodeWhenRequestCodeIsBlank() {
    Supplier supplier = supplierWithEncryptedBankDetails();
    supplier.setCode("LEGACY");
    when(companyEntityLookup.requireSupplier(company, 77L)).thenReturn(supplier);

    SupplierResponse response =
        supplierService.updateSupplier(
            77L,
            new SupplierRequest(
                "Skeina Updated",
                "   ",
                "skeina@example.com",
                "9999999999",
                "Main Street",
                null));

    assertThat(supplier.getCode()).isEqualTo("LEGACY");
    assertThat(supplier.getCreditLimit()).isEqualByComparingTo(BigDecimal.ZERO);
    assertThat(response.payableAccountCode()).isEqualTo("AP-SKEINA");
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

  private void stubDecryptBankDetails() {
    when(cryptoService.isEncrypted("enc-name")).thenReturn(true);
    when(cryptoService.isEncrypted("enc-number")).thenReturn(true);
    when(cryptoService.isEncrypted("enc-ifsc")).thenReturn(true);
    when(cryptoService.isEncrypted("enc-branch")).thenReturn(true);
    when(cryptoService.decrypt("enc-name")).thenReturn("Primary Supplier");
    when(cryptoService.decrypt("enc-number")).thenReturn("1234567890");
    when(cryptoService.decrypt("enc-ifsc")).thenReturn("HDFC0001234");
    when(cryptoService.decrypt("enc-branch")).thenReturn("Mumbai");
  }
}
