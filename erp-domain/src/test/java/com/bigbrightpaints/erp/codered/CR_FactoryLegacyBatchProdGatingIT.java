package com.bigbrightpaints.erp.codered;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.security.CompanyContextHolder;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.modules.factory.domain.ProductionBatchRepository;
import com.bigbrightpaints.erp.modules.factory.dto.ProductionBatchRequest;
import com.bigbrightpaints.erp.modules.factory.service.FactoryService;
import com.bigbrightpaints.erp.test.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

@ActiveProfiles(value = {"test", "prod"}, inheritProfiles = false)
@TestPropertySource(properties = {
        "jwt.secret=2f4f8a6c9b1d4e7f8a2c5d9e3f6b7c1a4d8e2f5a9c3b6d7e",
        "spring.mail.host=localhost",
        "spring.mail.username=test-smtp-user",
        "spring.mail.password=test-smtp-password",
        "ERP_LICENSE_KEY=test-license-key",
        "ERP_DISPATCH_DEBIT_ACCOUNT_ID=1",
        "ERP_DISPATCH_CREDIT_ACCOUNT_ID=2",
        "management.endpoint.health.validate-group-membership=false",
        "erp.environment.validation.enabled=false",
        "erp.factory.legacy-batch.enabled=false"
})
class CR_FactoryLegacyBatchProdGatingIT extends AbstractIntegrationTest {

    private static final String COMPANY_CODE = "CR-FACTORY-LEGACY";

    @Autowired
    private FactoryService factoryService;
    @Autowired
    private CompanyRepository companyRepository;
    @Autowired
    private ProductionBatchRepository productionBatchRepository;

    private Company company;

    @BeforeEach
    void setUp() {
        company = companyRepository.findByCodeIgnoreCase(COMPANY_CODE)
                .orElseGet(() -> {
                    Company created = new Company();
                    created.setCode(COMPANY_CODE);
                    created.setName("CR Factory Legacy Gate");
                    created.setTimezone("UTC");
                    return companyRepository.save(created);
                });
        CompanyContextHolder.setCompanyId(COMPANY_CODE);
    }

    @AfterEach
    void tearDown() {
        CompanyContextHolder.clear();
    }

    @Test
    void prodBlocksLegacyBatchLogging() {
        ProductionBatchRequest request = new ProductionBatchRequest(
                "CR-LEGACY-001",
                10.0,
                "codered",
                "legacy batch");

        ApplicationException ex = assertThrows(ApplicationException.class,
                () -> factoryService.logBatch(null, request));

        assertThat(ex.getMessage()).contains("Legacy production batch logging is disabled");
        assertThat(productionBatchRepository.findByCompanyOrderByProducedAtDesc(company)).isEmpty();
    }
}
