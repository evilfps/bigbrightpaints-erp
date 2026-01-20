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
import org.springframework.transaction.annotation.Transactional;

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
    void getOrderWithItemsIsScopedToCurrentCompany() {
        Company companyA = dataSeeder.ensureCompany("COMP-A", "Company A");
        Company companyB = dataSeeder.ensureCompany("COMP-B", "Company B");
        SalesOrder orderB = dataSeeder.ensureSalesOrder(companyB.getCode(), "SO-B-1", new BigDecimal("50"));

        CompanyContextHolder.setCompanyId(companyA.getCode());

        assertThrows(IllegalArgumentException.class, () -> salesService.getOrderWithItems(orderB.getId()));
    }

    @Test
    void updateRawMaterialDefaultsNullThresholdsToZero() {
        Company company = dataSeeder.ensureCompany("RM-CO", "Raw Material Co");
        CompanyContextHolder.setCompanyId(company.getCode());

        var created = rawMaterialService.createRawMaterial(new RawMaterialRequest(
                "Titanium Dioxide",
                "RM-TIO2",
                "KG",
                new BigDecimal("5"),
                new BigDecimal("2"),
                new BigDecimal("8"),
                null
        ));

        rawMaterialService.updateRawMaterial(created.id(), new RawMaterialRequest(
                "Titanium Dioxide",
                "RM-TIO2",
                "KG",
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
        supplier = supplierRepository.save(supplier);

        var material = rawMaterialService.createRawMaterial(new RawMaterialRequest(
                "Duplicate Test Material",
                "RM-DUP-001",
                "KG",
                new BigDecimal("5"),
                new BigDecimal("2"),
                new BigDecimal("8"),
                inventory.getId()
        ));

        RawMaterialBatchRequest request = new RawMaterialBatchRequest(
                "DUP-BATCH-001",
                new BigDecimal("10"),
                "KG",
                new BigDecimal("2.50"),
                supplier.getId(),
                null
        );

        rawMaterialService.createBatch(material.id(), request);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> rawMaterialService.createBatch(material.id(), request));
        assertTrue(ex.getMessage().contains("Batch code"));
    }
}
