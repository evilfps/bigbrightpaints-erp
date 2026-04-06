package com.bigbrightpaints.erp.e2e.production;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.bigbrightpaints.erp.core.security.CompanyContextHolder;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountType;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryRepository;
import com.bigbrightpaints.erp.modules.accounting.service.CompanyDefaultAccountsService;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.modules.factory.domain.PackagingSizeMapping;
import com.bigbrightpaints.erp.modules.factory.domain.PackagingSizeMappingRepository;
import com.bigbrightpaints.erp.modules.factory.domain.PackingRecord;
import com.bigbrightpaints.erp.modules.factory.domain.PackingRecordRepository;
import com.bigbrightpaints.erp.modules.factory.domain.ProductionLog;
import com.bigbrightpaints.erp.modules.factory.domain.ProductionLogRepository;
import com.bigbrightpaints.erp.modules.factory.domain.ProductionLogStatus;
import com.bigbrightpaints.erp.modules.factory.domain.SizeVariant;
import com.bigbrightpaints.erp.modules.factory.domain.SizeVariantRepository;
import com.bigbrightpaints.erp.modules.factory.dto.PackingLineRequest;
import com.bigbrightpaints.erp.modules.factory.dto.PackingRequest;
import com.bigbrightpaints.erp.modules.factory.dto.ProductionLogDetailDto;
import com.bigbrightpaints.erp.modules.factory.dto.ProductionLogRequest;
import com.bigbrightpaints.erp.modules.factory.service.PackingService;
import com.bigbrightpaints.erp.modules.factory.service.ProductionLogService;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGood;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodBatch;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodBatchRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryMovement;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryMovementRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryReference;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryReservation;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryReservationRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.MaterialType;
import com.bigbrightpaints.erp.modules.inventory.domain.PackagingSlip;
import com.bigbrightpaints.erp.modules.inventory.domain.PackagingSlipLine;
import com.bigbrightpaints.erp.modules.inventory.domain.PackagingSlipLineRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.PackagingSlipRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterial;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialBatch;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialBatchRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialMovement;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialMovementRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialRepository;
import com.bigbrightpaints.erp.modules.production.domain.ProductionBrand;
import com.bigbrightpaints.erp.modules.production.domain.ProductionBrandRepository;
import com.bigbrightpaints.erp.modules.production.domain.ProductionProduct;
import com.bigbrightpaints.erp.modules.production.domain.ProductionProductRepository;
import com.bigbrightpaints.erp.modules.reports.dto.InventoryValuationDto;
import com.bigbrightpaints.erp.modules.reports.dto.InventoryValuationItemDto;
import com.bigbrightpaints.erp.modules.reports.dto.ProfitLossDto;
import com.bigbrightpaints.erp.modules.reports.service.ReportQueryRequestBuilder;
import com.bigbrightpaints.erp.modules.reports.service.ReportService;
import com.bigbrightpaints.erp.modules.sales.domain.Dealer;
import com.bigbrightpaints.erp.modules.sales.domain.DealerRepository;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrder;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrderItem;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrderRepository;
import com.bigbrightpaints.erp.modules.sales.dto.DispatchConfirmRequest;
import com.bigbrightpaints.erp.modules.sales.service.SalesService;
import com.bigbrightpaints.erp.test.AbstractIntegrationTest;

@DisplayName("E2E: Factory packaging costing + dispatch")
@Tag("critical")
public class FactoryPackagingCostingIT extends AbstractIntegrationTest {

  private static final String COMPANY_CODE = "WE-E2E";

