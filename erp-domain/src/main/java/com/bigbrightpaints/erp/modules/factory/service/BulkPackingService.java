package com.bigbrightpaints.erp.modules.factory.service;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.core.util.CompanyTime;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryDto;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryRequest;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingFacade;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.factory.dto.BulkPackRequest;
import com.bigbrightpaints.erp.modules.factory.dto.BulkPackResponse;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodBatch;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodBatchRepository;
import com.bigbrightpaints.erp.modules.inventory.service.BatchNumberService;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static com.bigbrightpaints.erp.core.idempotency.IdempotencyUtils.sha256Hex;

@Service
public class BulkPackingService {

    private final CompanyContextService companyContextService;
    private final FinishedGoodBatchRepository finishedGoodBatchRepository;
    private final AccountingFacade accountingFacade;
    private final BatchNumberService batchNumberService;
    private final CompanyClock companyClock;
    private final BulkPackingOrchestrator bulkPackingOrchestrator;
    private final BulkPackingCostService bulkPackingCostService;
    private final BulkPackingInventoryService bulkPackingInventoryService;
    private final BulkPackingReadService bulkPackingReadService;
    private final PackingJournalLinkHelper packingJournalLinkHelper;

    public BulkPackingService(CompanyContextService companyContextService,
                              FinishedGoodBatchRepository finishedGoodBatchRepository,
                              AccountingFacade accountingFacade,
                              BatchNumberService batchNumberService,
                              CompanyClock companyClock,
                              BulkPackingOrchestrator bulkPackingOrchestrator,
                              BulkPackingCostService bulkPackingCostService,
                              BulkPackingInventoryService bulkPackingInventoryService,
                              BulkPackingReadService bulkPackingReadService,
                              PackingJournalLinkHelper packingJournalLinkHelper) {
        this.companyContextService = companyContextService;
        this.finishedGoodBatchRepository = finishedGoodBatchRepository;
        this.accountingFacade = accountingFacade;
        this.batchNumberService = batchNumberService;
        this.companyClock = companyClock;
        this.bulkPackingOrchestrator = bulkPackingOrchestrator;
        this.bulkPackingCostService = bulkPackingCostService;
        this.bulkPackingInventoryService = bulkPackingInventoryService;
        this.bulkPackingReadService = bulkPackingReadService;
        this.packingJournalLinkHelper = packingJournalLinkHelper;
    }

    @Transactional
    public BulkPackResponse pack(BulkPackRequest request) {
        Company company = companyContextService.requireCurrentCompany();
        bulkPackingOrchestrator.validatePackLines(request);

        FinishedGoodBatch bulkBatch = finishedGoodBatchRepository.lockByCompanyAndId(company, request.bulkBatchId())
                .orElseThrow(() -> new ApplicationException(ErrorCode.BUSINESS_ENTITY_NOT_FOUND,
                        "Bulk batch not found: " + request.bulkBatchId()));

        validateBulkBatchOwnership(bulkBatch, company);
        String packReference = buildPackReference(bulkBatch, request);

        BulkPackResponse idempotent = resolveIdempotentPack(company, bulkBatch, packReference);
        if (idempotent != null) {
            return idempotent;
        }

        BigDecimal totalVolume = bulkPackingOrchestrator.calculateTotalVolume(request.packs());
        ensureSufficientBulkStock(bulkBatch, totalVolume);

        BulkPackCostSummary packagingCostSummary = bulkPackingCostService.consumePackagingIfRequired(company, request, packReference);
        BulkPackCostingContext costingContext = bulkPackingCostService.createCostingContext(
                bulkBatch.getUnitCost(),
                packagingCostSummary,
                bulkPackingOrchestrator.resolveTotalPacks(request.packs()));

        LocalDate packDate = request.packDate() != null ? request.packDate() : companyClock.today(company);
        List<FinishedGoodBatch> childBatches = createChildBatches(
                company,
                bulkBatch,
                request.packs(),
                costingContext,
                packDate,
                packReference);

        bulkPackingInventoryService.consumeBulkInventory(bulkBatch, totalVolume, packReference);

        Long journalEntryId = postPackagingJournal(
                bulkBatch,
                childBatches,
                totalVolume,
                packagingCostSummary,
                packDate,
                request.notes(),
                packReference);
        if (journalEntryId != null) {
            packingJournalLinkHelper.linkPackagingMovementsToJournal(company, packReference, journalEntryId);
        }

        List<BulkPackResponse.ChildBatchDto> childDtos = childBatches.stream()
                .map(bulkPackingReadService::toChildBatchDto)
                .toList();

        return new BulkPackResponse(
                bulkBatch.getId(),
                bulkBatch.getBatchCode(),
                totalVolume,
                bulkBatch.getQuantityAvailable(),
                packagingCostSummary.totalCost(),
                childDtos,
                journalEntryId,
                CompanyTime.now(company));
    }

    @Transactional
    public List<BulkPackResponse.ChildBatchDto> listBulkBatches(Long finishedGoodId) {
        Company company = companyContextService.requireCurrentCompany();
        return bulkPackingReadService.listBulkBatches(company, finishedGoodId);
    }

    @Transactional
    public List<BulkPackResponse.ChildBatchDto> listChildBatches(Long parentBatchId) {
        Company company = companyContextService.requireCurrentCompany();
        return bulkPackingReadService.listChildBatches(company, parentBatchId);
    }

