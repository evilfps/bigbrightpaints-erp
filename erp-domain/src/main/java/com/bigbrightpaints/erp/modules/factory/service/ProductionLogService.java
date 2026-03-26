package com.bigbrightpaints.erp.modules.factory.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.core.util.CompanyEntityLookup;
import com.bigbrightpaints.erp.core.util.CompanyTime;
import com.bigbrightpaints.erp.core.util.CostingMethodUtils;
import com.bigbrightpaints.erp.core.util.MoneyUtils;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalCreationRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryDto;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingFacade;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.factory.domain.ProductionLog;
import com.bigbrightpaints.erp.modules.factory.domain.ProductionLogMaterial;
import com.bigbrightpaints.erp.modules.factory.domain.ProductionLogRepository;
import com.bigbrightpaints.erp.modules.factory.domain.ProductionLogStatus;
import com.bigbrightpaints.erp.modules.factory.dto.AllowedSellableSizeDto;
import com.bigbrightpaints.erp.modules.factory.dto.ProductionLogDetailDto;
import com.bigbrightpaints.erp.modules.factory.dto.ProductionLogDto;
import com.bigbrightpaints.erp.modules.factory.dto.ProductionLogMaterialDto;
import com.bigbrightpaints.erp.modules.factory.dto.ProductionLogPackingRecordDto;
import com.bigbrightpaints.erp.modules.factory.dto.ProductionLogRequest;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGood;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodBatch;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodBatchRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryBatchSource;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryMovement;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryMovementRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryReference;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterial;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialBatch;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialBatchRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialMovement;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialMovementRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialRepository;
import com.bigbrightpaints.erp.modules.production.domain.ProductionBrand;
import com.bigbrightpaints.erp.modules.production.domain.ProductionProduct;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrder;

import jakarta.transaction.Transactional;

@Service
public class ProductionLogService {

  private static final DateTimeFormatter CODE_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
  private static final String MOVEMENT_TYPE_ISSUE = "ISSUE";
  private static final RoundingMode COST_ROUNDING = RoundingMode.HALF_UP;

  private final CompanyContextService companyContextService;
  private final CompanyRepository companyRepository;
  private final ProductionLogRepository logRepository;
  private final RawMaterialRepository rawMaterialRepository;
  private final RawMaterialBatchRepository rawMaterialBatchRepository;
  private final RawMaterialMovementRepository rawMaterialMovementRepository;
  private final AccountingFacade accountingFacade;
  private final CompanyEntityLookup companyEntityLookup;
  private final CompanyClock companyClock;
  private final FinishedGoodRepository finishedGoodRepository;
  private final FinishedGoodBatchRepository finishedGoodBatchRepository;
  private final InventoryMovementRepository inventoryMovementRepository;
  private final PackingAllowedSizeService packingAllowedSizeService;

  public ProductionLogService(
      CompanyContextService companyContextService,
      CompanyRepository companyRepository,
      ProductionLogRepository logRepository,
      RawMaterialRepository rawMaterialRepository,
      RawMaterialBatchRepository rawMaterialBatchRepository,
      RawMaterialMovementRepository rawMaterialMovementRepository,
      AccountingFacade accountingFacade,
      CompanyEntityLookup companyEntityLookup,
      CompanyClock companyClock,
      FinishedGoodRepository finishedGoodRepository,
      FinishedGoodBatchRepository finishedGoodBatchRepository,
      InventoryMovementRepository inventoryMovementRepository,
      PackingAllowedSizeService packingAllowedSizeService) {
    this.companyContextService = companyContextService;
    this.companyRepository = companyRepository;
    this.logRepository = logRepository;
    this.rawMaterialRepository = rawMaterialRepository;
    this.rawMaterialBatchRepository = rawMaterialBatchRepository;
    this.rawMaterialMovementRepository = rawMaterialMovementRepository;
    this.accountingFacade = accountingFacade;
    this.companyEntityLookup = companyEntityLookup;
    this.companyClock = companyClock;
    this.finishedGoodRepository = finishedGoodRepository;
    this.finishedGoodBatchRepository = finishedGoodBatchRepository;
    this.inventoryMovementRepository = inventoryMovementRepository;
    this.packingAllowedSizeService = packingAllowedSizeService;
  }