  @Autowired private CompanyRepository companyRepository;
  @Autowired private AccountRepository accountRepository;
  @Autowired private CompanyDefaultAccountsService companyDefaultAccountsService;
  @Autowired private ProductionBrandRepository productionBrandRepository;
  @Autowired private ProductionProductRepository productionProductRepository;
  @Autowired private RawMaterialRepository rawMaterialRepository;
  @Autowired private RawMaterialBatchRepository rawMaterialBatchRepository;
  @Autowired private PackagingSizeMappingRepository packagingSizeMappingRepository;
  @Autowired private SizeVariantRepository sizeVariantRepository;
  @Autowired private ProductionLogRepository productionLogRepository;
  @Autowired private ProductionLogService productionLogService;
  @Autowired private PackingService packingService;
  @Autowired private PackingRecordRepository packingRecordRepository;
  @Autowired private FinishedGoodRepository finishedGoodRepository;
  @Autowired private FinishedGoodBatchRepository finishedGoodBatchRepository;
  @Autowired private InventoryMovementRepository inventoryMovementRepository;
  @Autowired private InventoryReservationRepository inventoryReservationRepository;
  @Autowired private PackagingSlipRepository packagingSlipRepository;
  @Autowired private PackagingSlipLineRepository packagingSlipLineRepository;
  @Autowired private RawMaterialMovementRepository rawMaterialMovementRepository;
  @Autowired private DealerRepository dealerRepository;
  @Autowired private SalesOrderRepository salesOrderRepository;
  @Autowired private SalesService salesService;
  @Autowired private JournalEntryRepository journalEntryRepository;
  @Autowired private ReportService reportService;

  private Company company;
  private Account wip;
  private Account rmInventory;
  private Account packagingInventory;
  private Account fgInventory;
  private Account cogs;
  private Account revenue;
  private Account tax;
  private Account receivable;

  @BeforeEach
  void init() {
    company = dataSeeder.ensureCompany(COMPANY_CODE, COMPANY_CODE + " Ltd");
    CompanyContextHolder.setCompanyCode(COMPANY_CODE);
    wip = ensureAccount("WIP", AccountType.ASSET);
    rmInventory = ensureAccount("INV-RM", AccountType.ASSET);
    packagingInventory = ensureAccount("INV-PACK", AccountType.ASSET);
    fgInventory = ensureAccount("INV-FG", AccountType.ASSET);
    cogs = ensureAccount("COGS", AccountType.COGS);
    revenue = ensureAccount("REV", AccountType.REVENUE);
    tax = ensureAccount("TAX", AccountType.LIABILITY);
    receivable = ensureAccount("AR", AccountType.ASSET);

    // Set company defaults so sales/dispatch flows can post
    companyDefaultAccountsService.updateDefaults(
        fgInventory.getId(), cogs.getId(), revenue.getId(), null, null, tax.getId());
  }

  @AfterEach
  void cleanupContext() {
    CompanyContextHolder.clear();
  }

