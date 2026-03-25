package com.bigbrightpaints.erp.modules.factory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.factory.domain.PackingRecordRepository;
import com.bigbrightpaints.erp.modules.factory.domain.ProductionLog;
import com.bigbrightpaints.erp.modules.factory.domain.ProductionLogRepository;
import com.bigbrightpaints.erp.modules.factory.domain.ProductionLogStatus;
import com.bigbrightpaints.erp.modules.factory.dto.UnpackedBatchDto;
import com.bigbrightpaints.erp.modules.production.domain.ProductionProduct;

@Tag("critical")
@ExtendWith(MockitoExtension.class)
class PackingReadServiceTest {

  @Mock private CompanyContextService companyContextService;
  @Mock private ProductionLogRepository productionLogRepository;
  @Mock private PackingRecordRepository packingRecordRepository;
  @Mock private com.bigbrightpaints.erp.core.util.CompanyEntityLookup companyEntityLookup;
  @Mock private PackingAllowedSizeService packingAllowedSizeService;

  private PackingReadService packingReadService;
  private Company company;

  @BeforeEach
  void setUp() {
    packingReadService =
        new PackingReadService(
            companyContextService,
            productionLogRepository,
            packingRecordRepository,
            companyEntityLookup,
            packingAllowedSizeService);
    company = new Company();
    when(companyContextService.requireCurrentCompany()).thenReturn(company);
  }

  @Test
  void listUnpackedBatches_allowsProductWithoutFamilyName() {
    ProductionLog log = new ProductionLog();
    ReflectionTestUtils.setField(log, "id", 9L);
    log.setCompany(company);
    log.setProductionCode("PROD-009");
    log.setStatus(ProductionLogStatus.READY_TO_PACK);
    log.setProducedAt(Instant.parse("2026-03-25T12:00:00Z"));
    ProductionProduct product = new ProductionProduct();
    product.setProductName("Primer");
    log.setProduct(product);

    when(productionLogRepository.findByCompanyAndStatusInOrderByProducedAtAsc(eq(company), anyList()))
        .thenReturn(List.of(log));
    when(packingAllowedSizeService.listAllowedSellableSizes(company, log)).thenReturn(List.of());

    List<UnpackedBatchDto> result = packingReadService.listUnpackedBatches();

    assertThat(result)
        .singleElement()
        .satisfies(
            batch -> {
              assertThat(batch.id()).isEqualTo(9L);
              assertThat(batch.productFamilyName()).isNull();
              assertThat(batch.mixedQuantity()).isEqualByComparingTo(BigDecimal.ZERO);
              assertThat(batch.packedQuantity()).isEqualByComparingTo(BigDecimal.ZERO);
              assertThat(batch.remainingQuantity()).isEqualByComparingTo(BigDecimal.ZERO);
              assertThat(batch.allowedSellableSizes()).isEmpty();
            });
  }
}
