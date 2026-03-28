package com.bigbrightpaints.erp.modules.purchasing.service;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.bigbrightpaints.erp.core.security.CryptoService;
import com.bigbrightpaints.erp.core.util.CompanyEntityLookup;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountType;
import com.bigbrightpaints.erp.modules.accounting.domain.GstRegistrationType;
import com.bigbrightpaints.erp.modules.accounting.service.SupplierLedgerService;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.purchasing.domain.Supplier;
import com.bigbrightpaints.erp.modules.purchasing.domain.SupplierPaymentTerms;
import com.bigbrightpaints.erp.modules.purchasing.domain.SupplierRepository;
import com.bigbrightpaints.erp.modules.purchasing.domain.SupplierStatus;
import com.bigbrightpaints.erp.modules.purchasing.dto.SupplierRequest;
import com.bigbrightpaints.erp.modules.purchasing.dto.SupplierResponse;

import jakarta.transaction.Transactional;

@Service
public class SupplierService {

  private static final Pattern GSTIN_PATTERN = Pattern.compile("^[0-9]{2}[A-Z0-9]{13}$");

  private final SupplierRepository supplierRepository;
  private final CompanyContextService companyContextService;
  private final AccountRepository accountRepository;
  private final SupplierLedgerService supplierLedgerService;
  private final CompanyEntityLookup companyEntityLookup;
  private final CryptoService cryptoService;

  public SupplierService(
      SupplierRepository supplierRepository,
      CompanyContextService companyContextService,
      AccountRepository accountRepository,
      SupplierLedgerService supplierLedgerService,
      CompanyEntityLookup companyEntityLookup,
      CryptoService cryptoService) {
    this.supplierRepository = supplierRepository;
    this.companyContextService = companyContextService;
    this.accountRepository = accountRepository;
    this.supplierLedgerService = supplierLedgerService;
    this.companyEntityLookup = companyEntityLookup;
    this.cryptoService = cryptoService;
  }

  @Transactional
  public List<SupplierResponse> listSuppliers() {
    Company company = companyContextService.requireCurrentCompany();
    List<Supplier> suppliers =
        supplierRepository.findByCompanyWithPayableAccountOrderByNameAsc(company);
    Map<Long, BigDecimal> balances =
        supplierLedgerService.currentBalances(suppliers.stream().map(Supplier::getId).toList());
    return suppliers.stream()
        .map(
            supplier ->
                toResponse(supplier, balances.getOrDefault(supplier.getId(), BigDecimal.ZERO)))
        .toList();
  }

  @Transactional
  public SupplierResponse getSupplier(Long id) {
    Company company = companyContextService.requireCurrentCompany();
    Supplier supplier = requireSupplier(company, id);
    BigDecimal ledgerBalance = supplierLedgerService.currentBalance(id);
    return toResponse(supplier, ledgerBalance);
  }

  @Transactional
  public SupplierResponse createSupplier(SupplierRequest request) {
    Company company = companyContextService.requireCurrentCompany();
    Supplier supplier = new Supplier();
    supplier.setCompany(company);
    supplier.setName(request.name().trim());
    supplier.setCode(resolveSupplierCode(request.code(), request.name(), company));
    supplier.setEmail(normalize(request.contactEmail()));
    supplier.setPhone(normalize(request.contactPhone()));
    supplier.setAddress(normalize(request.address()));
    supplier.setGstNumber(normalizeGstNumber(request.gstNumber()));
    supplier.setStateCode(normalizeStateCode(request.stateCode()));
    supplier.setGstRegistrationType(resolveRegistrationType(request.gstRegistrationType()));
    supplier.setPaymentTerms(resolvePaymentTerms(request.paymentTerms()));
    supplier.setStatus(SupplierStatus.PENDING);
    supplier.setCreditLimit(
        request.creditLimit() != null ? request.creditLimit() : BigDecimal.ZERO);
    applyBankDetails(supplier, request);
    Account payableAccount = createPayableAccount(company, supplier);
    supplier.setPayableAccount(payableAccount);
    supplier = supplierRepository.save(supplier);
    return toResponse(supplier, supplierLedgerService.currentBalance(supplier.getId()));
  }