  @Test
  @DisplayName("WE-2025-0001: RM→WIP, packaging→WIP, FG receipt, dispatch COGS at batch cost")
  void endToEndPackagingAndDispatchCosting() {
    ProductionBrand brand = createBrand("WE-BRAND");
    ProductionProduct product = createProduct("WE-PROD-20L", "White Emulsion", brand);

    RawMaterial base = createRawMaterial("RM-BASE", "Base", rmInventory, new BigDecimal("200"));
    addBatch(base, new BigDecimal("100"), new BigDecimal("100")); // cost per unit 100, qty 100

    RawMaterial bucket =
        createRawMaterial("RM-BUCKET", "Bucket 20L", packagingInventory, new BigDecimal("10"));
    addBatch(bucket, new BigDecimal("10"), new BigDecimal("50")); // 10 buckets @ 50 each
    mapPackagingSize("20L", bucket);
    FinishedGood packTarget = ensurePackTarget(product, "20L");

    // STEP 1: Mixing (consume 55 units @100 -> 5,500 production cost over 100L)
    ProductionLogDetailDto log =
        productionLogService.createLog(
            new ProductionLogRequest(
                brand.getId(),
                product.getId(),
                "WHITE",
                new BigDecimal("100"),
                "L",
                new BigDecimal("100"),
                LocalDate.now().toString(),
                "WE-2025-0001",
                "Supervisor",
                null,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                List.of(
                    new ProductionLogRequest.MaterialUsageRequest(
                        base.getId(), new BigDecimal("55"), "KG"))));
    String productionCode = log.productionCode();

    // STEP 2: Packing 5×20L buckets (100L) with packaging cost 5*50=250
    packingService.recordPacking(
        new PackingRequest(
            log.id(),
            LocalDate.now(),
            "Packer",
            List.of(
                new PackingLineRequest(
                    packTarget.getId(), null, "20L", new BigDecimal("100"), 5, null, null))));

    FinishedGood fg =
        finishedGoodRepository
            .findByCompanyAndProductCode(company, product.getSkuCode())
            .orElseThrow();
    Long finishedGoodId = fg.getId();
    FinishedGoodBatch fgBatch =
        finishedGoodBatchRepository.findAll().stream()
            .filter(b -> b.getFinishedGood().getId().equals(finishedGoodId))
            .findFirst()
            .orElseThrow();
    FinishedGoodBatch higherCostBatch =
        addFinishedGoodBatch(
            fg,
            productionCode + "-ALT",
            new BigDecimal("20"),
            new BigDecimal("90.0000"),
            log.producedAt().plusSeconds(3600));
    FinishedGood refreshedFinishedGood =
        finishedGoodRepository.findById(finishedGoodId).orElseThrow();

    // STEP 3: Create order + reservation + slip, then dispatch 2 buckets (40L)
    Dealer dealer = createDealer("DEALER-1");
    SalesOrder order =
        createOrder(dealer, product.getSkuCode(), new BigDecimal("40"), new BigDecimal("1500"));
    PackagingSlip slip = createSlip(order, fgBatch, new BigDecimal("40"), fgBatch.getUnitCost());
    createReservation(refreshedFinishedGood, fgBatch, order.getId(), new BigDecimal("40"));

    salesService.confirmDispatch(
        new DispatchConfirmRequest(
            slip.getId(), order.getId(), null, null, null, false, null, null));

    // Inventory assertions
    assertThat(rawMaterialRepository.findById(base.getId()).orElseThrow().getCurrentStock())
        .isEqualByComparingTo(new BigDecimal("145")); // 200 - 55
    assertThat(rawMaterialRepository.findById(bucket.getId()).orElseThrow().getCurrentStock())
        .isEqualByComparingTo(new BigDecimal("5")); // 10 - 5 buckets
    assertThat(finishedGoodRepository.findById(finishedGoodId).orElseThrow().getCurrentStock())
        .isEqualByComparingTo(
            new BigDecimal("80")); // 100L produced + 20L higher-cost batch - 40L shipped
    assertThat(
            finishedGoodBatchRepository
                .findById(higherCostBatch.getId())
                .orElseThrow()
                .getQuantityAvailable())
        .isEqualByComparingTo(new BigDecimal("20"));

    BigDecimal expectedUnitCost =
        fgBatch.getUnitCost(); // 55 production + 2.5 packaging = 57.5 per liter
    assertThat(expectedUnitCost).isEqualByComparingTo(new BigDecimal("57.5000"));

    ProductionLog storedLog = productionLogRepository.findById(log.id()).orElseThrow();
    assertThat(storedLog.getStatus()).isEqualTo(ProductionLogStatus.FULLY_PACKED);
    assertThat(storedLog.getTotalPackedQuantity()).isEqualByComparingTo(new BigDecimal("100"));
    PackingRecord packingRecord =
        packingRecordRepository
            .findByCompanyAndProductionLogOrderByPackedDateAscIdAsc(company, storedLog)
            .stream()
            .findFirst()
            .orElseThrow();
    assertThat(packingRecord.getPackagingQuantity()).isEqualByComparingTo(new BigDecimal("5"));
    assertThat(packingRecord.getPackagingCost()).isEqualByComparingTo(new BigDecimal("250.00"));
    assertThat(packingRecord.getFinishedGoodBatch()).isNotNull();
    assertThat(packingRecord.getFinishedGoodBatch().getId()).isEqualTo(fgBatch.getId());

    assertThat(fgBatch.getQuantityTotal()).isEqualByComparingTo(new BigDecimal("100"));
    assertThat(fgBatch.getUnitCost()).isEqualByComparingTo(new BigDecimal("57.5000"));
    String packagingReference = productionCode + "-PACK-" + packingRecord.getId();

    JournalEntry rmJournal = requireJournal(company, productionCode + "-RM");
    assertBalanced(rmJournal);
    assertLineAmount(rmJournal, wip.getId(), new BigDecimal("5500.00"), false);
    assertLineAmount(rmJournal, rmInventory.getId(), new BigDecimal("5500.00"), true);

    JournalEntry semiFinishedJournal = requireJournal(company, productionCode + "-SEMIFG");
    assertBalanced(semiFinishedJournal);
    assertLineAmount(semiFinishedJournal, fgInventory.getId(), new BigDecimal("5500.00"), false);
    assertLineAmount(semiFinishedJournal, wip.getId(), new BigDecimal("5500.00"), true);

    JournalEntry packagingJournal = requireJournal(company, packagingReference + "-PACKMAT");
    assertBalanced(packagingJournal);
    assertLineAmount(packagingJournal, wip.getId(), new BigDecimal("250.00"), false);
    assertLineAmount(packagingJournal, packagingInventory.getId(), new BigDecimal("250.00"), true);

    List<JournalEntry> packingRelated =
        journalEntryRepository.findByCompanyAndReferenceNumberStartingWith(
            company, productionCode + "-PACK-");
    JournalEntry packingSessionJournal =
        packingRelated.stream()
            .filter(
                entry ->
                    entry.getReferenceNumber() != null
                        && !entry.getReferenceNumber().endsWith("-PACKMAT"))
            .findFirst()
            .map(entry -> journalEntryRepository.findById(entry.getId()).orElseThrow())
            .orElseThrow();
    assertBalanced(packingSessionJournal);
    assertLineAmount(packingSessionJournal, fgInventory.getId(), new BigDecimal("5750.00"), false);
    assertLineAmount(packingSessionJournal, fgInventory.getId(), new BigDecimal("5500.00"), true);
    assertLineAmount(packingSessionJournal, wip.getId(), new BigDecimal("250.00"), true);

    List<RawMaterialMovement> rmMovements =
        rawMaterialMovementRepository.findByRawMaterialCompanyAndReferenceTypeAndReferenceId(
            company, InventoryReference.PRODUCTION_LOG, productionCode);
    assertThat(rmMovements).isNotEmpty();
    assertThat(rmMovements)
        .anyMatch(
            movement ->
                "ISSUE".equals(movement.getMovementType())
                    && rmJournal.getId().equals(movement.getJournalEntryId()));
    assertThat(rmMovements)
        .anyMatch(
            movement ->
                "RECEIPT".equals(movement.getMovementType())
                    && semiFinishedJournal.getId().equals(movement.getJournalEntryId())
                    && movement.getUnitCost().compareTo(log.unitCost()) == 0);

    List<RawMaterialMovement> packagingMovements =
        rawMaterialMovementRepository.findByRawMaterialCompanyAndReferenceTypeAndReferenceId(
            company, InventoryReference.PACKING_RECORD, packagingReference);
    assertThat(packagingMovements).isNotEmpty();
    RawMaterial semiFinishedMaterial =
        rawMaterialRepository
            .findByCompanyAndSkuIgnoreCase(company, product.getSkuCode() + "-BULK")
            .orElseThrow();
    assertThat(packagingMovements)
        .anyMatch(
            movement ->
                "ISSUE".equals(movement.getMovementType())
                    && movement.getRawMaterial() != null
                    && bucket.getId().equals(movement.getRawMaterial().getId())
                    && packagingJournal.getId().equals(movement.getJournalEntryId()));
    assertThat(packagingMovements)
        .anyMatch(
            movement ->
                "ISSUE".equals(movement.getMovementType())
                    && movement.getRawMaterial() != null
                    && semiFinishedMaterial.getId().equals(movement.getRawMaterial().getId())
                    && packingSessionJournal.getId().equals(movement.getJournalEntryId()));

    List<InventoryMovement> packingMovements =
        inventoryMovementRepository.findByReferenceTypeAndReferenceIdOrderByCreatedAtAsc(
            InventoryReference.PACKING_RECORD, packagingReference);
    assertThat(packingMovements).isNotEmpty();
    assertThat(packingMovements)
        .anyMatch(
            movement ->
                "RECEIPT".equals(movement.getMovementType())
                    && packingSessionJournal.getId().equals(movement.getJournalEntryId())
                    && movement.getUnitCost().compareTo(expectedUnitCost) == 0);

    InventoryValuationDto valuation = reportService.inventoryValuation();
    InventoryValuationItemDto fgValuation =
        valuation.items().stream()
            .filter(item -> product.getSkuCode().equals(item.code()))
            .findFirst()
            .orElseThrow();
    assertThat(fgValuation.quantityOnHand()).isEqualByComparingTo(new BigDecimal("80.00"));
    assertThat(fgValuation.totalValue()).isEqualByComparingTo(new BigDecimal("5250.00"));

    ProfitLossDto profitLoss = reportService.profitLoss(ReportQueryRequestBuilder.empty());
    assertThat(profitLoss.costOfGoodsSold()).isEqualByComparingTo(new BigDecimal("2300.00"));
    assertThat(profitLoss.grossProfit()).isEqualByComparingTo(new BigDecimal("57700.00"));
  }

