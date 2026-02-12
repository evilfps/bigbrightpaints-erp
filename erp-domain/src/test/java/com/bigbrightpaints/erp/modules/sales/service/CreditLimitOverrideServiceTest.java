package com.bigbrightpaints.erp.modules.sales.service;

import com.bigbrightpaints.erp.modules.accounting.service.DealerLedgerService;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.inventory.domain.PackagingSlipRepository;
import com.bigbrightpaints.erp.modules.sales.domain.CreditLimitOverrideRequest;
import com.bigbrightpaints.erp.modules.sales.domain.CreditLimitOverrideRequestRepository;
import com.bigbrightpaints.erp.modules.sales.domain.DealerRepository;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrderRepository;
import com.bigbrightpaints.erp.modules.sales.dto.CreditLimitOverrideRequestDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CreditLimitOverrideServiceTest {

    @Mock
    private CompanyContextService companyContextService;
    @Mock
    private CreditLimitOverrideRequestRepository creditLimitOverrideRequestRepository;
    @Mock
    private DealerRepository dealerRepository;
    @Mock
    private SalesOrderRepository salesOrderRepository;
    @Mock
    private PackagingSlipRepository packagingSlipRepository;
    @Mock
    private DealerLedgerService dealerLedgerService;

    private CreditLimitOverrideService service;
    private Company company;

    @BeforeEach
    void setUp() {
        service = new CreditLimitOverrideService(
                companyContextService,
                creditLimitOverrideRequestRepository,
                dealerRepository,
                salesOrderRepository,
                packagingSlipRepository,
                dealerLedgerService
        );
        company = new Company();
        company.setTimezone("UTC");
        when(companyContextService.requireCurrentCompany()).thenReturn(company);
    }

    @Test
    void listRequests_trimsAndNormalizesStatusFilter() {
        CreditLimitOverrideRequest request = new CreditLimitOverrideRequest();
        request.setCompany(company);
        request.setStatus("PENDING");

        when(creditLimitOverrideRequestRepository.findByCompanyAndStatusOrderByCreatedAtDesc(company, "PENDING"))
                .thenReturn(List.of(request));

        List<CreditLimitOverrideRequestDto> result = service.listRequests(" pending ");

        assertThat(result).hasSize(1);
        verify(creditLimitOverrideRequestRepository)
                .findByCompanyAndStatusOrderByCreatedAtDesc(company, "PENDING");
    }

    @Test
    void approveRequest_acceptsTrimmedPendingStatus() {
        CreditLimitOverrideRequest request = new CreditLimitOverrideRequest();
        request.setCompany(company);
        request.setStatus(" pending ");

        when(creditLimitOverrideRequestRepository.findByCompanyAndId(company, 11L))
                .thenReturn(Optional.of(request));

        CreditLimitOverrideRequestDto response = service.approveRequest(11L, null, "admin@bbp.com");

        assertThat(response.status()).isEqualTo("APPROVED");
        assertThat(request.getStatus()).isEqualTo("APPROVED");
    }
}