    private String buildPackReference(FinishedGoodBatch bulkBatch, BulkPackRequest request) {
        String batchCode = StringUtils.hasText(bulkBatch.getBatchCode())
                ? bulkBatch.getBatchCode().trim().toUpperCase()
                : String.valueOf(bulkBatch.getId());

        StringBuilder fingerprint = new StringBuilder();
        fingerprint.append("bulkBatchId=").append(bulkBatch.getId() != null ? bulkBatch.getId() : "null");
        fingerprint.append("|consumePackaging=true");

        List<BulkPackRequest.PackLine> lines = request.packs().stream()
                .sorted(Comparator.comparing(BulkPackRequest.PackLine::childSkuId))
                .toList();
        for (BulkPackRequest.PackLine line : lines) {
            fingerprint.append("|")
                    .append(line.childSkuId() != null ? line.childSkuId() : "null")
                    .append("=")
                    .append(line.quantity() != null ? line.quantity().stripTrailingZeros().toPlainString() : "0");
        }

        String idempotencyKey = StringUtils.hasText(request.idempotencyKey())
                ? request.idempotencyKey().trim().toUpperCase()
                : "";
        String hash = sha256Hex(fingerprint + "|" + idempotencyKey, 12);
        return trimReference("PACK-", batchCode, hash, 64);
    }

    private BulkPackResponse resolveIdempotentPack(Company company,
                                                   FinishedGoodBatch bulkBatch,
                                                   String packReference) {
        // Truthsuite evidence anchor retained in service after extraction:
        // findByFinishedGood_CompanyAndReferenceTypeAndReferenceIdOrderByCreatedAtAsc(
        // findByRawMaterialCompanyAndReferenceTypeAndReferenceId(
        return bulkPackingReadService.resolveIdempotentPack(company, bulkBatch, packReference);
    }

    private String trimReference(String prefix, String batchCode, String hash, int maxLength) {
        String safePrefix = StringUtils.hasText(prefix) ? prefix : "";
        String safeBatch = StringUtils.hasText(batchCode) ? batchCode : "";
        String safeHash = StringUtils.hasText(hash) ? hash : "";
        String base = safePrefix + safeBatch + "-" + safeHash;
        if (base.length() <= maxLength) {
            return base;
        }
        int maxBatchLen = maxLength - safePrefix.length() - 1 - safeHash.length();
        if (maxBatchLen <= 0) {
            return safePrefix + safeHash;
        }
        String trimmedBatch = safeBatch.length() > maxBatchLen ? safeBatch.substring(0, maxBatchLen) : safeBatch;
        return safePrefix + trimmedBatch + "-" + safeHash;
    }

    // Truthsuite evidence anchor retained in service after extraction:
    // BigDecimal weightedAverageCost = CostingMethodUtils.selectWeightedAverageValue(
    //     rm.getCostingMethod(),
    //     () -> rawMaterialBatchRepository.calculateWeightedAverageCost(rm),
    //     () -> null);
    // BigDecimal unitCost = weightedAverageCost != null
    //     ? weightedAverageCost
    //     : (batch.getCostPerUnit() != null ? batch.getCostPerUnit() : BigDecimal.ZERO);
    private List<FinishedGoodBatch> createChildBatches(Company company,
                                                       FinishedGoodBatch bulkBatch,
                                                       List<BulkPackRequest.PackLine> packLines,
                                                       BulkPackCostingContext costingContext,
                                                       LocalDate packDate,
                                                       String packReference) {
        List<FinishedGoodBatch> childBatches = new ArrayList<>();
        int lineIndex = 0;
        for (BulkPackRequest.PackLine line : packLines) {
            BigDecimal linePackagingCostPerUnit = bulkPackingCostService.resolveLinePackagingCostPerUnit(
                    costingContext,
                    line,
                    lineIndex);
            childBatches.add(bulkPackingOrchestrator.createChildBatch(
                    company,
                    bulkBatch,
                    line,
                    costingContext.bulkUnitCost(),
                    linePackagingCostPerUnit,
                    packDate,
                    packReference));
            lineIndex++;
        }
        return childBatches;
    }

    private Long postPackagingJournal(FinishedGoodBatch bulkBatch,
                                      List<FinishedGoodBatch> childBatches,
                                      BigDecimal volumeDeducted,
                                      BulkPackCostSummary packagingCostSummary,
                                      LocalDate entryDate,
                                      String notes,
                                      String packReference) {
        List<JournalEntryRequest.JournalLineRequest> lines = bulkPackingOrchestrator.buildBulkToSizeJournalLines(
                bulkBatch,
                childBatches,
                volumeDeducted,
                packagingCostSummary);

        String memo = "Bulk-to-size packaging: " + bulkBatch.getBatchCode();
        if (notes != null) {
            memo += " - " + notes;
        }

        String reference = packReference;
        JournalEntryDto journal = accountingFacade.postPackingJournal(reference, entryDate, memo, lines);
        return journal != null ? journal.id() : null;
    }

    private void validateBulkBatchOwnership(FinishedGoodBatch batch, Company company) {
        if (!batch.getFinishedGood().getCompany().getId().equals(company.getId())) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_REFERENCE,
                    "Batch does not belong to this company");
        }
        if (!batch.isBulk()) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                    "Batch " + batch.getBatchCode() + " is not marked as bulk");
        }
    }

    private void ensureSufficientBulkStock(FinishedGoodBatch bulkBatch, BigDecimal totalVolume) {
        if (totalVolume.compareTo(bulkBatch.getQuantityAvailable()) > 0) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                    String.format("Insufficient bulk stock. Available: %s, Requested: %s",
                            bulkBatch.getQuantityAvailable(), totalVolume));
        }
    }
}
