package com.bigbrightpaints.erp.modules.purchasing.service;

import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryRepository;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryDto;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingFacade;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryReference;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterial;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialMovement;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialMovementRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialRepository;
import com.bigbrightpaints.erp.modules.inventory.dto.RawMaterialBatchRequest;
import com.bigbrightpaints.erp.modules.inventory.service.RawMaterialService;
import com.bigbrightpaints.erp.modules.purchasing.domain.RawMaterialPurchase;
import com.bigbrightpaints.erp.modules.purchasing.domain.RawMaterialPurchaseLine;
import com.bigbrightpaints.erp.modules.purchasing.domain.RawMaterialPurchaseRepository;
import com.bigbrightpaints.erp.modules.purchasing.domain.Supplier;
import com.bigbrightpaints.erp.modules.purchasing.domain.SupplierRepository;
import com.bigbrightpaints.erp.modules.purchasing.dto.RawMaterialPurchaseLineRequest;
import com.bigbrightpaints.erp.modules.purchasing.dto.RawMaterialPurchaseLineResponse;
import com.bigbrightpaints.erp.modules.purchasing.dto.RawMaterialPurchaseRequest;
import com.bigbrightpaints.erp.modules.purchasing.dto.RawMaterialPurchaseResponse;
import com.bigbrightpaints.erp.modules.purchasing.dto.PurchaseReturnRequest;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class PurchasingService {

    private final CompanyContextService companyContextService;
    private final RawMaterialPurchaseRepository purchaseRepository;
    private final RawMaterialRepository rawMaterialRepository;
    private final SupplierRepository supplierRepository;
    private final RawMaterialService rawMaterialService;
    private final RawMaterialMovementRepository movementRepository;
    private final AccountingFacade accountingFacade;
    private final JournalEntryRepository journalEntryRepository;

    public PurchasingService(CompanyContextService companyContextService,
                             RawMaterialPurchaseRepository purchaseRepository,
                             RawMaterialRepository rawMaterialRepository,
                             SupplierRepository supplierRepository,
                             RawMaterialService rawMaterialService,
                             RawMaterialMovementRepository movementRepository,
                             AccountingFacade accountingFacade,
                             JournalEntryRepository journalEntryRepository) {
        this.companyContextService = companyContextService;
        this.purchaseRepository = purchaseRepository;
        this.rawMaterialRepository = rawMaterialRepository;
        this.supplierRepository = supplierRepository;
        this.rawMaterialService = rawMaterialService;
        this.movementRepository = movementRepository;
        this.accountingFacade = accountingFacade;
        this.journalEntryRepository = journalEntryRepository;
    }

    public List<RawMaterialPurchaseResponse> listPurchases() {
        Company company = companyContextService.requireCurrentCompany();
        return purchaseRepository.findByCompanyOrderByInvoiceDateDesc(company).stream()
                .map(this::toResponse)
                .toList();
    }

    public RawMaterialPurchaseResponse getPurchase(Long id) {
        Company company = companyContextService.requireCurrentCompany();
        RawMaterialPurchase purchase = purchaseRepository.findByCompanyAndId(company, id)
                .orElseThrow(() -> new IllegalArgumentException("Purchase not found"));
        return toResponse(purchase);
    }

    @Transactional
    public RawMaterialPurchaseResponse createPurchase(RawMaterialPurchaseRequest request) {
        Company company = companyContextService.requireCurrentCompany();
        Supplier supplier = supplierRepository.findByCompanyAndId(company, request.supplierId())
                .orElseThrow(() -> new IllegalArgumentException("Supplier not found"));
        if (supplier.getPayableAccount() == null) {
            throw new IllegalStateException("Supplier " + supplier.getName() + " is missing a payable account");
        }
        String invoiceNumber = request.invoiceNumber().trim();
        purchaseRepository.findByCompanyAndInvoiceNumberIgnoreCase(company, invoiceNumber)
                .ifPresent(existing -> {
                    throw new IllegalArgumentException("Invoice number already used for this company");
                });
        RawMaterialPurchase purchase = new RawMaterialPurchase();
        purchase.setCompany(company);
        purchase.setSupplier(supplier);
        purchase.setInvoiceNumber(invoiceNumber);
        purchase.setInvoiceDate(request.invoiceDate());
        purchase.setMemo(clean(request.memo()));

        List<RawMaterialService.ReceiptResult> receipts = new ArrayList<>();
        BigDecimal totalAmount = BigDecimal.ZERO;
        Map<Long, BigDecimal> inventoryDebits = new HashMap<>();
        int lineIndex = 1;
        for (RawMaterialPurchaseLineRequest lineRequest : request.lines()) {
            RawMaterial rawMaterial = requireMaterial(company, lineRequest.rawMaterialId());
            BigDecimal quantity = positive(lineRequest.quantity(), "quantity");
            BigDecimal costPerUnit = positive(lineRequest.costPerUnit(), "costPerUnit");
            String unit = StringUtils.hasText(lineRequest.unit())
                    ? lineRequest.unit().trim()
                    : rawMaterial.getUnitType();
            String batchCode = StringUtils.hasText(lineRequest.batchCode())
                    ? lineRequest.batchCode().trim()
                    : null;
            BigDecimal lineTotal = quantity.multiply(costPerUnit);
            totalAmount = totalAmount.add(lineTotal);
            Long inventoryAccountId = rawMaterial.getInventoryAccountId();
            if (inventoryAccountId == null) {
                throw new IllegalStateException("Raw material " + rawMaterial.getName() + " is missing an inventory account");
            }
            inventoryDebits.merge(inventoryAccountId, lineTotal, BigDecimal::add);

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

        purchase.setTotalAmount(totalAmount);
        purchase = purchaseRepository.save(purchase);

        JournalEntryDto entry = postPurchaseEntry(request, supplier, inventoryDebits, totalAmount);
        if (entry != null) {
            JournalEntry linked = journalEntryRepository.findByCompanyAndId(company, entry.id())
                    .orElse(null);
            purchase.setJournalEntry(linked);
            purchaseRepository.save(purchase);
        }
        if (entry != null) {
            Long entryId = entry.id();
            receipts.stream()
                    .map(RawMaterialService.ReceiptResult::movement)
                    .filter(movement -> movement != null && entryId != null)
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
        Supplier supplier = supplierRepository.findByCompanyAndId(company, request.supplierId())
                .orElseThrow(() -> new IllegalArgumentException("Supplier not found"));
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
        if (material.getCurrentStock().compareTo(quantity) < 0) {
            throw new IllegalArgumentException("Cannot return more than on-hand inventory for " + material.getName());
        }
        BigDecimal totalAmount = quantity.multiply(unitCost);
        String memo = returnMemo(material, supplier, request.reason());
        String reference = resolveReturnReference(supplier, request.referenceNumber());
        LocalDate returnDate = request.returnDate() != null ? request.returnDate() : LocalDate.now();
        JournalEntryDto entry = accountingFacade.postPurchaseReturn(
                supplier.getId(),
                reference,
                returnDate,
                memo,
                Map.of(material.getInventoryAccountId(), totalAmount),
                totalAmount
        );
        material.setCurrentStock(material.getCurrentStock().subtract(quantity));
        rawMaterialRepository.save(material);
        RawMaterialMovement movement = new RawMaterialMovement();
        movement.setRawMaterial(material);
        movement.setReferenceType(InventoryReference.PURCHASE_RETURN);
        movement.setReferenceId(reference);
        movement.setMovementType("RETURN");
        movement.setQuantity(quantity);
        movement.setUnitCost(unitCost);
        movement.setJournalEntryId(entry.id());
        movementRepository.save(movement);
        return entry;
    }

    private JournalEntryDto postPurchaseEntry(RawMaterialPurchaseRequest request,
                                              Supplier supplier,
                                              Map<Long, BigDecimal> inventoryDebits,
                                              BigDecimal totalAmount) {
        if (inventoryDebits.isEmpty() || totalAmount.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }
        String memo = purchaseMemo(request.memo(), request.invoiceNumber(), null);
        LocalDate entryDate = request.invoiceDate() != null ? request.invoiceDate() : LocalDate.now();

        // Delegate to AccountingFacade for purchase journal posting
        return accountingFacade.postPurchaseJournal(
                supplier.getId(),
                request.invoiceNumber(),
                entryDate,
                memo,
                inventoryDebits,
                totalAmount
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

    private String resolveReferenceNumber(Supplier supplier, String invoiceNumber) {
        String normalized = invoiceNumber == null ? "" : invoiceNumber.replaceAll("[^A-Za-z0-9]", "");
        if (!StringUtils.hasText(normalized)) {
            normalized = Instant.now().toString().replaceAll("[^A-Za-z0-9]", "");
        }
        return "RMP-" + supplier.getCode() + "-" + normalized.toUpperCase(Locale.ROOT);
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

    private String resolveReturnReference(Supplier supplier, String provided) {
        if (StringUtils.hasText(provided)) {
            return provided.trim();
        }
        return "PRN-" + supplier.getCode() + "-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase(Locale.ROOT);
    }

    private String clean(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
