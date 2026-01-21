package com.bigbrightpaints.erp.core.health;

import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialRepository;
import com.bigbrightpaints.erp.modules.production.domain.ProductionProductRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConfigurationHealthServiceTest {

    @Mock
    private CompanyRepository companyRepository;
    @Mock
    private FinishedGoodRepository finishedGoodRepository;
    @Mock
    private RawMaterialRepository rawMaterialRepository;
    @Mock
    private ProductionProductRepository productionProductRepository;

    private ConfigurationHealthService configurationHealthService;

    @BeforeEach
    void setup() {
        configurationHealthService = new ConfigurationHealthService(
                companyRepository,
                finishedGoodRepository,
                rawMaterialRepository,
                productionProductRepository
        );
    }

    @Test
    void evaluate_reportsMissingBaseCurrencyAndDefaultAccounts() {
        Company company = new Company();
        company.setCode("CFG-TEST");
        company.setName("Config Test Co");
        company.setTimezone("UTC");
        company.setGstInputTaxAccountId(1L);
        company.setGstOutputTaxAccountId(2L);
        ReflectionTestUtils.setField(company, "baseCurrency", " ");

        when(companyRepository.findAll()).thenReturn(List.of(company));
        when(finishedGoodRepository.findByCompanyOrderByProductCodeAsc(company)).thenReturn(List.of());
        when(rawMaterialRepository.findByCompanyOrderByNameAsc(company)).thenReturn(List.of());
        when(productionProductRepository.findByCompanyOrderByProductNameAsc(company)).thenReturn(List.of());

        ConfigurationHealthService.ConfigurationHealthReport report = configurationHealthService.evaluate();

        assertThat(report.issues())
                .anySatisfy(issue -> assertThat(issue.domain()).isEqualTo("BASE_CURRENCY"));
        assertThat(report.issues())
                .anySatisfy(issue -> assertThat(issue.domain()).isEqualTo("DEFAULT_ACCOUNTS"));
    }
}