  @Transactional
  public SupplierResponse updateSupplier(Long id, SupplierRequest request) {
    Company company = companyContextService.requireCurrentCompany();
    Supplier supplier = requireSupplier(company, id);
    supplier.setName(request.name().trim());
    if (StringUtils.hasText(request.code())) {
      supplier.setCode(
          resolveSupplierCode(request.code(), request.name(), company, supplier.getId()));
    }
    supplier.setEmail(normalize(request.contactEmail()));
    supplier.setPhone(normalize(request.contactPhone()));
    supplier.setAddress(normalize(request.address()));
    supplier.setGstNumber(normalizeGstNumber(request.gstNumber()));
    supplier.setStateCode(normalizeStateCode(request.stateCode()));
    supplier.setGstRegistrationType(resolveRegistrationType(request.gstRegistrationType()));
    supplier.setPaymentTerms(resolvePaymentTerms(request.paymentTerms()));
    supplier.setCreditLimit(
        request.creditLimit() != null ? request.creditLimit() : BigDecimal.ZERO);
    applyBankDetails(supplier, request);
    return toResponse(supplier, supplierLedgerService.currentBalance(supplier.getId()));
  }

  @Transactional
  public SupplierResponse approveSupplier(Long id) {
    Company company = companyContextService.requireCurrentCompany();
    Supplier supplier = requireSupplier(company, id);
    if (supplier.getStatusEnum() != SupplierStatus.PENDING) {
      throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidState(
          "Supplier can only be approved from PENDING state");
    }
    supplier.setStatus(SupplierStatus.APPROVED);
    return toResponse(supplier, supplierLedgerService.currentBalance(supplier.getId()));
  }

  @Transactional
  public SupplierResponse activateSupplier(Long id) {
    Company company = companyContextService.requireCurrentCompany();
    Supplier supplier = requireSupplier(company, id);
    if (supplier.getStatusEnum() != SupplierStatus.APPROVED
        && supplier.getStatusEnum() != SupplierStatus.SUSPENDED) {
      throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidState(
          "Supplier can only be activated from APPROVED or SUSPENDED state");
    }
    supplier.setStatus(SupplierStatus.ACTIVE);
    return toResponse(supplier, supplierLedgerService.currentBalance(supplier.getId()));
  }

  @Transactional
  public SupplierResponse suspendSupplier(Long id) {
    Company company = companyContextService.requireCurrentCompany();
    Supplier supplier = requireSupplier(company, id);
    if (supplier.getStatusEnum() != SupplierStatus.ACTIVE) {
      throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidState(
          "Supplier can only be suspended from ACTIVE state");
    }
    supplier.setStatus(SupplierStatus.SUSPENDED);
    return toResponse(supplier, supplierLedgerService.currentBalance(supplier.getId()));
  }

  private Supplier requireSupplier(Company company, Long id) {
    return companyEntityLookup.requireSupplier(company, id);
  }

  private Account createPayableAccount(Company company, Supplier supplier) {
    String baseCode = "AP-" + supplier.getCode();
    String code = baseCode;
    int attempt = 1;
    while (accountRepository.findByCompanyAndCodeIgnoreCase(company, code).isPresent()) {
      code = baseCode + "-" + attempt++;
    }
    Account account = new Account();
    account.setCompany(company);
    account.setCode(code);
    account.setName(supplier.getName() + " Payable");
    account.setType(AccountType.LIABILITY);
    resolveControlAccount(company, "AP", AccountType.LIABILITY).ifPresent(account::setParent);
    return accountRepository.save(account);
  }

  private Optional<Account> resolveControlAccount(
      Company company, String code, AccountType expectedType) {
    return accountRepository
        .findByCompanyAndCodeIgnoreCase(company, code)
        .filter(account -> account.getType() == expectedType);
  }

