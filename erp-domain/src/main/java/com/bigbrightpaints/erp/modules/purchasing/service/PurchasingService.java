package com.bigbrightpaints.erp.modules.purchasing.service;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.core.util.MoneyUtils;
import com.bigbrightpaints.erp.core.util.CompanyEntityLookup;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryRepository;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryDto;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingFacade;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryReference;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterial;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialBatch;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialBatchRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialMovement;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialMovementRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialRepository;
import com.bigbrightpaints.erp.modules.inventory.dto.RawMaterialBatchRequest;
import com.bigbrightpaints.erp.modules.inventory.service.RawMaterialService;
import com.bigbrightpaints.erp.modules.accounting.service.ReferenceNumberService;
import com.bigbrightpaints.erp.modules.purchasing.domain.RawMaterialPurchase;
import com.bigbrightpaints.erp.modules.purchasing.domain.RawMaterialPurchaseLine;
import com.bigbrightpaints.erp.modules.purchasing.domain.RawMaterialPurchaseRepository;
import com.bigbrightpaints.erp.modules.purchasing.domain.Supplier;
import com.bigbrightpaints.erp.modules.purchasing.dto.RawMaterialPurchaseLineRequest;
import com.bigbrightpaints.erp.modules.purchasing.dto.RawMaterialPurchaseLineResponse;
import com.bigbrightpaints.erp.modules.purchasing.dto.RawMaterialPurchaseRequest;
import com.bigbrightpaints.erp.modules.purchasing.dto.RawMaterialPurchaseResponse;
import com.bigbrightpaints.erp.modules.purchasing.dto.PurchaseReturnRequest;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class PurchasingService {

    private final CompanyContextService companyContextService;
    private final RawMaterialPurchaseRepository purchaseRepository;
    private final RawMaterialRepository rawMaterialRepository;
    private final RawMaterialBatchRepository rawMaterialBatchRepository;
    private final RawMaterialService rawMaterialService;
    private final RawMaterialMovementRepository movementRepository;
    private final AccountingFacade accountingFacade;
    private final JournalEntryRepository journalEntryRepository;
    private final CompanyEntityLookup companyEntityLookup;
    private final ReferenceNumberService referenceNumberService;
    private final CompanyClock companyClock;

    public PurchasingService(CompanyContextService companyContextService,
                             RawMaterialPurchaseRepository purchaseRepository,
                             RawMaterialRepository rawMaterialRepository,
                             RawMaterialBatchRepository rawMaterialBatchRepository,
                             RawMaterialService rawMaterialService,
                             RawMaterialMovementRepository movementRepository,
                             AccountingFacade accountingFacade,
                             JournalEntryRepository journalEntryRepository,
                             CompanyEntityLookup companyEntityLookup,
                             ReferenceNumberService referenceNumberService,
                             CompanyClock companyClock) {
        this.companyContextService = companyContextService;
        this.purchaseRepository = purchaseRepository;
        this.rawMaterialRepository = rawMaterialRepository;
        this.rawMaterialBatchRepository = rawMaterialBatchRepository;
        this.rawMaterialService = rawMaterialService;
        this.movementRepository = movementRepository;
        this.accountingFacade = accountingFacade;
        this.journalEntryRepository = journalEntryRepository;
        this.companyEntityLookup = companyEntityLookup;
        this.referenceNumberService = referenceNumberService;
        this.companyClock = companyClock;
    }

    public List<RawMaterialPurchaseResponse> listPurchases() {
        Company company = companyContextService.requireCurrentCompany();
        return purchaseRepository.findByCompanyWithLinesOrderByInvoiceDateDesc(company).stream()
                .map(this::toResponse)
                .toList();
    }

    public RawMaterialPurchaseResponse getPurchase(Long id) {
        Company company = companyContextService.requireCurrentCompany();
        RawMaterialPurchase purchase = companyEntityLookup.requireRawMaterialPurchase(company, id);
        return toResponse(purchase);
    }

    @Transactional
    public RawMaterialPurchaseResponse createPurchase(RawMaterialPurchaseRequest request) {
        Company company = companyContextService.requireCurrentCompany();
        Supplier supplier = companyEntityLookup.requireSupplier(company, request.supplierId());
        if (supplier.getPayableAccount() == null) {
            throw new IllegalStateException("Supplier " + supplier.getName() + " is missing a payable account");
        }
        String invoiceNumber = request.invoiceNumber().trim();

        // Use pessimistic lock to prevent duplicate invoice race condition
        purchaseRepository.lockByCompanyAndInvoiceNumberIgnoreCase(company, invoiceNumber)
                .ifPresent(existing -> {
                    throw new IllegalArgumentException("Invoice number already used for this company");
                });

        // Sort lines by rawMaterialId to maintain consistent lock ordering and avoid deadlocks
        List<RawMaterialPurchaseLineRequest> sortedLines = request.lines().stream()
                .sorted(Comparator.comparing(RawMaterialPurchaseLineRequest::rawMaterialId))
                .toList();

        // Pre-validate and lock all materials in consistent order before any mutations
        Map<Long, RawMaterial> lockedMaterials = new HashMap<>();
        BigDecimal totalAmount = BigDecimal.ZERO;
        Map<Long, BigDecimal> inventoryDebits = new HashMap<>();

        for (RawMaterialPurchaseLineRequest lineRequest : sortedLines) {
            RawMaterial rawMaterial = requireMaterial(company, lineRequest.rawMaterialId());
            lockedMaterials.put(rawMaterial.getId(), rawMaterial);
            BigDecimal quantity = positive(lineRequest.quantity(), "quantity");
            BigDecimal costPerUnit = positive(lineRequest.costPerUnit(), "costPerUnit");
            BigDecimal lineTotal = MoneyUtils.safeMultiply(quantity, costPerUnit);
            totalAmount = totalAmount.add(lineTotal);
            Long inventoryAccountId = rawMaterial.getInventoryAccountId();
            if (inventoryAccountId == null) {
                throw new IllegalStateException("Raw material " + rawMaterial.getName() + " is missing an inventory account");
            }
            inventoryDebits.merge(inventoryAccountId, lineTotal, BigDecimal::add);
        }

        // Post journal FIRST to avoid orphan purchases if journal fails
        String referenceNumber = referenceNumberService.purchaseReference(company, supplier, invoiceNumber);
        JournalEntryDto entry = postPurchaseEntry(request, supplier, inventoryDebits, totalAmount, referenceNumber);
        JournalEntry linkedJournal = null;
        if (entry != null) {
            linkedJournal = companyEntityLookup.requireJournalEntry(company, entry.id());
        }

        // Now create purchase with journal already linked
        RawMaterialPurchase purchase = new RawMaterialPurchase();
        purchase.setCompany(company);
        purchase.setSupplier(supplier);
        purchase.setInvoiceNumber(invoiceNumber);
        purchase.setInvoiceDate(request.invoiceDate());
        purchase.setMemo(clean(request.memo()));
        purchase.setTotalAmount(totalAmount);
        purchase.setOutstandingAmount(totalAmount);
        purchase.setJournalEntry(linkedJournal);

        // Process lines using already-locked materials
        List<RawMaterialService.ReceiptResult> receipts = new ArrayList<>();
        int lineIndex = 1;
        for (RawMaterialPurchaseLineRequest lineRequest : request.lines()) {
            RawMaterial rawMaterial = lockedMaterials.get(lineRequest.rawMaterialId());
            BigDecimal quantity = positive(lineRequest.quantity(), "quantity");
            BigDecimal costPerUnit = positive(lineRequest.costPerUnit(), "costPerUnit");
            String unit = StringUtils.hasText(lineRequest.unit())
                    ? lineRequest.unit().trim()
                    : rawMaterial.getUnitType();
            String batchCode = StringUtils.hasText(lineRequest.batchCode())
                    ? lineRequest.batchCode().trim()
                    : null;
            BigDecimal lineTotal = MoneyUtils.safeMultiply(quantity, costPerUnit);

            RawMaterialBatchRequest batchRequest = new RawMaterialBatchRequest(
                    batchCode,
                    quantity,
                    unit,
                    costPerUnit,
                    supplier.getId(),
                    lineRequest.notes()
            );
            RawMaterialService.ReceiptContext context = new RawMaterialService.ReceiptContext(
                    InventoryReference.RAW_MATERIAL_PURCHASE,
                    invoiceReference(invoiceNumber, lineIndex++),
                    purchaseMemo(request.memo(), invoiceNumber, batchCode),
                    false
            );
            RawMaterialService.ReceiptResult receipt = rawMaterialService.recordReceipt(rawMaterial.getId(), batchRequest, context);
            receipts.add(receipt);

            RawMaterialPurchaseLine line = new RawMaterialPurchaseLine();
            line.setPurchase(purchase);
            line.setRawMaterial(rawMaterial);
            line.setRawMaterialBatch(receipt.batch());
            line.setBatchCode(receipt.batch().getBatchCode());
            line.setQuantity(quantity);
            line.setUnit(unit);
            line.setCostPerUnit(costPerUnit);
            line.setLineTotal(lineTotal);
            line.setNotes(lineRequest.notes());
            purchase.getLines().add(line);
        }

        purchase = purchaseRepository.save(purchase);

        // Link journal entry to movements
        if (entry != null) {
            Long entryId = entry.id();
            receipts.stream()
                    .map(RawMaterialService.ReceiptResult::movement)
                    .filter(movement -> movement != null)
                    .forEach(movement -> {
                        movement.setJournalEntryId(entryId);
                        movementRepository.save(movement);
                    });
        }
        return toResponse(purchase);
    }

    @Transactional
    public JournalEntryDto recordPurchaseReturn(PurchaseReturnRequest request) {
        Company company = companyContextService.requireCurrentCompany();
        Supplier supplier = companyEntityLookup.requireSupplier(company, request.supplierId());
        if (supplier.getPayableAccount() == null) {
            throw new IllegalStateException("Supplier " + supplier.getName() + " is missing a payable account");
        }
        RawMaterial material = rawMaterialRepository.lockByCompanyAndId(company, request.rawMaterialId())
                .orElseThrow(() -> new IllegalArgumentException("Raw material not found"));
        if (material.getInventoryAccountId() == null) {
            throw new IllegalStateException("Raw material " + material.getName() + " is missing an inventory account mapping");
        }
        BigDecimal quantity = positive(request.quantity(), "quantity");
        BigDecimal unitCost = positive(request.unitCost(), "unitCost");
        BigDecimal totalAmount = MoneyUtils.safeMultiply(quantity, unitCost);
        String memo = returnMemo(material, supplier, request.reason());
        String reference = StringUtils.hasText(request.referenceNumber())
                ? request.referenceNumber().trim()
                : referenceNumberService.purchaseReturnReference(company, supplier);
        LocalDate returnDate = request.returnDate() != null ? request.returnDate() : companyClock.today(company);
        List<RawMaterialMovement> existingMovements = movementRepository
                .findByRawMaterialCompanyAndReferenceTypeAndReferenceId(company,
                        InventoryReference.PURCHASE_RETURN,
                        reference);
        if (!existingMovements.isEmpty()) {
            return returnExistingPurchaseReturn(material, supplier, quantity, unitCost, totalAmount, reference,
                    returnDate, memo, existingMovements);
        }

        // Post journal FIRST before deducting stock
        JournalEntryDto entry = accountingFacade.postPurchaseReturn(
                supplier.getId(),
                reference,
                returnDate,
                memo,
                Map.of(material.getInventoryAccountId(), totalAmount),
                totalAmount
        );

        // Use atomic UPDATE to prevent negative stock under concurrent access
        int updated = rawMaterialRepository.deductStockIfSufficient(material.getId(), quantity);
        if (updated == 0) {
            throw new IllegalArgumentException("Cannot return more than on-hand inventory for " + material.getName());
        }

        List<RawMaterialMovement> movements = issueReturnFromBatches(material, quantity, unitCost, reference, entry.id());
        movementRepository.saveAll(movements);
        return entry;
    }

    private JournalEntryDto returnExistingPurchaseReturn(RawMaterial material,
                                                         Supplier supplier,
                                                         BigDecimal quantity,
                                                         BigDecimal unitCost,
                                                         BigDecimal totalAmount,
                                                         String reference,
                                                         LocalDate returnDate,
                                                         String memo,
                                                         List<RawMaterialMovement> existingMovements) {
        validateReturnReplay(material, quantity, unitCost, reference, existingMovements);
        JournalEntryDto entry = accountingFacade.postPurchaseReturn(
                supplier.getId(),
                reference,
                returnDate,
                memo,
                Map.of(material.getInventoryAccountId(), totalAmount),
                totalAmount
        );
        if (entry != null) {
            Long entryId = entry.id();
            boolean needsLink = existingMovements.stream()
                    .anyMatch(movement -> movement.getJournalEntryId() == null
                            || !movement.getJournalEntryId().equals(entryId));
            if (needsLink) {
                existingMovements.forEach(movement -> movement.setJournalEntryId(entryId));
                movementRepository.saveAll(existingMovements);
            }
        }
        return entry;
    }

    private void validateReturnReplay(RawMaterial material,
                                      BigDecimal quantity,
                                      BigDecimal unitCost,
                                      String reference,
                                      List<RawMaterialMovement> existingMovements) {
        List<Long> materialIds = existingMovements.stream()
                .map(movement -> movement.getRawMaterial().getId())
                .distinct()
                .toList();
        if (materialIds.size() != 1 || !materialIds.get(0).equals(material.getId())) {
            throwIdempotencyConflict(material, quantity, unitCost, reference);
        }
        BigDecimal existingQty = existingMovements.stream()
                .map(RawMaterialMovement::getQuantity)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (existingQty.compareTo(quantity) != 0) {
            throwIdempotencyConflict(material, quantity, unitCost, reference);
        }
        BigDecimal expectedCost = unitCost != null ? unitCost : BigDecimal.ZERO;
        boolean unitCostMismatch = existingMovements.stream()
                .map(RawMaterialMovement::getUnitCost)
                .filter(Objects::nonNull)
                .anyMatch(cost -> cost.compareTo(expectedCost) != 0);
        if (unitCostMismatch) {
            throwIdempotencyConflict(material, quantity, unitCost, reference);
        }
    }

    private void throwIdempotencyConflict(RawMaterial material,
                                          BigDecimal quantity,
                                          BigDecimal unitCost,
                                          String reference) {
        throw new ApplicationException(ErrorCode.CONCURRENCY_CONFLICT,
                "Purchase return reference already used with different payload")
                .withDetail("reference", reference)
                .withDetail("rawMaterialId", material.getId())
                .withDetail("quantity", quantity)
                .withDetail("unitCost", unitCost);
    }

    private List<RawMaterialMovement> issueReturnFromBatches(RawMaterial material,
                                                            BigDecimal quantity,
                                                            BigDecimal unitCost,
                                                            String reference,
                                                            Long journalEntryId) {
        List<RawMaterialBatch> batches = rawMaterialBatchRepository.findAvailableBatchesFIFO(material);
        BigDecimal remaining = quantity;
        List<RawMaterialMovement> movements = new ArrayList<>();

        for (RawMaterialBatch batch : batches) {
            if (remaining.compareTo(BigDecimal.ZERO) <= 0) {
                break;
            }
            BigDecimal available = batch.getQuantity() != null ? batch.getQuantity() : BigDecimal.ZERO;
            if (available.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            BigDecimal take = available.min(remaining);
            if (take.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }

            int updated = rawMaterialBatchRepository.deductQuantityIfSufficient(batch.getId(), take);
            if (updated == 0) {
                throw new IllegalArgumentException(
                        "Concurrent modification detected or insufficient quantity for batch " + batch.getBatchCode());
            }

            RawMaterialMovement movement = new RawMaterialMovement();
            movement.setRawMaterial(material);
            movement.setRawMaterialBatch(batch);
            movement.setReferenceType(InventoryReference.PURCHASE_RETURN);
            movement.setReferenceId(reference);
            movement.setMovementType("RETURN");
            movement.setQuantity(take);
            movement.setUnitCost(unitCost);
            movement.setJournalEntryId(journalEntryId);
            movements.add(movement);

            remaining = remaining.subtract(take);
        }

        if (remaining.compareTo(BigDecimal.ZERO) > 0) {
            throw new IllegalArgumentException("Insufficient batch availability for " + material.getName());
        }
        return movements;
    }

    private JournalEntryDto postPurchaseEntry(RawMaterialPurchaseRequest request,
                                              Supplier supplier,
                                              Map<Long, BigDecimal> inventoryDebits,
                                              BigDecimal totalAmount,
                                              String referenceNumber) {
        if (inventoryDebits.isEmpty() || totalAmount.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }
        Company company = companyContextService.requireCurrentCompany();
        String memo = purchaseMemo(request.memo(), request.invoiceNumber(), null);
        LocalDate entryDate = request.invoiceDate() != null ? request.invoiceDate() : companyClock.today(company);

        // Delegate to AccountingFacade for purchase journal posting
        return accountingFacade.postPurchaseJournal(
                supplier.getId(),
                request.invoiceNumber(),
                entryDate,
                memo,
                inventoryDebits,
                null,
                totalAmount,
                referenceNumber
        );
    }

    private RawMaterialPurchaseResponse toResponse(RawMaterialPurchase purchase) {
        JournalEntry journalEntry = purchase.getJournalEntry();
        Supplier supplier = purchase.getSupplier();
        List<RawMaterialPurchaseLineResponse> lines = purchase.getLines().stream()
                .map(this::toLineResponse)
                .toList();
        return new RawMaterialPurchaseResponse(
                purchase.getId(),
                purchase.getPublicId(),
                purchase.getInvoiceNumber(),
                purchase.getInvoiceDate(),
                purchase.getTotalAmount(),
                purchase.getOutstandingAmount(),
                purchase.getStatus(),
                purchase.getMemo(),
                supplier != null ? supplier.getId() : null,
                supplier != null ? supplier.getCode() : null,
                supplier != null ? supplier.getName() : null,
                journalEntry != null ? journalEntry.getId() : null,
                purchase.getCreatedAt(),
                lines
        );
    }

    private RawMaterialPurchaseLineResponse toLineResponse(RawMaterialPurchaseLine line) {
        RawMaterial material = line.getRawMaterial();
        return new RawMaterialPurchaseLineResponse(
                material != null ? material.getId() : null,
                material != null ? material.getName() : null,
                line.getRawMaterialBatch() != null ? line.getRawMaterialBatch().getId() : null,
                line.getBatchCode(),
                line.getQuantity(),
                line.getUnit(),
                line.getCostPerUnit(),
                line.getLineTotal(),
                line.getNotes()
        );
    }

    private RawMaterial requireMaterial(Company company, Long rawMaterialId) {
        return rawMaterialRepository.lockByCompanyAndId(company, rawMaterialId)
                .orElseThrow(() -> new IllegalArgumentException("Raw material not found"));
    }

    private BigDecimal positive(BigDecimal value, String field) {
        if (value == null || value.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Value for " + field + " must be greater than zero");
        }
        return value;
    }

    private String invoiceReference(String invoiceNumber, int lineIndex) {
        String normalized = invoiceNumber == null ? "" : invoiceNumber.replaceAll("\\s+", "-");
        return normalized + "-" + lineIndex;
    }

    private String purchaseMemo(String memo, String invoiceNumber, String batchCode) {
        String base = StringUtils.hasText(memo) ? memo.trim() : "Raw material purchase " + invoiceNumber;
        if (StringUtils.hasText(batchCode)) {
            return base + " (" + batchCode + ")";
        }
        return base;
    }

    private String returnMemo(RawMaterial material, Supplier supplier, String reason) {
        String prefix = StringUtils.hasText(reason) ? reason.trim() : "Purchase return";
        return prefix + " - " + material.getName() + " to " + supplier.getName();
    }

    private String clean(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