  @Transactional
  public ProductionLogDetailDto createLog(ProductionLogRequest request) {
    Company company = companyContextService.requireCurrentCompany();
    if (company.getId() != null) {
      companyRepository.lockById(company.getId());
    }
    ProductionBrand brand = companyEntityLookup.requireProductionBrand(company, request.brandId());
    ProductionProduct product =
        companyEntityLookup.requireProductionProduct(company, request.productId());
    if (!product.getBrand().getId().equals(brand.getId())) {
      throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
          "Product does not belong to brand");
    }
    BigDecimal batchSize = positive(request.batchSize(), "batchSize");
    BigDecimal mixedQty = positive(request.mixedQuantity(), "mixedQuantity");
    String unitOfMeasure =
        StringUtils.hasText(request.unitOfMeasure())
            ? request.unitOfMeasure().trim()
            : Optional.ofNullable(product.getUnitOfMeasure())
                .filter(StringUtils::hasText)
                .orElse("UNIT");
    String productionCode = nextProductionCode(company);

    ProductionLog log = new ProductionLog();
    log.setCompany(company);
    log.setBrand(brand);
    log.setProduct(product);
    log.setProductionCode(productionCode);
    log.setBatchColour(clean(request.batchColour()));
    log.setBatchSize(batchSize);
    log.setUnitOfMeasure(unitOfMeasure);
    log.setMixedQuantity(mixedQty);
    log.setStatus(ProductionLogStatus.READY_TO_PACK);
    log.setTotalPackedQuantity(BigDecimal.ZERO);
    log.setWastageQuantity(mixedQty);
    log.setWastageReasonCode("PROCESS_LOSS");
    log.setProducedAt(resolveProducedAt(company, request.producedAt()));
    log.setNotes(clean(request.notes()));
    log.setCreatedBy(clean(request.createdBy()));
    if (request.salesOrderId() != null) {
      SalesOrder order = companyEntityLookup.requireSalesOrder(company, request.salesOrderId());
      log.setSalesOrderId(order.getId());
      log.setSalesOrderNumber(order.getOrderNumber());
    }
    BigDecimal laborCost = nonNegative(request.laborCost());
    BigDecimal overheadCost = nonNegative(request.overheadCost());
    log.setLaborCostTotal(laborCost);
    log.setOverheadCostTotal(overheadCost);