  private SupplierResponse toResponse(Supplier supplier, BigDecimal balance) {
    Account payableAccount = supplier.getPayableAccount();
    Long accountId = payableAccount != null ? payableAccount.getId() : null;
    String accountCode = payableAccount != null ? payableAccount.getCode() : null;
    return new SupplierResponse(
        supplier.getId(),
        supplier.getPublicId(),
        supplier.getCode(),
        supplier.getName(),
        supplier.getStatusEnum(),
        supplier.getEmail(),
        supplier.getPhone(),
        supplier.getAddress(),
        supplier.getCreditLimit(),
        balance,
        accountId,
        accountCode,
        supplier.getGstNumber(),
        supplier.getStateCode(),
        supplier.getGstRegistrationType(),
        supplier.getPaymentTerms(),
        decryptIfPresent(supplier.getBankAccountNameEncrypted()),
        decryptIfPresent(supplier.getBankAccountNumberEncrypted()),
        decryptIfPresent(supplier.getBankIfscEncrypted()),
        decryptIfPresent(supplier.getBankBranchEncrypted()));
  }

  private void applyBankDetails(Supplier supplier, SupplierRequest request) {
    supplier.setBankAccountNameEncrypted(encryptOrNull(request.bankAccountName()));
    supplier.setBankAccountNumberEncrypted(encryptOrNull(request.bankAccountNumber()));
    supplier.setBankIfscEncrypted(encryptOrNull(request.bankIfsc()));
    supplier.setBankBranchEncrypted(encryptOrNull(request.bankBranch()));
  }

  private String encryptOrNull(String value) {
    String normalized = normalize(value);
    if (!StringUtils.hasText(normalized)) {
      return null;
    }
    return cryptoService.encrypt(normalized);
  }

  private String decryptIfPresent(String value) {
    if (!StringUtils.hasText(value)) {
      return null;
    }
    if (!cryptoService.isEncrypted(value)) {
      return value;
    }
    return cryptoService.decrypt(value);
  }

  private String normalize(String value) {
    return StringUtils.hasText(value) ? value.trim() : null;
  }

  private String normalizeGstNumber(String gstNumber) {
    if (!StringUtils.hasText(gstNumber)) {
      return null;
    }
    String normalized = gstNumber.trim().toUpperCase(Locale.ROOT);
    if (!GSTIN_PATTERN.matcher(normalized).matches()) {
      throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
          "GST number must be a valid 15-character GSTIN");
    }
    return normalized;
  }

  private String normalizeStateCode(String stateCode) {
    if (!StringUtils.hasText(stateCode)) {
      return null;
    }
    String normalized = stateCode.trim().toUpperCase(Locale.ROOT);
    if (normalized.length() != 2) {
      throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
          "State code must be exactly 2 characters");
    }
    return normalized;
  }

  private GstRegistrationType resolveRegistrationType(GstRegistrationType registrationType) {
    return registrationType == null ? GstRegistrationType.UNREGISTERED : registrationType;
  }

  private SupplierPaymentTerms resolvePaymentTerms(SupplierPaymentTerms paymentTerms) {
    return paymentTerms == null ? SupplierPaymentTerms.NET_30 : paymentTerms;
  }

  private String resolveSupplierCode(String requestedCode, String name, Company company) {
    return resolveSupplierCode(requestedCode, name, company, null);
  }

  private String resolveSupplierCode(
      String requestedCode, String name, Company company, Long currentId) {
    String base =
        StringUtils.hasText(requestedCode) ? requestedCode.trim() : generateCodeFromName(name);
    String code = base;
    int attempt = 1;
    while (supplierRepository
        .findByCompanyAndCodeIgnoreCase(company, code)
        .filter(existing -> currentId == null || !existing.getId().equals(currentId))
        .isPresent()) {
      code = base + "-" + attempt++;
    }
    return code;
  }

  private String generateCodeFromName(String name) {
    String normalized =
        Normalizer.normalize(name, Normalizer.Form.NFD)
            .replaceAll("\\p{M}", "")
            .replaceAll("[^A-Za-z0-9]", "")
            .toUpperCase(Locale.ROOT);
    return normalized.isEmpty() ? "SUPPLIER" : normalized;
  }
}
