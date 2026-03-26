package com.bigbrightpaints.erp.modules.factory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

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

    when(productionLogRepository.findByCompanyAndStatusInOrderByProducedAtAsc(
            eq(company), anyList()))
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

  @Test
  void listUnpackedBatches_handlesMissingProductWithoutNpe() {
    ProductionLog log = new ProductionLog();
    ReflectionTestUtils.setField(log, "id", 12L);
    log.setCompany(company);
    log.setProductionCode("PROD-012");
    log.setStatus(ProductionLogStatus.READY_TO_PACK);
    log.setProducedAt(Instant.parse("2026-03-25T13:00:00Z"));

    when(productionLogRepository.findByCompanyAndStatusInOrderByProducedAtAsc(
            eq(company), anyList()))
        .thenReturn(List.of(log));

    List<UnpackedBatchDto> result = packingReadService.listUnpackedBatches();

    assertThat(result)
        .singleElement()
        .satisfies(
            batch -> {
              assertThat(batch.id()).isEqualTo(12L);
              assertThat(batch.productName()).isNull();
              assertThat(batch.productFamilyName()).isNull();
              assertThat(batch.allowedSellableSizes()).isEmpty();
            });
    verify(packingAllowedSizeService, never()).listAllowedSellableSizes(eq(company), same(log));
  }

  @Test
  void listUnpackedBatches_reusesAllowedSizeLookupForSameProductFamily() {
    UUID variantGroupId = UUID.randomUUID();

    ProductionLog firstLog = new ProductionLog();
    ReflectionTestUtils.setField(firstLog, "id", 20L);
    firstLog.setCompany(company);
    firstLog.setProductionCode("PROD-020");
    firstLog.setStatus(ProductionLogStatus.READY_TO_PACK);
    firstLog.setProducedAt(Instant.parse("2026-03-25T14:00:00Z"));
    ProductionProduct firstProduct = new ProductionProduct();
    ReflectionTestUtils.setField(firstProduct, "id", 100L);
    firstProduct.setProductName("Safari 1L");
    firstProduct.setProductFamilyName("Safari");
    firstProduct.setVariantGroupId(variantGroupId);
    firstLog.setProduct(firstProduct);

    ProductionLog secondLog = new ProductionLog();
    ReflectionTestUtils.setField(secondLog, "id", 21L);
    secondLog.setCompany(company);
    secondLog.setProductionCode("PROD-021");
    secondLog.setStatus(ProductionLogStatus.PARTIAL_PACKED);
    secondLog.setProducedAt(Instant.parse("2026-03-25T15:00:00Z"));
    ProductionProduct secondProduct = new ProductionProduct();
    ReflectionTestUtils.setField(secondProduct, "id", 101L);
    secondProduct.setProductName("Safari 4L");
    secondProduct.setProductFamilyName("Safari");
    secondProduct.setVariantGroupId(variantGroupId);
    secondLog.setProduct(secondProduct);

    when(productionLogRepository.findByCompanyAndStatusInOrderByProducedAtAsc(
            eq(company), anyList()))
        .thenReturn(List.of(firstLog, secondLog));
    when(packingAllowedSizeService.listAllowedSellableSizes(company, firstLog))
        .thenReturn(List.of());

    List<UnpackedBatchDto> result = packingReadService.listUnpackedBatches();

    assertThat(result).hasSize(2);
    verify(packingAllowedSizeService, times(1)).listAllowedSellableSizes(company, firstLog);
    verify(packingAllowedSizeService, never()).listAllowedSellableSizes(company, secondLog);
  }
}