    if (request.materials() == null || request.materials().isEmpty()) {
      throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
          "Materials are required");
    }

    MaterialIssueSummary issueSummary = issueMaterials(company, log, request.materials());
    log.setMaterialCostTotal(issueSummary.totalCost());
    BigDecimal totalCost = issueSummary.totalCost().add(laborCost).add(overheadCost);
    log.setUnitCost(calculateUnitCost(totalCost, mixedQty));

    ProductionLog saved = logRepository.save(log);

    postMaterialJournal(company, saved, product, issueSummary);
    postLaborOverheadJournal(company, saved, product);
    registerSemiFinishedBatch(company, saved, product, mixedQty, totalCost);

    return toDetailDto(saved);
  }

  private void registerSemiFinishedBatch(
      Company company,
      ProductionLog log,
      ProductionProduct product,
      BigDecimal mixedQty,
      BigDecimal totalCost) {
    if (mixedQty.compareTo(BigDecimal.ZERO) <= 0) {
      return;
    }

    FinishedGood semiFinished = ensureSemiFinishedFinishedGood(company, product);
    Long semiFinishedAccountId = semiFinished.getValuationAccountId();
    if (semiFinishedAccountId == null) {
      throw new ApplicationException(
          ErrorCode.VALIDATION_INVALID_REFERENCE,
          "Semi-finished SKU " + semiFinished.getProductCode() + " missing valuation account");
    }

    String batchCode = log.getProductionCode();
    FinishedGoodBatch batch =
        finishedGoodBatchRepository
            .findByFinishedGoodAndBatchCode(semiFinished, batchCode)
            .orElseGet(() -> createSemiFinishedBatch(log, semiFinished, mixedQty, batchCode));

    BigDecimal current =
        Optional.ofNullable(semiFinished.getCurrentStock()).orElse(BigDecimal.ZERO);
    semiFinished.setCurrentStock(current.add(mixedQty));
    finishedGoodRepository.save(semiFinished);

    InventoryMovement movement = new InventoryMovement();
    movement.setFinishedGood(semiFinished);
    movement.setFinishedGoodBatch(batch);
    movement.setReferenceType(InventoryReference.PRODUCTION_LOG);
    movement.setReferenceId(log.getProductionCode());
    movement.setMovementType("RECEIPT");
    movement.setQuantity(mixedQty);
    movement.setUnitCost(log.getUnitCost());
    InventoryMovement savedMovement = inventoryMovementRepository.save(movement);

    BigDecimal amount = totalCost.setScale(2, COST_ROUNDING);
    if (amount.compareTo(BigDecimal.ZERO) > 0) {
      Long wipAccountId = requireWipAccountId(product);
      JournalEntryDto entry =
          accountingFacade.createStandardJournal(
              new JournalCreationRequest(
                  amount,
                  semiFinishedAccountId,
                  wipAccountId,
                  "Semi-finished receipt for " + log.getProductionCode(),
                  "FACTORY_PRODUCTION",
                  log.getProductionCode() + "-SEMIFG",
                  null,
                  null,
                  resolveJournalDate(company, log),
                  null,
                  null,
                  Boolean.FALSE));
      if (entry != null) {
        savedMovement.setJournalEntryId(entry.id());
        inventoryMovementRepository.save(savedMovement);
      }
    }
  }

  private FinishedGood ensureSemiFinishedFinishedGood(Company company, ProductionProduct product) {
    String semiSku = product.getSkuCode() + "-BULK";
    return finishedGoodRepository
        .lockByCompanyAndProductCode(company, semiSku)
        .orElseGet(() -> initializeSemiFinishedFinishedGood(company, product, semiSku));
  }

  private FinishedGood initializeSemiFinishedFinishedGood(
      Company company, ProductionProduct product, String semiSku) {
    Long valuationAccountId =
        Optional.ofNullable(metadataLong(product, "semiFinishedAccountId"))
            .orElse(metadataLong(product, "fgValuationAccountId"));
    Long cogsAccountId = metadataLong(product, "fgCogsAccountId");
    Long revenueAccountId = metadataLong(product, "fgRevenueAccountId");
    Long discountAccountId = metadataLong(product, "fgDiscountAccountId");
    Long taxAccountId = metadataLong(product, "fgTaxAccountId");
    if (valuationAccountId == null
        || cogsAccountId == null
        || revenueAccountId == null
        || discountAccountId == null
        || taxAccountId == null) {
      throw new ApplicationException(
          ErrorCode.VALIDATION_INVALID_REFERENCE,
          "Product " + product.getProductName() + " missing finished good account metadata");
    }
    FinishedGood created = new FinishedGood();
    created.setCompany(company);
    created.setProductCode(semiSku);
    created.setName(product.getProductName() + " (Bulk)");
    created.setUnit(Optional.ofNullable(product.getUnitOfMeasure()).orElse("UNIT"));
    created.setCostingMethod("FIFO");
    created.setValuationAccountId(valuationAccountId);
    created.setCogsAccountId(cogsAccountId);
    created.setRevenueAccountId(revenueAccountId);
    created.setDiscountAccountId(discountAccountId);
    created.setTaxAccountId(taxAccountId);
    created.setCurrentStock(BigDecimal.ZERO);
    created.setReservedStock(BigDecimal.ZERO);
    return finishedGoodRepository.save(created);
  }

  private FinishedGoodBatch createSemiFinishedBatch(
      ProductionLog log, FinishedGood semiFinished, BigDecimal quantity, String batchCode) {
    FinishedGoodBatch batch = new FinishedGoodBatch();
    batch.setFinishedGood(semiFinished);
    batch.setBatchCode(batchCode);
    batch.setQuantityTotal(quantity);
    batch.setQuantityAvailable(quantity);
    batch.setUnitCost(log.getUnitCost());
    batch.setManufacturedAt(log.getProducedAt());
    batch.setBulk(true);
    batch.setSizeLabel(log.getUnitOfMeasure());
    batch.setSource(InventoryBatchSource.PRODUCTION);
    return finishedGoodBatchRepository.save(batch);
  }

  @Transactional
  public List<ProductionLogDto> recentLogs() {
    Company company = companyContextService.requireCurrentCompany();
    return logRepository.findTop25ByCompanyOrderByProducedAtDesc(company).stream()
        .map(this::toDto)
        .toList();
  }

  @Transactional
  public ProductionLogDetailDto getLog(Long id) {
    Company company = companyContextService.requireCurrentCompany();
    ProductionLog log = companyEntityLookup.requireProductionLog(company, id);
    return toDetailDto(log);
  }

  private MaterialIssueSummary issueMaterials(
      Company company, ProductionLog log, List<ProductionLogRequest.MaterialUsageRequest> usages) {
    BigDecimal totalCost = BigDecimal.ZERO;
    Map<Long, BigDecimal> accountTotals = new HashMap<>();
    for (ProductionLogRequest.MaterialUsageRequest usage : usages) {
      MaterialConsumption consumption = consumeMaterial(company, log, usage);
      log.getMaterials().addAll(consumption.materials());
      totalCost = totalCost.add(consumption.totalCost());
      accountTotals.merge(
          consumption.inventoryAccountId(), consumption.totalCost(), BigDecimal::add);
    }
    return new MaterialIssueSummary(totalCost, Map.copyOf(accountTotals));
  }

  private MaterialConsumption consumeMaterial(
      Company company, ProductionLog log, ProductionLogRequest.MaterialUsageRequest usage) {
    BigDecimal qty = positive(usage.quantity(), "materials.quantity");
    RawMaterial rawMaterial;
    try {
      rawMaterial = companyEntityLookup.lockActiveRawMaterial(company, usage.rawMaterialId());
    } catch (IllegalArgumentException ex) {
      throw new ApplicationException(
          ErrorCode.VALIDATION_INVALID_REFERENCE, "Raw material not found");
    }
    if (rawMaterial.getCurrentStock().compareTo(qty) < 0) {
      throw new ApplicationException(
          ErrorCode.VALIDATION_INVALID_INPUT, "Insufficient stock for " + rawMaterial.getName());
    }
    if (rawMaterial.getInventoryAccountId() == null) {
      throw new ApplicationException(
          ErrorCode.VALIDATION_INVALID_REFERENCE,
          "Raw material " + rawMaterial.getName() + " missing inventory account");
    }

    List<BatchIssue> issues = issueFromBatches(rawMaterial, qty, log.getProductionCode());
    rawMaterial.setCurrentStock(rawMaterial.getCurrentStock().subtract(qty));
    rawMaterialRepository.save(rawMaterial);

    String unitOfMeasure =
        StringUtils.hasText(usage.unitOfMeasure())
            ? usage.unitOfMeasure().trim()
            : rawMaterial.getUnitType();
    List<ProductionLogMaterial> materials = new ArrayList<>();
    BigDecimal totalCost = BigDecimal.ZERO;
    for (BatchIssue issue : issues) {
      ProductionLogMaterial material = new ProductionLogMaterial();
      material.setLog(log);
      material.setRawMaterial(rawMaterial);
      material.setRawMaterialBatch(issue.batch());
      material.setRawMaterialMovementId(issue.movement() != null ? issue.movement().getId() : null);
      material.setMaterialName(rawMaterial.getName());
      material.setQuantity(issue.quantity());
      material.setUnitOfMeasure(unitOfMeasure);
      material.setCostPerUnit(issue.unitCost());
      material.setTotalCost(issue.totalCost());
      materials.add(material);
      totalCost = totalCost.add(issue.totalCost());
    }

    return new MaterialConsumption(materials, totalCost, rawMaterial.getInventoryAccountId());
  }

  private List<BatchIssue> issueFromBatches(
      RawMaterial rawMaterial, BigDecimal requiredQty, String referenceId) {
    // Lock batches in FIFO order (pessimistic lock) to prevent double consumption
    List<RawMaterialBatch> batches =
        rawMaterialBatchRepository.findAvailableBatchesFIFO(rawMaterial);
    BigDecimal weightedAverageCost =
        CostingMethodUtils.selectWeightedAverageValue(
            rawMaterial.getCostingMethod(),
            () -> rawMaterialBatchRepository.calculateWeightedAverageCost(rawMaterial),
            () -> null);
    BigDecimal remaining = requiredQty;
    List<BatchIssue> issues = new ArrayList<>();

    for (RawMaterialBatch batch : batches) {
      if (remaining.compareTo(BigDecimal.ZERO) <= 0) {
        break;
      }
      BigDecimal available = Optional.ofNullable(batch.getQuantity()).orElse(BigDecimal.ZERO);
      if (available.compareTo(BigDecimal.ZERO) <= 0) {
        continue;
      }
      BigDecimal take = available.min(remaining);
      if (take.compareTo(BigDecimal.ZERO) <= 0) {
        continue;
      }

      // Capture cost snapshot BEFORE deduction while holding pessimistic lock
      BigDecimal unitCost =
          weightedAverageCost != null
              ? weightedAverageCost
              : Optional.ofNullable(batch.getCostPerUnit()).orElse(BigDecimal.ZERO);
      BigDecimal movementCost = unitCost.multiply(take);

      // Use atomic update to prevent race conditions and negative quantities
      int updated = rawMaterialBatchRepository.deductQuantityIfSufficient(batch.getId(), take);
      if (updated == 0) {
        throw new ApplicationException(
            ErrorCode.VALIDATION_INVALID_INPUT,
            "Concurrent modification detected or insufficient quantity for batch "
                + batch.getBatchCode());
      }

      RawMaterialMovement movement = new RawMaterialMovement();
      movement.setRawMaterial(rawMaterial);
      movement.setRawMaterialBatch(batch);
      movement.setReferenceType(InventoryReference.PRODUCTION_LOG);
      movement.setReferenceId(referenceId);
      movement.setMovementType(MOVEMENT_TYPE_ISSUE);
      movement.setQuantity(take);
      movement.setUnitCost(unitCost);
      RawMaterialMovement savedMovement = rawMaterialMovementRepository.save(movement);

      issues.add(new BatchIssue(batch, take, unitCost, movementCost, savedMovement));
      remaining = remaining.subtract(take);
    }
    if (remaining.compareTo(BigDecimal.ZERO) > 0) {
      throw new ApplicationException(
          ErrorCode.VALIDATION_INVALID_INPUT,
          "Insufficient batch availability for " + rawMaterial.getName());
    }
    return issues;
  }

  private void postMaterialJournal(
      Company company, ProductionLog log, ProductionProduct product, MaterialIssueSummary summary) {
    if (summary.totalCost().compareTo(BigDecimal.ZERO) <= 0) {
      return;
    }
    Long wipAccountId = requireWipAccountId(product);

    List<JournalCreationRequest.LineRequest> lines = new ArrayList<>();
    lines.add(
        new JournalCreationRequest.LineRequest(
            wipAccountId,
            summary.totalCost(),
            BigDecimal.ZERO,
            "WIP charge " + log.getProductionCode()));
    summary
        .accountTotals()
        .forEach(
            (accountId, amount) -> {
              if (amount != null && amount.compareTo(BigDecimal.ZERO) > 0) {
                lines.add(
                    new JournalCreationRequest.LineRequest(
                        accountId,
                        BigDecimal.ZERO,
                        amount.abs(),
                        "Raw material issue " + log.getProductionCode()));
              }
            });

    Long primaryCreditAccount =
        summary.accountTotals().keySet().stream().findFirst().orElse(wipAccountId);
    JournalEntryDto entry =
        accountingFacade.createStandardJournal(
            new JournalCreationRequest(
                summary.totalCost(),
                wipAccountId,
                primaryCreditAccount,
                "Raw material consumption for " + log.getProductionCode(),
                "FACTORY_PRODUCTION",
                log.getProductionCode() + "-RM",
                null,
                lines,
                resolveJournalDate(company, log),
                null,
                null,
                Boolean.FALSE));

    if (entry != null) {
      linkRawMaterialMovementsToJournal(company, log.getProductionCode(), entry.id());
    }
  }

  private void postLaborOverheadJournal(
      Company company, ProductionLog log, ProductionProduct product) {
    BigDecimal laborCost = nonNegative(log.getLaborCostTotal());
    BigDecimal overheadCost = nonNegative(log.getOverheadCostTotal());
    if (laborCost.compareTo(BigDecimal.ZERO) <= 0 && overheadCost.compareTo(BigDecimal.ZERO) <= 0) {
      return;
    }

    Long wipAccountId = requireWipAccountId(product);
    Long laborAppliedAccountId = null;
    Long overheadAppliedAccountId = null;
    if (laborCost.compareTo(BigDecimal.ZERO) > 0) {
      laborAppliedAccountId = requireLaborAppliedAccountId(product);
    }
    if (overheadCost.compareTo(BigDecimal.ZERO) > 0) {
      overheadAppliedAccountId = requireOverheadAppliedAccountId(product);
    }

    BigDecimal totalAmount = laborCost.add(overheadCost);
    List<JournalCreationRequest.LineRequest> lines = new ArrayList<>();
    lines.add(
        new JournalCreationRequest.LineRequest(
            wipAccountId,
            totalAmount,
            BigDecimal.ZERO,
            "WIP labor/overhead " + log.getProductionCode()));
    if (laborCost.compareTo(BigDecimal.ZERO) > 0) {
      lines.add(
          new JournalCreationRequest.LineRequest(
              laborAppliedAccountId,
              BigDecimal.ZERO,
              laborCost.abs(),
              "Labor applied " + log.getProductionCode()));
    }
    if (overheadCost.compareTo(BigDecimal.ZERO) > 0) {
      lines.add(
          new JournalCreationRequest.LineRequest(
              overheadAppliedAccountId,
              BigDecimal.ZERO,
              overheadCost.abs(),
              "Overhead applied " + log.getProductionCode()));
    }

    Long primaryCreditAccount =
        laborCost.compareTo(BigDecimal.ZERO) > 0 ? laborAppliedAccountId : overheadAppliedAccountId;
    accountingFacade.createStandardJournal(
        new JournalCreationRequest(
            totalAmount,
            wipAccountId,
            primaryCreditAccount,
            "Labor/overhead applied for " + log.getProductionCode(),
            "FACTORY_PRODUCTION",
            log.getProductionCode() + "-LABOH",
            null,
            lines,
            resolveJournalDate(company, log),
            null,
            null,
            Boolean.FALSE));
  }

  private void linkRawMaterialMovementsToJournal(
      Company company, String referenceId, Long journalEntryId) {
    if (journalEntryId == null) {
      return;
    }
    List<RawMaterialMovement> movements =
        rawMaterialMovementRepository.findByRawMaterialCompanyAndReferenceTypeAndReferenceId(
            company, InventoryReference.PRODUCTION_LOG, referenceId);
    if (movements.isEmpty()) {
      return;
    }
    movements.forEach(movement -> movement.setJournalEntryId(journalEntryId));
    rawMaterialMovementRepository.saveAll(movements);
  }

  private LocalDate resolveJournalDate(Company company, ProductionLog log) {
    ZoneId zoneId =
        Optional.ofNullable(company.getTimezone())
            .filter(StringUtils::hasText)
            .map(ZoneId::of)
            .orElse(ZoneOffset.UTC);
    return log.getProducedAt().atZone(zoneId).toLocalDate();
  }

  private Long requireWipAccountId(ProductionProduct product) {
    Long accountId = metadataLong(product, "wipAccountId");
    if (accountId == null) {
      throw new ApplicationException(
          ErrorCode.VALIDATION_INVALID_REFERENCE,
          "Product " + product.getProductName() + " missing wipAccountId metadata");
    }
    return accountId;
  }

  private Long requireLaborAppliedAccountId(ProductionProduct product) {
    Long accountId = metadataLong(product, "laborAppliedAccountId");
    if (accountId == null) {
      throw new ApplicationException(
          ErrorCode.VALIDATION_INVALID_REFERENCE,
          "Product " + product.getProductName() + " missing laborAppliedAccountId metadata");
    }
    return accountId;
  }

  private Long requireOverheadAppliedAccountId(ProductionProduct product) {
    Long accountId = metadataLong(product, "overheadAppliedAccountId");
    if (accountId == null) {
      throw new ApplicationException(
          ErrorCode.VALIDATION_INVALID_REFERENCE,
          "Product " + product.getProductName() + " missing overheadAppliedAccountId metadata");
    }
    return accountId;
  }

  private Long metadataLong(ProductionProduct product, String key) {
    if (product.getMetadata() == null) {
      return null;
    }
    Object candidate = product.getMetadata().get(key);
    if (candidate instanceof Number number) {
      return number.longValue();
    }
    if (candidate instanceof String str && StringUtils.hasText(str)) {
      try {
        return Long.parseLong(str.trim());
      } catch (NumberFormatException ignored) {
        return null;
      }
    }
    return null;
  }

  private BigDecimal calculateUnitCost(BigDecimal total, BigDecimal quantity) {
    return MoneyUtils.safeDivide(total, quantity, 6, COST_ROUNDING);
  }

  private record MaterialIssueSummary(BigDecimal totalCost, Map<Long, BigDecimal> accountTotals) {}

  private record MaterialConsumption(
      List<ProductionLogMaterial> materials, BigDecimal totalCost, Long inventoryAccountId) {}

  private record BatchIssue(
      RawMaterialBatch batch,
      BigDecimal quantity,
      BigDecimal unitCost,
      BigDecimal totalCost,
      RawMaterialMovement movement) {}

  private String nextProductionCode(Company company) {
    LocalDate today = companyClock.today(company);
    String prefix = "PROD-" + CODE_DATE.format(today);
    return logRepository
        .findTopByCompanyAndProductionCodeStartingWithOrderByProductionCodeDesc(company, prefix)
        .map(ProductionLog::getProductionCode)
        .map(existing -> incrementCode(existing, prefix))
        .orElse(prefix + "-001");
  }

  private String incrementCode(String existing, String prefix) {
    try {
      String[] parts = existing.split("-");
      int seq = Integer.parseInt(parts[parts.length - 1]);
      return prefix + "-" + String.format("%03d", seq + 1);
    } catch (Exception ex) {
      throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
          "Invalid production code format: " + existing, ex);
    }
  }

  private Instant resolveProducedAt(Company company, String producedAt) {
    if (!StringUtils.hasText(producedAt)) {
      return CompanyTime.now(company);
    }
    // Accept common UI formats: ISO_OFFSET_DATE_TIME, ISO_INSTANT, yyyy-MM-dd, dd-MM-yyyy
    // HH:mm[:ss]
    ZoneId zoneId = companyClock.zoneId(company);
    try {
      return OffsetDateTime.parse(producedAt).toInstant();
    } catch (Exception ignored) {
      // fall through
    }
    try {
      return Instant.parse(producedAt);
    } catch (Exception ignored) {
      // fall through to final attempt
    }
    try {
      DateTimeFormatter fmt = DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm");
      return java.time.LocalDateTime.parse(producedAt, fmt).atZone(zoneId).toInstant();
    } catch (Exception ignored) {
      // fall through
    }
    try {
      DateTimeFormatter fmtSeconds = DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm:ss");
      return java.time.LocalDateTime.parse(producedAt, fmtSeconds).atZone(zoneId).toInstant();
    } catch (Exception ignored) {
      // fall through to final attempt
    }
    try {
      return LocalDate.parse(producedAt).atStartOfDay(zoneId).toInstant();
    } catch (Exception ex) {
      throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
          "Invalid producedAt format: " + producedAt, ex);
    }
  }

  private ProductionLogDto toDto(ProductionLog log) {
    String brandName = log.getBrand() != null ? log.getBrand().getName() : null;
    ProductionProduct product = log.getProduct();
    String productName = product != null ? product.getProductName() : null;
    String skuCode = product != null ? product.getSkuCode() : null;
    String status = log.getStatus() != null ? log.getStatus().name() : null;
    return new ProductionLogDto(
        log.getId(),
        log.getPublicId(),
        log.getProductionCode(),
        log.getProducedAt(),
        brandName,
        productName,
        skuCode,
        log.getBatchColour(),
        log.getBatchSize(),
        log.getUnitOfMeasure(),
        log.getMixedQuantity(),
        log.getProductionCode(),
        log.getMixedQuantity(),
        log.getTotalPackedQuantity(),
        log.getWastageQuantity(),
        log.getWastageReasonCode(),
        status,
        log.getCreatedBy(),
        log.getUnitCost(),
        log.getMaterialCostTotal(),
        log.getLaborCostTotal(),
        log.getOverheadCostTotal(),
        log.getSalesOrderId(),
        log.getSalesOrderNumber());
  }

  private ProductionLogDetailDto toDetailDto(ProductionLog log) {
    String brandName = log.getBrand() != null ? log.getBrand().getName() : null;
    ProductionProduct product = log.getProduct();
    String productName = product != null ? product.getProductName() : null;
    String skuCode = product != null ? product.getSkuCode() : null;
    String status = log.getStatus() != null ? log.getStatus().name() : null;
    List<ProductionLogMaterialDto> materials =
        log.getMaterials().stream()
            .map(
                material ->
                    new ProductionLogMaterialDto(
                        material.getRawMaterial() != null
                            ? material.getRawMaterial().getId()
                            : null,
                        material.getRawMaterialBatch() != null
                            ? material.getRawMaterialBatch().getId()
                            : null,
                        material.getRawMaterialBatch() != null
                            ? material.getRawMaterialBatch().getBatchCode()
                            : null,
                        material.getRawMaterialMovementId(),
                        material.getMaterialName(),
                        material.getQuantity(),
                        material.getUnitOfMeasure(),
                        material.getCostPerUnit(),
                        material.getTotalCost()))
            .toList();
    List<ProductionLogPackingRecordDto> packingRecords =
        log.getPackingRecords().stream()
            .map(
                record ->
                    new ProductionLogPackingRecordDto(
                        record.getId(),
                        record.getSizeVariant() != null ? record.getSizeVariant().getId() : null,
                        record.getSizeVariant() != null
                            ? record.getSizeVariant().getSizeLabel()
                            : null,
                        record.getChildBatchCount() != null
                            ? record.getChildBatchCount().longValue()
                            : null,
                        record.getFinishedGood() != null ? record.getFinishedGood().getId() : null,
                        record.getFinishedGood() != null
                            ? record.getFinishedGood().getProductCode()
                            : null,
                        record.getFinishedGood() != null
                            ? record.getFinishedGood().getName()
                            : null,
                        record.getFinishedGoodBatch() != null
                            ? record.getFinishedGoodBatch().getId()
                            : null,
                        record.getFinishedGoodBatch() != null
                            ? record.getFinishedGoodBatch().getPublicId()
                            : null,
                        record.getFinishedGoodBatch() != null
                            ? record.getFinishedGoodBatch().getBatchCode()
                            : null,
                        record.getPackagingSize(),
                        record.getQuantityPacked(),
                        record.getPackedDate(),
                        record.getPackedBy()))
            .toList();
    List<AllowedSellableSizeDto> allowedSellableSizes =
        packingAllowedSizeService.listAllowedSellableSizes(log.getCompany(), log);
    String productFamilyName =
        log.getProduct() != null ? log.getProduct().getProductFamilyName() : null;
    return new ProductionLogDetailDto(
        log.getId(),
        log.getPublicId(),
        log.getProductionCode(),
        log.getProducedAt(),
        brandName,
        productName,
        skuCode,
        log.getBatchColour(),
        log.getBatchSize(),
        log.getUnitOfMeasure(),
        log.getMixedQuantity(),
        log.getProductionCode(),
        log.getMixedQuantity(),
        log.getTotalPackedQuantity(),
        log.getWastageQuantity(),
        log.getWastageReasonCode(),
        status,
        log.getMaterialCostTotal(),
        log.getLaborCostTotal(),
        log.getOverheadCostTotal(),
        log.getUnitCost(),
        log.getSalesOrderId(),
        log.getSalesOrderNumber(),
        log.getNotes(),
        log.getCreatedBy(),
        materials,
        packingRecords,
        productFamilyName,
        allowedSellableSizes);
  }

  private BigDecimal positive(BigDecimal value, String field) {
    if (value == null || value.compareTo(BigDecimal.ZERO) <= 0) {
      throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
          field + " must be positive");
    }
    return value;
  }

  private BigDecimal nonNegative(BigDecimal value) {
    if (value == null || value.compareTo(BigDecimal.ZERO) <= 0) {
      return BigDecimal.ZERO;
    }
    return value;
  }

  private String clean(String value) {
    return StringUtils.hasText(value) ? value.trim() : null;
  }
}
