package com.bigbrightpaints.erp.modules.inventory.service;

import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodBatchRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialBatchRepository;
import com.bigbrightpaints.erp.modules.inventory.dto.InventoryExpiringBatchDto;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;

@Service
public class InventoryBatchQueryService {

    private final CompanyContextService companyContextService;
    private final CompanyClock companyClock;
    private final RawMaterialBatchRepository rawMaterialBatchRepository;
    private final FinishedGoodBatchRepository finishedGoodBatchRepository;

    public InventoryBatchQueryService(CompanyContextService companyContextService,
                                      CompanyClock companyClock,
                                      RawMaterialBatchRepository rawMaterialBatchRepository,
                                      FinishedGoodBatchRepository finishedGoodBatchRepository) {
        this.companyContextService = companyContextService;
        this.companyClock = companyClock;
        this.rawMaterialBatchRepository = rawMaterialBatchRepository;
        this.finishedGoodBatchRepository = finishedGoodBatchRepository;
    }

    @Transactional
    public List<InventoryExpiringBatchDto> listExpiringSoonBatches(int days) {
        int safeDays = Math.max(days, 0);
        Company company = companyContextService.requireCurrentCompany();
        LocalDate today = companyClock.today(company);
        LocalDate cutoff = today.plusDays(safeDays);

        List<InventoryExpiringBatchDto> rawMaterialRows = rawMaterialBatchRepository.findExpiringSoonByCompany(company, today, cutoff)
                .stream()
                .map(batch -> new InventoryExpiringBatchDto(
                        "RAW_MATERIAL",
                        batch.getId(),
                        batch.getPublicId(),
                        batch.getRawMaterial().getSku(),
                        batch.getRawMaterial().getName(),
                        batch.getBatchCode(),
                        batch.getQuantity(),
                        batch.getCostPerUnit(),
                        batch.getManufacturedAt(),
                        batch.getExpiryDate(),
                        ChronoUnit.DAYS.between(today, batch.getExpiryDate())
                ))
                .toList();

        List<InventoryExpiringBatchDto> finishedGoodRows = finishedGoodBatchRepository.findExpiringSoonByCompany(company, today, cutoff)
                .stream()
                .map(batch -> new InventoryExpiringBatchDto(
                        "FINISHED_GOOD",
                        batch.getId(),
                        batch.getPublicId(),
                        batch.getFinishedGood().getProductCode(),
                        batch.getFinishedGood().getName(),
                        batch.getBatchCode(),
                        batch.getQuantityAvailable(),
                        batch.getUnitCost(),
                        batch.getManufacturedAt(),
                        batch.getExpiryDate(),
                        ChronoUnit.DAYS.between(today, batch.getExpiryDate())
                ))
                .toList();

        return java.util.stream.Stream.concat(rawMaterialRows.stream(), finishedGoodRows.stream())
                .sorted(Comparator
                        .comparing(InventoryExpiringBatchDto::expiryDate)
                        .thenComparing(InventoryExpiringBatchDto::batchType)
                        .thenComparing(InventoryExpiringBatchDto::batchCode)
                        .thenComparing(InventoryExpiringBatchDto::batchId))
                .toList();
    }
}