  private Account ensureAccount(String code, AccountType type) {
    return accountRepository
        .findByCompanyAndCodeIgnoreCase(company, code)
        .orElseGet(
            () -> {
              Account a = new Account();
              a.setCompany(company);
              a.setCode(code);
              a.setName(code);
              a.setType(type);
              return accountRepository.save(a);
            });
  }

  private ProductionBrand createBrand(String code) {
    return productionBrandRepository
        .findByCompanyAndCodeIgnoreCase(company, code)
        .orElseGet(
            () -> {
              ProductionBrand b = new ProductionBrand();
              b.setCompany(company);
              b.setCode(code);
              b.setName(code);
              return productionBrandRepository.save(b);
            });
  }

  private ProductionProduct createProduct(String sku, String name, ProductionBrand brand) {
    ProductionProduct p = new ProductionProduct();
    p.setCompany(company);
    p.setBrand(brand);
    p.setSkuCode(sku);
    p.setProductName(name);
    p.setCategory("FINISHED_GOOD");
    p.setUnitOfMeasure("L");
    Map<String, Object> metadata = new HashMap<>();
    metadata.put("wipAccountId", wip.getId());
    metadata.put("semiFinishedAccountId", fgInventory.getId());
    metadata.put("fgValuationAccountId", fgInventory.getId());
    metadata.put("fgCogsAccountId", cogs.getId());
    metadata.put("fgRevenueAccountId", revenue.getId());
    metadata.put("fgDiscountAccountId", revenue.getId());
    metadata.put("fgTaxAccountId", tax.getId());
    p.setMetadata(metadata);
    return productionProductRepository.save(p);
  }

