package com.bigbrightpaints.erp.e2e.production;

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
import com.bigbrightpaints.erp.modules.sales.domain.Dealer;
import com.bigbrightpaints.erp.modules.sales.domain.DealerRepository;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrder;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrderItem;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrderRepository;
import com.bigbrightpaints.erp.modules.sales.dto.DispatchConfirmRequest;
import com.bigbrightpaints.erp.modules.sales.service.SalesService;
import com.bigbrightpaints.erp.test.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("E2E: Factory packaging costing + dispatch")
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
    @Autowired private ProductionLogService productionLogService;
    @Autowired private PackingService packingService;
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
        CompanyContextHolder.setCompanyId(COMPANY_CODE);
        wip = ensureAccount("WIP", AccountType.ASSET);
        rmInventory = ensureAccount("INV-RM", AccountType.ASSET);
        packagingInventory = ensureAccount("INV-PACK", AccountType.ASSET);
        fgInventory = ensureAccount("INV-FG", AccountType.ASSET);
        cogs = ensureAccount("COGS", AccountType.COGS);
        revenue = ensureAccount("REV", AccountType.REVENUE);
        tax = ensureAccount("TAX", AccountType.LIABILITY);
        receivable = ensureAccount("AR", AccountType.ASSET);

        // Set company defaults so sales/dispatch flows can post
        companyDefaultAccountsService.updateDefaults(fgInventory.getId(), cogs.getId(), revenue.getId(), null, tax.getId());
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

        RawMaterial bucket = createRawMaterial("RM-BUCKET", "Bucket 20L", packagingInventory, new BigDecimal("10"));
        addBatch(bucket, new BigDecimal("10"), new BigDecimal("50")); // 10 buckets @ 50 each
        mapPackagingSize("20L", bucket);

        // STEP 1: Mixing (consume 55 units @100 -> 5,500 production cost over 100L)
        ProductionLogDetailDto log = productionLogService.createLog(new ProductionLogRequest(
                brand.getId(),
                product.getId(),
                "WHITE",
                new BigDecimal("100"),
                "L",
                new BigDecimal("100"),
                LocalDate.now().toString(),
                "WE-2025-0001",
                "Supervisor",
                true,
                null,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                List.of(new ProductionLogRequest.MaterialUsageRequest(
                        base.getId(),
                        new BigDecimal("55"),
                        "KG"
                ))
        ));
        String productionCode = log.productionCode();

        // STEP 2: Packing 5×20L buckets (100L) with packaging cost 5*50=250
        packingService.recordPacking(new PackingRequest(
                log.id(),
                LocalDate.now(),
                "Packer",
                List.of(new PackingLineRequest("20L", new BigDecimal("100"), 5, null, null))
        ));

        FinishedGood fg = finishedGoodRepository.findByCompanyAndProductCode(company, product.getSkuCode()).orElseThrow();
        FinishedGoodBatch fgBatch = finishedGoodBatchRepository.findAll().stream()
                .filter(b -> b.getFinishedGood().getId().equals(fg.getId()))
                .findFirst()
                .orElseThrow();

        // STEP 3: Create order + reservation + slip, then dispatch 2 buckets (40L)
        Dealer dealer = createDealer("DEALER-1");
        SalesOrder order = createOrder(dealer, product.getSkuCode(), new BigDecimal("40"), new BigDecimal("1500"));
        PackagingSlip slip = createSlip(order, fgBatch, new BigDecimal("40"), fgBatch.getUnitCost());
        createReservation(fg, fgBatch, order.getId(), new BigDecimal("40"));

        salesService.confirmDispatch(new DispatchConfirmRequest(
                slip.getId(),
                order.getId(),
                null,
                null,
                null,
                false,
                null
        ));

        // Inventory assertions
        assertThat(rawMaterialRepository.findById(base.getId()).orElseThrow().getCurrentStock())
                .isEqualByComparingTo(new BigDecimal("145")); // 200 - 55
        assertThat(rawMaterialRepository.findById(bucket.getId()).orElseThrow().getCurrentStock())
                .isEqualByComparingTo(new BigDecimal("5")); // 10 - 5 buckets
        assertThat(finishedGoodRepository.findById(fg.getId()).orElseThrow().getCurrentStock())
                .isEqualByComparingTo(new BigDecimal("60")); // 100L produced - 40L shipped

        BigDecimal expectedUnitCost = fgBatch.getUnitCost(); // 55 production + 2.5 packaging = 57.5 per liter
        assertThat(expectedUnitCost).isEqualByComparingTo(new BigDecimal("57.5000"));

        JournalEntry rmJournal = requireJournal(company, productionCode + "-RM");
        assertBalanced(rmJournal);
        assertLineAmount(rmJournal, wip.getId(), new BigDecimal("5500.00"), false);
        assertLineAmount(rmJournal, rmInventory.getId(), new BigDecimal("5500.00"), true);

        JournalEntry semiFinishedJournal = requireJournal(company, productionCode + "-SEMIFG");
        assertBalanced(semiFinishedJournal);
        assertLineAmount(semiFinishedJournal, fgInventory.getId(), new BigDecimal("5500.00"), false);
        assertLineAmount(semiFinishedJournal, wip.getId(), new BigDecimal("5500.00"), true);

        JournalEntry packagingJournal = requireJournal(company, productionCode + "-PACK-1-PACKMAT");
        assertBalanced(packagingJournal);
        assertLineAmount(packagingJournal, wip.getId(), new BigDecimal("250.00"), false);
        assertLineAmount(packagingJournal, packagingInventory.getId(), new BigDecimal("250.00"), true);

        List<JournalEntry> packingRelated = journalEntryRepository
                .findByCompanyAndReferenceNumberStartingWith(company, productionCode + "-PACK-");
        JournalEntry packingSessionJournal = packingRelated.stream()
                .filter(entry -> entry.getReferenceNumber() != null && !entry.getReferenceNumber().endsWith("-PACKMAT"))
                .findFirst()
                .map(entry -> journalEntryRepository.findById(entry.getId()).orElseThrow())
                .orElseThrow();
        assertBalanced(packingSessionJournal);
        assertLineAmount(packingSessionJournal, fgInventory.getId(), new BigDecimal("5750.00"), false);
        assertLineAmount(packingSessionJournal, fgInventory.getId(), new BigDecimal("5500.00"), true);
        assertLineAmount(packingSessionJournal, wip.getId(), new BigDecimal("250.00"), true);

        List<RawMaterialMovement> rmMovements = rawMaterialMovementRepository
                .findByReferenceTypeAndReferenceId(InventoryReference.PRODUCTION_LOG, productionCode);
        assertThat(rmMovements).isNotEmpty();
        assertThat(rmMovements).allMatch(movement -> rmJournal.getId().equals(movement.getJournalEntryId()));
        System.out.println("M3 evidence rm movements: " + rmMovements.stream()
                .map(movement -> "id=" + movement.getId()
                        + ",ref=" + movement.getReferenceId()
                        + ",journal=" + movement.getJournalEntryId())
                .toList());

        String packagingReference = productionCode + "-PACK-1";
        List<RawMaterialMovement> packagingMovements = rawMaterialMovementRepository
                .findByReferenceTypeAndReferenceId(InventoryReference.PACKING_RECORD, packagingReference);
        assertThat(packagingMovements).isNotEmpty();
        assertThat(packagingMovements).allMatch(movement -> packagingJournal.getId().equals(movement.getJournalEntryId()));
        System.out.println("M3 evidence packaging movements: " + packagingMovements.stream()
                .map(movement -> "id=" + movement.getId()
                        + ",ref=" + movement.getReferenceId()
                        + ",journal=" + movement.getJournalEntryId())
                .toList());

        List<InventoryMovement> movements = inventoryMovementRepository
                .findByReferenceTypeAndReferenceIdOrderByCreatedAtAsc(InventoryReference.PRODUCTION_LOG, productionCode);
        assertThat(movements).isNotEmpty();
        System.out.println("M3 evidence inventory movements: " + movements.stream()
                .map(movement -> "id=" + movement.getId()
                        + ",type=" + movement.getMovementType()
                        + ",ref=" + movement.getReferenceId()
                        + ",journal=" + movement.getJournalEntryId())
                .toList());
        assertThat(movements).anyMatch(movement ->
                "RECEIPT".equals(movement.getMovementType())
                        && semiFinishedJournal.getId().equals(movement.getJournalEntryId())
                        && movement.getUnitCost().compareTo(log.unitCost()) == 0);
        assertThat(movements).anyMatch(movement ->
                "ISSUE".equals(movement.getMovementType())
                        && packingSessionJournal.getId().equals(movement.getJournalEntryId()));
        assertThat(movements).anyMatch(movement ->
                "RECEIPT".equals(movement.getMovementType())
                        && packingSessionJournal.getId().equals(movement.getJournalEntryId())
                        && movement.getUnitCost().compareTo(expectedUnitCost) == 0);
    }

    private Account ensureAccount(String code, AccountType type) {
        return accountRepository.findByCompanyAndCodeIgnoreCase(company, code)
                .orElseGet(() -> {
                    Account a = new Account();
                    a.setCompany(company);
                    a.setCode(code);
                    a.setName(code);
                    a.setType(type);
                    return accountRepository.save(a);
                });
    }

    private ProductionBrand createBrand(String code) {
        return productionBrandRepository.findByCompanyAndCodeIgnoreCase(company, code)
                .orElseGet(() -> {
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

    private RawMaterial createRawMaterial(String sku, String name, Account inventoryAccount, BigDecimal currentStock) {
        RawMaterial rm = new RawMaterial();
        rm.setCompany(company);
        rm.setSku(sku);
        rm.setName(name);
        rm.setUnitType("KG");
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

    private SalesOrder createOrder(Dealer dealer, String productCode, BigDecimal qty, BigDecimal price) {
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

    private PackagingSlip createSlip(SalesOrder order, FinishedGoodBatch batch, BigDecimal qty, BigDecimal unitCost) {
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

    private void createReservation(FinishedGood fg, FinishedGoodBatch batch, Long orderId, BigDecimal qty) {
        InventoryReservation reservation = new InventoryReservation();
        reservation.setFinishedGood(fg);
        reservation.setFinishedGoodBatch(batch);
        reservation.setReferenceType(InventoryReference.SALES_ORDER);
        reservation.setReferenceId(orderId.toString());
        reservation.setQuantity(qty);
        reservation.setReservedQuantity(qty);
        reservation.setStatus("RESERVED");
        inventoryReservationRepository.save(reservation);
    }

    private JournalEntry requireJournal(Company company, String reference) {
        JournalEntry entry = journalEntryRepository.findByCompanyAndReferenceNumber(company, reference)
                .orElseThrow();
        return journalEntryRepository.findById(entry.getId()).orElseThrow();
    }

    private void assertBalanced(JournalEntry entry) {
        BigDecimal debit = entry.getLines().stream()
                .map(l -> Optional.ofNullable(l.getDebit()).orElse(BigDecimal.ZERO))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal credit = entry.getLines().stream()
                .map(l -> Optional.ofNullable(l.getCredit()).orElse(BigDecimal.ZERO))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(debit).as("Journal %s balanced", entry.getReferenceNumber())
                .isEqualByComparingTo(credit);
    }

    private void assertLineAmount(JournalEntry entry, Long accountId, BigDecimal expected, boolean credit) {
        BigDecimal actual = entry.getLines().stream()
                .filter(l -> l.getAccount().getId().equals(accountId))
                .map(l -> credit ? l.getCredit() : l.getDebit())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(actual).isEqualByComparingTo(expected);
    }
}
