package com.bigbrightpaints.erp.modules.inventory.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bigbrightpaints.erp.core.security.CompanyContextHolder;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountType;
import com.bigbrightpaints.erp.modules.company.domain.Company;
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
import com.bigbrightpaints.erp.modules.inventory.domain.PackagingSlipRepository;
import com.bigbrightpaints.erp.modules.inventory.dto.RawMaterialRequest;
import com.bigbrightpaints.erp.modules.inventory.service.RawMaterialService;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrder;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrderRepository;
import com.bigbrightpaints.erp.modules.inventory.service.FinishedGoodsService.DispatchPosting;
import com.bigbrightpaints.erp.modules.sales.service.SalesService;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterial;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialRepository;
import com.bigbrightpaints.erp.test.AbstractIntegrationTest;
import com.bigbrightpaints.erp.test.TestDataSeeder;
import com.bigbrightpaints.erp.modules.inventory.dto.RawMaterialBatchRequest;
import com.bigbrightpaints.erp.modules.purchasing.domain.Supplier;
import com.bigbrightpaints.erp.modules.purchasing.domain.SupplierRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

@TestPropertySource(properties = "erp.raw-material.intake.enabled=true")
@Transactional
class AuditFixesIntegrationTest extends AbstractIntegrationTest {

    private static final String COMPANY_CODE = "DISP-CO";

    @Autowired
    private FinishedGoodsService finishedGoodsService;

    @Autowired
    private FinishedGoodRepository finishedGoodRepository;

    @Autowired
    private FinishedGoodBatchRepository finishedGoodBatchRepository;

    @Autowired
    private PackagingSlipRepository packagingSlipRepository;

    @Autowired
    private InventoryReservationRepository inventoryReservationRepository;

    @Autowired
    private InventoryMovementRepository inventoryMovementRepository;

    @Autowired
    private SalesOrderRepository salesOrderRepository;

    @Autowired
    private SalesService salesService;

    @Autowired
    private RawMaterialService rawMaterialService;

    @Autowired
    private RawMaterialRepository rawMaterialRepository;

    @Autowired
    private TestDataSeeder dataSeeder;

    @Autowired
    private SupplierRepository supplierRepository;

    @Autowired
    private AccountRepository accountRepository;

    @AfterEach
    void clearContext() {
        CompanyContextHolder.clear();
    }

    @Test
    void shouldUseSlipOverridesForDispatchAndCost() {
        Company company = dataSeeder.ensureCompany(COMPANY_CODE, "Dispatch Co");

        FinishedGood fg = new FinishedGood();
        fg.setCompany(company);
        fg.setProductCode("FG-001");
        fg.setName("Blue Paint");
        fg.setUnit("UNIT");
        fg.setCostingMethod("FIFO");
        fg.setCurrentStock(new BigDecimal("10"));
        fg.setReservedStock(new BigDecimal("10"));
        fg.setValuationAccountId(100L);
        fg.setCogsAccountId(200L);
        fg = finishedGoodRepository.saveAndFlush(fg);

        FinishedGoodBatch batch = new FinishedGoodBatch();
        batch.setFinishedGood(fg);
        batch.setBatchCode("BATCH-1");
        batch.setQuantityTotal(new BigDecimal("10"));
        batch.setQuantityAvailable(new BigDecimal("10"));
        batch.setUnitCost(new BigDecimal("5"));
        batch.setManufacturedAt(Instant.now());
        batch = finishedGoodBatchRepository.saveAndFlush(batch);

        String uniqueId = UUID.randomUUID().toString().substring(0, 8);
        SalesOrder order = dataSeeder.ensureSalesOrder(company.getCode(), "SO-DISP-" + uniqueId, new BigDecimal("100"));

        PackagingSlip slip = new PackagingSlip();
        slip.setCompany(company);
        slip.setSalesOrder(order);
        slip.setSlipNumber("PS-" + uniqueId); // Unique slip number
        slip.setStatus("PENDING");
        // createdAt is auto-set by @PrePersist
        
        PackagingSlipLine line = new PackagingSlipLine();
        line.setPackagingSlip(slip);
        line.setFinishedGoodBatch(batch);
        line.setOrderedQuantity(new BigDecimal("10"));
        line.setQuantity(new BigDecimal("3")); // override to ship only 3 units
        line.setUnitCost(batch.getUnitCost());
        slip.getLines().add(line);
        slip = packagingSlipRepository.saveAndFlush(slip);

        InventoryReservation reservation = new InventoryReservation();
        reservation.setFinishedGood(fg);
        reservation.setFinishedGoodBatch(batch);
        reservation.setReferenceType(InventoryReference.SALES_ORDER);
        reservation.setReferenceId(order.getId().toString());
        reservation.setQuantity(new BigDecimal("10"));
        reservation.setReservedQuantity(new BigDecimal("10"));
        reservation.setStatus("RESERVED");
        inventoryReservationRepository.save(reservation);

        List<DispatchPosting> postings = finishedGoodsService.markSlipDispatched(order.getId(), slip);

        assertEquals(1, postings.size());
        assertEquals(0, postings.get(0).cost().compareTo(new BigDecimal("15")));

        PackagingSlip refreshedSlip = packagingSlipRepository.findByIdAndCompany(slip.getId(), company)
                .orElseThrow();
        assertEquals("DISPATCHED", refreshedSlip.getStatus());
        assertTrue(refreshedSlip.getDispatchedAt() != null);

        FinishedGood refreshedFg = finishedGoodRepository.findById(fg.getId()).orElseThrow();
        assertEquals(0, refreshedFg.getCurrentStock().compareTo(new BigDecimal("7")));
        assertEquals(0, refreshedFg.getReservedStock().compareTo(new BigDecimal("7")));

        List<InventoryReservation> updatedReservations =
                inventoryReservationRepository.findByFinishedGoodCompanyAndReferenceTypeAndReferenceId(
                        company, InventoryReference.SALES_ORDER, order.getId().toString());
        InventoryReservation updated = updatedReservations.get(0);
        assertEquals(0, updated.getFulfilledQuantity().compareTo(new BigDecimal("3")));
        assertEquals(0, updated.getReservedQuantity().compareTo(BigDecimal.ZERO));
        assertEquals("FULFILLED", updated.getStatus());

        List<InventoryMovement> movements = inventoryMovementRepository
                .findByReferenceTypeAndReferenceIdOrderByCreatedAtAsc(InventoryReference.SALES_ORDER, order.getId().toString());
        assertEquals(1, movements.size());
        assertEquals(0, movements.getFirst().getQuantity().compareTo(new BigDecimal("3")));
    }

