package com.bigbrightpaints.erp.modules.purchasing.service;

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
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SupplierServiceTest {

    @Mock
    private SupplierRepository supplierRepository;
    @Mock
    private CompanyContextService companyContextService;
    @Mock
    private AccountRepository accountRepository;
    @Mock
    private SupplierLedgerService supplierLedgerService;
    @Mock
    private CompanyEntityLookup companyEntityLookup;
    @Mock
    private CryptoService cryptoService;

    private SupplierService supplierService;
    private Company company;

    @BeforeEach
    void setUp() {
        supplierService = new SupplierService(
                supplierRepository,
                companyContextService,
                accountRepository,
                supplierLedgerService,
                companyEntityLookup,
                cryptoService
        );

        company = new Company();
        ReflectionTestUtils.setField(company, "id", 1L);
        company.setCode("TEST");

        when(companyContextService.requireCurrentCompany()).thenReturn(company);
        when(supplierRepository.findByCompanyAndCodeIgnoreCase(eq(company), anyString())).thenReturn(Optional.empty());
        when(supplierRepository.save(any(Supplier.class))).thenAnswer(invocation -> {
            Supplier supplier = invocation.getArgument(0);
            if (supplier.getId() == null) {
                ReflectionTestUtils.setField(supplier, "id", 77L);
            }
            return supplier;
        });
        when(accountRepository.findByCompanyAndCodeIgnoreCase(eq(company), anyString())).thenReturn(Optional.empty());
        when(accountRepository.save(any(Account.class))).thenAnswer(invocation -> {
            Account account = invocation.getArgument(0);
            if (account.getId() == null) {
                ReflectionTestUtils.setField(account, "id", 123L);
            }
            return account;
        });
        when(supplierLedgerService.currentBalance(anyLong())).thenReturn(BigDecimal.ZERO);
    }

    @Test
    void createSupplier_linksPayableAccountUnderApControlWhenPresent() {
        Account apControl = new Account();
        apControl.setType(AccountType.LIABILITY);
        apControl.setCode("AP");
        when(accountRepository.findByCompanyAndCodeIgnoreCase(eq(company), eq("AP"))).thenReturn(Optional.of(apControl));

        SupplierRequest request = new SupplierRequest(
                "Skeina",
                "SKEINA",
                "skeina@example.com",
                "9999999999",
                "Main Street",
                BigDecimal.ZERO
        );

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
}