  private FinishedGood ensurePackTarget(ProductionProduct product, String sizeLabel) {
    SizeVariant variant =
        sizeVariantRepository
            .findByCompanyAndProductAndSizeLabelIgnoreCase(company, product, sizeLabel)
            .orElseGet(
                () -> {
                  SizeVariant created = new SizeVariant();
                  created.setCompany(company);
                  created.setProduct(product);
                  created.setSizeLabel(sizeLabel);
                  created.setCartonQuantity(1);
                  created.setLitersPerUnit(new BigDecimal(sizeLabel.replace("L", "")));
                  created.setActive(true);
                  return sizeVariantRepository.save(created);
                });
    variant.setActive(true);
    sizeVariantRepository.save(variant);

    return finishedGoodRepository
        .findByCompanyAndProductCode(company, product.getSkuCode())
        .orElseGet(
            () -> {
              FinishedGood finishedGood = new FinishedGood();
              finishedGood.setCompany(company);
              finishedGood.setProductCode(product.getSkuCode());
              finishedGood.setName(product.getProductName());
              finishedGood.setUnit("L");
              finishedGood.setValuationAccountId(fgInventory.getId());
              finishedGood.setCogsAccountId(cogs.getId());
              finishedGood.setRevenueAccountId(revenue.getId());
              finishedGood.setDiscountAccountId(revenue.getId());
              finishedGood.setTaxAccountId(tax.getId());
              return finishedGoodRepository.save(finishedGood);
            });
  }