    @Test
    void dispatchUsesFifoAcrossBatches() {
        Company company = dataSeeder.ensureCompany("DISP-FIFO", "Dispatch FIFO");

        FinishedGood fg = new FinishedGood();
        fg.setCompany(company);
        fg.setProductCode("FG-FIFO");
        fg.setName("Blue Paint FIFO");
        fg.setUnit("UNIT");
        fg.setCostingMethod("FIFO");
        fg.setCurrentStock(new BigDecimal("8"));
        fg.setReservedStock(new BigDecimal("8"));
        fg.setValuationAccountId(100L);
        fg.setCogsAccountId(200L);
        fg = finishedGoodRepository.saveAndFlush(fg);

        FinishedGoodBatch batch1 = new FinishedGoodBatch();
        batch1.setFinishedGood(fg);
        batch1.setBatchCode("BATCH-1");
        batch1.setQuantityTotal(new BigDecimal("5"));
        batch1.setQuantityAvailable(new BigDecimal("5"));
        batch1.setUnitCost(new BigDecimal("5"));
        batch1.setManufacturedAt(Instant.now().minusSeconds(3600));
        batch1 = finishedGoodBatchRepository.saveAndFlush(batch1);

        FinishedGoodBatch batch2 = new FinishedGoodBatch();
        batch2.setFinishedGood(fg);
        batch2.setBatchCode("BATCH-2");
        batch2.setQuantityTotal(new BigDecimal("5"));
        batch2.setQuantityAvailable(new BigDecimal("5"));
        batch2.setUnitCost(new BigDecimal("7"));
        batch2.setManufacturedAt(Instant.now());
        batch2 = finishedGoodBatchRepository.saveAndFlush(batch2);

        SalesOrder order = dataSeeder.ensureSalesOrder(company.getCode(), "SO-FIFO-" + UUID.randomUUID(), new BigDecimal("100"));

        PackagingSlip slip = new PackagingSlip();
        slip.setCompany(company);
        slip.setSalesOrder(order);
        slip.setSlipNumber("PS-FIFO-" + UUID.randomUUID());
        slip.setStatus("PENDING");

        PackagingSlipLine line1 = new PackagingSlipLine();
        line1.setPackagingSlip(slip);
        line1.setFinishedGoodBatch(batch1);
        line1.setOrderedQuantity(new BigDecimal("5"));
        line1.setQuantity(new BigDecimal("5"));
        line1.setUnitCost(batch1.getUnitCost());
        slip.getLines().add(line1);

        PackagingSlipLine line2 = new PackagingSlipLine();
        line2.setPackagingSlip(slip);
        line2.setFinishedGoodBatch(batch2);
        line2.setOrderedQuantity(new BigDecimal("3"));
        line2.setQuantity(new BigDecimal("3"));
        line2.setUnitCost(batch2.getUnitCost());
        slip.getLines().add(line2);

        slip = packagingSlipRepository.saveAndFlush(slip);

        InventoryReservation reservation1 = new InventoryReservation();
        reservation1.setFinishedGood(fg);
        reservation1.setFinishedGoodBatch(batch1);
        reservation1.setReferenceType(InventoryReference.SALES_ORDER);
        reservation1.setReferenceId(order.getId().toString());
        reservation1.setQuantity(new BigDecimal("5"));
        reservation1.setReservedQuantity(new BigDecimal("5"));
        reservation1.setStatus("RESERVED");
        inventoryReservationRepository.save(reservation1);

        InventoryReservation reservation2 = new InventoryReservation();
        reservation2.setFinishedGood(fg);
        reservation2.setFinishedGoodBatch(batch2);
        reservation2.setReferenceType(InventoryReference.SALES_ORDER);
        reservation2.setReferenceId(order.getId().toString());
        reservation2.setQuantity(new BigDecimal("3"));
        reservation2.setReservedQuantity(new BigDecimal("3"));
        reservation2.setStatus("RESERVED");
        inventoryReservationRepository.save(reservation2);

        List<DispatchPosting> postings = finishedGoodsService.markSlipDispatched(order.getId(), slip);

        BigDecimal expectedCost = new BigDecimal("5").multiply(new BigDecimal("5"))
                .add(new BigDecimal("7").multiply(new BigDecimal("3")));
        assertEquals(1, postings.size());
        assertEquals(0, postings.getFirst().cost().compareTo(expectedCost));
    }

