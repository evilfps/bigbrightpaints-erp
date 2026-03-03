package com.bigbrightpaints.erp.modules.factory.service;

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
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@Service
public class PackingReadService {

    private final CompanyContextService companyContextService;
    private final ProductionLogRepository productionLogRepository;
    private final PackingRecordRepository packingRecordRepository;
    private final CompanyEntityLookup companyEntityLookup;

    public PackingReadService(CompanyContextService companyContextService,
                              ProductionLogRepository productionLogRepository,
                              PackingRecordRepository packingRecordRepository,
                              CompanyEntityLookup companyEntityLookup) {
        this.companyContextService = companyContextService;
        this.productionLogRepository = productionLogRepository;
        this.packingRecordRepository = packingRecordRepository;
        this.companyEntityLookup = companyEntityLookup;
    }

    public List<UnpackedBatchDto> listUnpackedBatches() {
        Company company = companyContextService.requireCurrentCompany();
        List<ProductionLogStatus> statuses = List.of(ProductionLogStatus.READY_TO_PACK, ProductionLogStatus.PARTIAL_PACKED);
        return productionLogRepository.findByCompanyAndStatusInOrderByProducedAtAsc(company, statuses).stream()
                .map(log -> new UnpackedBatchDto(
                        log.getId(),
                        log.getProductionCode(),
                        log.getProduct().getProductName(),
                        log.getBatchColour(),
                        Optional.ofNullable(log.getMixedQuantity()).orElse(BigDecimal.ZERO),
                        Optional.ofNullable(log.getTotalPackedQuantity()).orElse(BigDecimal.ZERO),
                        Optional.ofNullable(log.getMixedQuantity()).orElse(BigDecimal.ZERO)
                                .subtract(Optional.ofNullable(log.getTotalPackedQuantity()).orElse(BigDecimal.ZERO))
                                .max(BigDecimal.ZERO),
                        log.getStatus().name(),
                        log.getProducedAt()))
                .toList();
    }

    public List<PackingRecordDto> packingHistory(Long productionLogId) {
        Company company = companyContextService.requireCurrentCompany();
        ProductionLog log = companyEntityLookup.requireProductionLog(company, productionLogId);
        return packingRecordRepository.findByCompanyAndProductionLogOrderByPackedDateAscIdAsc(company, log).stream()
                .sorted(Comparator.comparing(PackingRecord::getPackedDate).thenComparing(PackingRecord::getId))
                .map(record -> new PackingRecordDto(
                        record.getId(),
                        record.getProductionLog() != null ? record.getProductionLog().getId() : null,
                        record.getProductionLog() != null ? record.getProductionLog().getProductionCode() : null,
                        record.getSizeVariant() != null ? record.getSizeVariant().getId() : null,
                        record.getSizeVariant() != null ? record.getSizeVariant().getSizeLabel() : null,
                        record.getFinishedGoodBatch() != null ? record.getFinishedGoodBatch().getId() : null,
                        record.getFinishedGoodBatch() != null ? record.getFinishedGoodBatch().getBatchCode() : null,
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