  private RawMaterial createRawMaterial(
      String sku, String name, Account inventoryAccount, BigDecimal currentStock) {
    RawMaterial rm = new RawMaterial();
    rm.setCompany(company);
    rm.setSku(sku);
    rm.setName(name);
    rm.setUnitType("KG");
    if (sku.startsWith("RM-BUCKET")) {
      rm.setMaterialType(MaterialType.PACKAGING);
    } else {
      rm.setMaterialType(MaterialType.PRODUCTION);
    }
    rm.setInventoryAccountId(inventoryAccount.getId());
    rm.setCurrentStock(currentStock);
    return rawMaterialRepository.save(rm);
  }

  private void addBatch(RawMaterial rm, BigDecimal quantity, BigDecimal costPerUnit) {
    RawMaterialBatch batch = new RawMaterialBatch();
    batch.setRawMaterial(rm);
    batch.setBatchCode(rm.getSku() + "-B1");
    batch.setQuantity(quantity);
    batch.setUnit(Optional.ofNullable(rm.getUnitType()).orElse("UNIT"));
    batch.setCostPerUnit(costPerUnit);
    rawMaterialBatchRepository.save(batch);
  }

  private FinishedGoodBatch addFinishedGoodBatch(
      FinishedGood fg,
      String batchCode,
      BigDecimal quantity,
      BigDecimal unitCost,
      java.time.Instant manufacturedAt) {
    FinishedGoodBatch batch = new FinishedGoodBatch();
    batch.setFinishedGood(fg);
    batch.setBatchCode(batchCode);
    batch.setQuantityTotal(quantity);
    batch.setQuantityAvailable(quantity);
    batch.setUnitCost(unitCost);
    batch.setManufacturedAt(manufacturedAt);
    FinishedGoodBatch saved = finishedGoodBatchRepository.save(batch);

    fg.setCurrentStock(fg.getCurrentStock().add(quantity));
    finishedGoodRepository.save(fg);
    return saved;
  }

  private void mapPackagingSize(String size, RawMaterial bucket) {
    PackagingSizeMapping mapping = new PackagingSizeMapping();
    mapping.setCompany(company);
    mapping.setPackagingSize(size);
    mapping.setRawMaterial(bucket);
    mapping.setUnitsPerPack(1);
    mapping.setLitersPerUnit(new BigDecimal(size.replace("L", "")));
    mapping.setActive(true);
    packagingSizeMappingRepository.save(mapping);
  }

  private Dealer createDealer(String code) {
    Dealer dealer = new Dealer();
    dealer.setCompany(company);
    dealer.setCode(code);
    dealer.setName(code);
    dealer.setReceivableAccount(receivable);
    return dealerRepository.save(dealer);
  }