    @Test
    void dispatchUsesWeightedAverageCostWhenConfigured() {
        Company company = dataSeeder.ensureCompany("DISP-WAC", "Dispatch WAC");

        FinishedGood fg = new FinishedGood();
        fg.setCompany(company);
        fg.setProductCode("FG-WAC");
        fg.setName("Blue Paint WAC");
        fg.setUnit("UNIT");
        fg.setCostingMethod("WEIGHTED_AVERAGE");
        fg.setCurrentStock(new BigDecimal("4"));
        fg.setReservedStock(new BigDecimal("4"));
        fg.setValuationAccountId(100L);
        fg.setCogsAccountId(200L);
        fg = finishedGoodRepository.saveAndFlush(fg);

        FinishedGoodBatch batch1 = new FinishedGoodBatch();
        batch1.setFinishedGood(fg);
        batch1.setBatchCode("WAC-1");
        batch1.setQuantityTotal(new BigDecimal("10"));
        batch1.setQuantityAvailable(new BigDecimal("10"));
        batch1.setUnitCost(new BigDecimal("10"));
        batch1.setManufacturedAt(Instant.now().minusSeconds(7200));
        batch1 = finishedGoodBatchRepository.saveAndFlush(batch1);

        FinishedGoodBatch batch2 = new FinishedGoodBatch();
        batch2.setFinishedGood(fg);
        batch2.setBatchCode("WAC-2");
        batch2.setQuantityTotal(new BigDecimal("10"));
        batch2.setQuantityAvailable(new BigDecimal("10"));
        batch2.setUnitCost(new BigDecimal("20"));
        batch2.setManufacturedAt(Instant.now().minusSeconds(3600));
        batch2 = finishedGoodBatchRepository.saveAndFlush(batch2);

        SalesOrder order = dataSeeder.ensureSalesOrder(company.getCode(), "SO-WAC-" + UUID.randomUUID(), new BigDecimal("100"));

        PackagingSlip slip = new PackagingSlip();
        slip.setCompany(company);
        slip.setSalesOrder(order);
        slip.setSlipNumber("PS-WAC-" + UUID.randomUUID());
        slip.setStatus("PENDING");

        PackagingSlipLine line = new PackagingSlipLine();
        line.setPackagingSlip(slip);
        line.setFinishedGoodBatch(batch1);
        line.setOrderedQuantity(new BigDecimal("4"));
        line.setQuantity(new BigDecimal("4"));
        line.setUnitCost(batch1.getUnitCost());
        slip.getLines().add(line);

        slip = packagingSlipRepository.saveAndFlush(slip);

        InventoryReservation reservation = new InventoryReservation();
        reservation.setFinishedGood(fg);
        reservation.setFinishedGoodBatch(batch1);
        reservation.setReferenceType(InventoryReference.SALES_ORDER);
        reservation.setReferenceId(order.getId().toString());
        reservation.setQuantity(new BigDecimal("4"));
        reservation.setReservedQuantity(new BigDecimal("4"));
        reservation.setStatus("RESERVED");
        inventoryReservationRepository.save(reservation);

        List<DispatchPosting> postings = finishedGoodsService.markSlipDispatched(order.getId(), slip);

        BigDecimal expectedWac = new BigDecimal("15");
        BigDecimal expectedCost = expectedWac.multiply(new BigDecimal("4"));
        assertEquals(1, postings.size());
        assertEquals(0, postings.getFirst().cost().compareTo(expectedCost));
    }

