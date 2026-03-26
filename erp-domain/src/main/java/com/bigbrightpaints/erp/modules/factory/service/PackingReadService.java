package com.bigbrightpaints.erp.modules.factory.service;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;

import com.bigbrightpaints.erp.core.util.CompanyEntityLookup;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.factory.domain.PackingRecord;
import com.bigbrightpaints.erp.modules.factory.domain.PackingRecordRepository;
import com.bigbrightpaints.erp.modules.factory.domain.ProductionLog;
import com.bigbrightpaints.erp.modules.factory.domain.ProductionLogRepository;
import com.bigbrightpaints.erp.modules.factory.domain.ProductionLogStatus;
import com.bigbrightpaints.erp.modules.factory.dto.PackingRecordDto;
import com.bigbrightpaints.erp.modules.factory.dto.UnpackedBatchDto;
import com.bigbrightpaints.erp.modules.production.domain.ProductionProduct;

@Service
public class PackingReadService {

  private final CompanyContextService companyContextService;
  private final ProductionLogRepository productionLogRepository;
  private final PackingRecordRepository packingRecordRepository;
  private final CompanyEntityLookup companyEntityLookup;
  private final PackingAllowedSizeService packingAllowedSizeService;

  public PackingReadService(
      CompanyContextService companyContextService,
      ProductionLogRepository productionLogRepository,
      PackingRecordRepository packingRecordRepository,
      CompanyEntityLookup companyEntityLookup,
      PackingAllowedSizeService packingAllowedSizeService) {
    this.companyContextService = companyContextService;
    this.productionLogRepository = productionLogRepository;
    this.packingRecordRepository = packingRecordRepository;
    this.companyEntityLookup = companyEntityLookup;
    this.packingAllowedSizeService = packingAllowedSizeService;
  }

  public List<UnpackedBatchDto> listUnpackedBatches() {
    Company company = companyContextService.requireCurrentCompany();
    List<ProductionLogStatus> statuses =
        List.of(ProductionLogStatus.READY_TO_PACK, ProductionLogStatus.PARTIAL_PACKED);
    List<ProductionLog> logs =
        productionLogRepository.findByCompanyAndStatusInOrderByProducedAtAsc(company, statuses);
    Map<String, List<com.bigbrightpaints.erp.modules.factory.dto.AllowedSellableSizeDto>>
        allowedSizesByKey = new LinkedHashMap<>();
    return logs
        .stream()
        .map(
            log -> {
              ProductionProduct product = log.getProduct();
              List<com.bigbrightpaints.erp.modules.factory.dto.AllowedSellableSizeDto>
                  allowedSellableSizes =
                      product == null
                          ? List.of()
                          : allowedSizesByKey.computeIfAbsent(
                              allowedSellableSizeCacheKey(product),
                              ignored -> packingAllowedSizeService.listAllowedSellableSizes(company, log));
              return new UnpackedBatchDto(
                  log.getId(),
                  log.getProductionCode(),
                  product != null ? product.getProductName() : null,
                  log.getBatchColour(),
                  Optional.ofNullable(log.getMixedQuantity()).orElse(BigDecimal.ZERO),
                  Optional.ofNullable(log.getTotalPackedQuantity()).orElse(BigDecimal.ZERO),
                  Optional.ofNullable(log.getMixedQuantity())
                      .orElse(BigDecimal.ZERO)
                      .subtract(
                          Optional.ofNullable(log.getTotalPackedQuantity()).orElse(BigDecimal.ZERO))
                      .max(BigDecimal.ZERO),
                  log.getStatus().name(),
                  log.getProducedAt(),
                  product != null ? product.getProductFamilyName() : null,
                  allowedSellableSizes);
            })
        .toList();
  }

  private String allowedSellableSizeCacheKey(ProductionProduct product) {
    String familyName = Optional.ofNullable(resolveAllowedSizeFamilyKey(product)).orElse("");
    if (product.getVariantGroupId() != null) {
      return "group:" + product.getVariantGroupId() + "|family:" + familyName;
    }
    if (product.getId() != null) {
      return "product:" + product.getId() + "|family:" + familyName;
    }
    if (product.getSkuCode() != null && !product.getSkuCode().isBlank()) {
      return "sku:" + product.getSkuCode().trim().toLowerCase(Locale.ROOT) + "|family:" + familyName;
    }
    return "family:" + familyName;
  }

  private String resolveAllowedSizeFamilyKey(ProductionProduct product) {
    if (product == null) {
      return null;
    }
    if (product.getProductFamilyName() != null && !product.getProductFamilyName().isBlank()) {
      return product.getProductFamilyName().trim();
    }
    if (product.getProductName() != null && !product.getProductName().isBlank()) {
      return product.getProductName().trim();
    }
    return product.getSkuCode();
  }

  public List<PackingRecordDto> packingHistory(Long productionLogId) {
    Company company = companyContextService.requireCurrentCompany();
    ProductionLog log = companyEntityLookup.requireProductionLog(company, productionLogId);
    return packingRecordRepository
        .findByCompanyAndProductionLogOrderByPackedDateAscIdAsc(company, log)
        .stream()
        .sorted(
            Comparator.comparing(PackingRecord::getPackedDate).thenComparing(PackingRecord::getId))
        .map(
            record ->
                new PackingRecordDto(
                    record.getId(),
                    record.getProductionLog() != null ? record.getProductionLog().getId() : null,
                    record.getProductionLog() != null
                        ? record.getProductionLog().getProductionCode()
                        : null,
                    record.getSizeVariant() != null ? record.getSizeVariant().getId() : null,
                    record.getSizeVariant() != null ? record.getSizeVariant().getSizeLabel() : null,
                    record.getFinishedGoodBatch() != null
                        ? record.getFinishedGoodBatch().getId()
                        : null,
                    record.getFinishedGoodBatch() != null
                        ? record.getFinishedGoodBatch().getBatchCode()
                        : null,
                    record.getChildBatchCount(),
                    record.getPackagingSize(),
                    record.getQuantityPacked(),
                    record.getPiecesCount(),
                    record.getBoxesCount(),
                    record.getPiecesPerBox(),
                    record.getPackedDate(),
                    record.getPackedBy()))
        .toList();
  }
}
