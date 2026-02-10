package com.bigbrightpaints.erp.modules.purchasing.service;

import com.bigbrightpaints.erp.core.util.CompanyEntityLookup;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountType;
import com.bigbrightpaints.erp.modules.accounting.service.SupplierLedgerService;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.purchasing.domain.Supplier;
import com.bigbrightpaints.erp.modules.purchasing.domain.SupplierRepository;
import com.bigbrightpaints.erp.modules.purchasing.dto.SupplierRequest;
import com.bigbrightpaints.erp.modules.purchasing.dto.SupplierResponse;
import jakarta.transaction.Transactional;
import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class SupplierService {

    private final SupplierRepository supplierRepository;
    private final CompanyContextService companyContextService;
    private final AccountRepository accountRepository;
    private final SupplierLedgerService supplierLedgerService;
    private final CompanyEntityLookup companyEntityLookup;

    public SupplierService(SupplierRepository supplierRepository,
                           CompanyContextService companyContextService,
                           AccountRepository accountRepository,
                           SupplierLedgerService supplierLedgerService,
                            CompanyEntityLookup companyEntityLookup) {
        this.supplierRepository = supplierRepository;
        this.companyContextService = companyContextService;
        this.accountRepository = accountRepository;
        this.supplierLedgerService = supplierLedgerService;
        this.companyEntityLookup = companyEntityLookup;
    }

    @Transactional
    public List<SupplierResponse> listSuppliers() {
        Company company = companyContextService.requireCurrentCompany();
        List<Supplier> suppliers = supplierRepository.findByCompanyWithPayableAccountOrderByNameAsc(company);
        // Always use ledger as source of truth - never fall back to stale denormalized field
        Map<Long, BigDecimal> balances = supplierLedgerService.currentBalances(
                suppliers.stream().map(Supplier::getId).toList());
        return suppliers.stream()
                .map(supplier -> toResponse(supplier,
                        balances.getOrDefault(supplier.getId(), BigDecimal.ZERO)))
                .toList();
    }

    @Transactional
    public SupplierResponse getSupplier(Long id) {
        Company company = companyContextService.requireCurrentCompany();
        Supplier supplier = requireSupplier(company, id);
        // Use ledger balance as source of truth instead of denormalized field
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
        supplier.setCreditLimit(request.creditLimit() != null ? request.creditLimit() : BigDecimal.ZERO);
        supplier = supplierRepository.save(supplier);

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
            supplier.setCode(resolveSupplierCode(request.code(), request.name(), company, supplier.getId()));
        }
        supplier.setEmail(normalize(request.contactEmail()));
        supplier.setPhone(normalize(request.contactPhone()));
        supplier.setAddress(normalize(request.address()));
        supplier.setCreditLimit(request.creditLimit() != null ? request.creditLimit() : BigDecimal.ZERO);
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

    private Optional<Account> resolveControlAccount(Company company, String code, AccountType expectedType) {
        return accountRepository.findByCompanyAndCodeIgnoreCase(company, code)
                .filter(account -> account.getType() == expectedType);
    }

    private SupplierResponse toResponse(Supplier supplier) {
        BigDecimal balance = supplier.getId() != null
                ? supplierLedgerService.currentBalance(supplier.getId())
                : BigDecimal.ZERO;
        return toResponse(supplier, balance);
    }

    private SupplierResponse toResponse(Supplier supplier, BigDecimal outstandingBalance) {
        Account payableAccount = supplier.getPayableAccount();
        Long accountId = payableAccount != null ? payableAccount.getId() : null;
        String accountCode = payableAccount != null ? payableAccount.getCode() : null;
        return new SupplierResponse(
                supplier.getId(),
                supplier.getPublicId(),
                supplier.getCode(),
                supplier.getName(),
                supplier.getStatus(),
                supplier.getEmail(),
                supplier.getPhone(),
                supplier.getAddress(),
                supplier.getCreditLimit(),
                outstandingBalance,
                accountId,
                accountCode
        );
    }

    private String normalize(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private String resolveSupplierCode(String requestedCode, String name, Company company) {
        return resolveSupplierCode(requestedCode, name, company, null);
    }

    private String resolveSupplierCode(String requestedCode, String name, Company company, Long currentId) {
        String base = StringUtils.hasText(requestedCode)
                ? requestedCode.trim()
                : generateCodeFromName(name);
        String code = base;
        int attempt = 1;
        while (supplierRepository.findByCompanyAndCodeIgnoreCase(company, code)
                .filter(existing -> currentId == null || !existing.getId().equals(currentId))
                .isPresent()) {
            code = base + "-" + attempt++;
        }
        return code;
    }

    private String generateCodeFromName(String name) {
        String normalized = Normalizer.normalize(name, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .replaceAll("[^A-Za-z0-9]", "")
                .toUpperCase(Locale.ROOT);
        return normalized.isEmpty() ? "SUPPLIER" : normalized;
    }
}