    @Test
    void getOrderWithItemsIsScopedToCurrentCompany() {
        Company companyA = dataSeeder.ensureCompany("COMP-A", "Company A");
        Company companyB = dataSeeder.ensureCompany("COMP-B", "Company B");
        SalesOrder orderB = dataSeeder.ensureSalesOrder(companyB.getCode(), "SO-B-1", new BigDecimal("50"));

        CompanyContextHolder.setCompanyId(companyA.getCode());

        assertThrows(com.bigbrightpaints.erp.core.exception.ApplicationException.class,
                () -> salesService.getOrderWithItems(orderB.getId()));
    }

    @Test
    void updateRawMaterialDefaultsNullThresholdsToZero() {
        Company company = dataSeeder.ensureCompany("RM-CO", "Raw Material Co");
        CompanyContextHolder.setCompanyId(company.getCode());

        var created = rawMaterialService.createRawMaterial(new RawMaterialRequest(
                "Titanium Dioxide",
                "RM-TIO2",
                null,
                "KG",
                new BigDecimal("5"),
                new BigDecimal("2"),
                new BigDecimal("8"),
                null,
                null
        ));

        rawMaterialService.updateRawMaterial(created.id(), new RawMaterialRequest(
                "Titanium Dioxide",
                "RM-TIO2",
                null,
                "KG",
                null,
                null,
                null,
                null,
                null
        ));

        RawMaterial refreshed = rawMaterialRepository.findById(created.id()).orElseThrow();
        assertEquals(BigDecimal.ZERO, refreshed.getReorderLevel());
        assertEquals(BigDecimal.ZERO, refreshed.getMinStock());
        assertEquals(BigDecimal.ZERO, refreshed.getMaxStock());
    }

    @Test
    void rawMaterialBatchCodesMustBeUniquePerMaterial() {
        Company company = dataSeeder.ensureCompany("RM-DUP", "Raw Material Dup Co");
        CompanyContextHolder.setCompanyId(company.getCode());

        Account inventory = new Account();
        inventory.setCompany(company);
        inventory.setCode("INV-DUP");
        inventory.setName("Inventory");
        inventory.setType(AccountType.ASSET);
        inventory = accountRepository.save(inventory);

        Account payable = new Account();
        payable.setCompany(company);
        payable.setCode("AP-DUP");
        payable.setName("Accounts Payable");
        payable.setType(AccountType.LIABILITY);
        payable = accountRepository.save(payable);

        Supplier supplier = new Supplier();
        supplier.setCompany(company);
        supplier.setCode("SUP-DUP");
        supplier.setName("Dup Supplier");
        supplier.setPayableAccount(payable);
        supplier.setStatus("ACTIVE");
        supplier = supplierRepository.save(supplier);

        var material = rawMaterialService.createRawMaterial(new RawMaterialRequest(
                "Duplicate Test Material",
                "RM-DUP-001",
                null,
                "KG",
                new BigDecimal("5"),
                new BigDecimal("2"),
                new BigDecimal("8"),
                inventory.getId(),
                null
        ));

        RawMaterialBatchRequest request = new RawMaterialBatchRequest(
                "DUP-BATCH-001",
                new BigDecimal("10"),
                "KG",
                new BigDecimal("2.50"),
                supplier.getId(),
                null,
                null,
                null
        );

        rawMaterialService.createBatch(material.id(), request, "RM-DUP-KEY-1");

        com.bigbrightpaints.erp.core.exception.ApplicationException ex = assertThrows(
                com.bigbrightpaints.erp.core.exception.ApplicationException.class,
                () -> rawMaterialService.createBatch(material.id(), request, "RM-DUP-KEY-2"));
        assertTrue(ex.getMessage().contains("Batch code"));
    }
}