  private SalesOrder createOrder(
      Dealer dealer, String productCode, BigDecimal qty, BigDecimal price) {
    SalesOrder order = new SalesOrder();
    order.setCompany(company);
    order.setDealer(dealer);
    order.setOrderNumber("SO-1001");
    order.setStatus("CONFIRMED");
    order.setTotalAmount(price.multiply(qty));
    order.setSubtotalAmount(price.multiply(qty));
    order.setGstTreatment("NONE");
    SalesOrderItem item = new SalesOrderItem();
    item.setSalesOrder(order);
    item.setProductCode(productCode);
    item.setQuantity(qty);
    item.setUnitPrice(price);
    item.setLineSubtotal(price.multiply(qty));
    item.setLineTotal(price.multiply(qty));
    order.getItems().add(item);
    return salesOrderRepository.save(order);
  }

  private PackagingSlip createSlip(
      SalesOrder order, FinishedGoodBatch batch, BigDecimal qty, BigDecimal unitCost) {
    PackagingSlip slip = new PackagingSlip();
    slip.setCompany(company);
    slip.setSalesOrder(order);
    slip.setSlipNumber("SLIP-1001");
    slip.setStatus("PENDING");

    PackagingSlipLine line = new PackagingSlipLine();
    line.setPackagingSlip(slip);
    line.setFinishedGoodBatch(batch);
    line.setOrderedQuantity(qty);
    line.setQuantity(qty);
    line.setUnitCost(unitCost);
    slip.getLines().add(line);
    PackagingSlip saved = packagingSlipRepository.save(slip);
    line.setPackagingSlip(saved);
    packagingSlipLineRepository.save(line);
    return saved;
  }

  private void createReservation(
      FinishedGood fg, FinishedGoodBatch batch, Long orderId, BigDecimal qty) {
    InventoryReservation reservation = new InventoryReservation();
    reservation.setFinishedGood(fg);
    reservation.setFinishedGoodBatch(batch);
    reservation.setReferenceType(InventoryReference.SALES_ORDER);
    reservation.setReferenceId(orderId.toString());
    reservation.setQuantity(qty);
    reservation.setReservedQuantity(qty);
    reservation.setStatus("RESERVED");
    inventoryReservationRepository.save(reservation);

    BigDecimal reserved = fg.getReservedStock() != null ? fg.getReservedStock() : BigDecimal.ZERO;
    fg.setReservedStock(reserved.add(qty));
    finishedGoodRepository.save(fg);

    BigDecimal available =
        batch.getQuantityAvailable() != null ? batch.getQuantityAvailable() : BigDecimal.ZERO;
    batch.setQuantityAvailable(available.subtract(qty));
    finishedGoodBatchRepository.save(batch);
  }

  private JournalEntry requireJournal(Company company, String reference) {
    JournalEntry entry =
        journalEntryRepository.findByCompanyAndReferenceNumber(company, reference).orElseThrow();
    return journalEntryRepository.findById(entry.getId()).orElseThrow();
  }

  private void assertBalanced(JournalEntry entry) {
    BigDecimal debit =
        entry.getLines().stream()
            .map(l -> Optional.ofNullable(l.getDebit()).orElse(BigDecimal.ZERO))
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal credit =
        entry.getLines().stream()
            .map(l -> Optional.ofNullable(l.getCredit()).orElse(BigDecimal.ZERO))
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    assertThat(debit)
        .as("Journal %s balanced", entry.getReferenceNumber())
        .isEqualByComparingTo(credit);
  }

  private void assertLineAmount(
      JournalEntry entry, Long accountId, BigDecimal expected, boolean credit) {
    BigDecimal actual =
        entry.getLines().stream()
            .filter(l -> l.getAccount().getId().equals(accountId))
            .map(l -> credit ? l.getCredit() : l.getDebit())
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    assertThat(actual).isEqualByComparingTo(expected);
  }
}
