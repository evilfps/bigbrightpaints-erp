package com.bigbrightpaints.erp.modules.sales.service;

import com.bigbrightpaints.erp.core.util.CompanyEntityLookup;
import com.bigbrightpaints.erp.core.util.MoneyUtils;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryDto;
import com.bigbrightpaints.erp.modules.accounting.dto.SalesReturnRequest;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingFacade;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGood;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodBatch;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodBatchRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryReference;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryMovement;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryMovementRepository;
import com.bigbrightpaints.erp.modules.inventory.service.BatchNumberService;
import com.bigbrightpaints.erp.modules.invoice.domain.Invoice;
import com.bigbrightpaints.erp.modules.invoice.domain.InvoiceLine;
import com.bigbrightpaints.erp.modules.sales.domain.Dealer;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class SalesReturnService {

    private final CompanyContextService companyContextService;
    private final FinishedGoodRepository finishedGoodRepository;
    private final FinishedGoodBatchRepository finishedGoodBatchRepository;
    private final InventoryMovementRepository inventoryMovementRepository;
    private final BatchNumberService batchNumberService;
    private final AccountingFacade accountingFacade;
    private final CompanyEntityLookup companyEntityLookup;

    public SalesReturnService(CompanyContextService companyContextService,
                              FinishedGoodRepository finishedGoodRepository,
                              FinishedGoodBatchRepository finishedGoodBatchRepository,
                              InventoryMovementRepository inventoryMovementRepository,
                              BatchNumberService batchNumberService,
                              AccountingFacade accountingFacade,
                              CompanyEntityLookup companyEntityLookup) {
        this.companyContextService = companyContextService;
        this.finishedGoodRepository = finishedGoodRepository;
        this.finishedGoodBatchRepository = finishedGoodBatchRepository;
        this.inventoryMovementRepository = inventoryMovementRepository;
        this.batchNumberService = batchNumberService;
        this.accountingFacade = accountingFacade;
        this.companyEntityLookup = companyEntityLookup;
    }

    @Transactional
    public JournalEntryDto processReturn(SalesReturnRequest request) {
        Company company = companyContextService.requireCurrentCompany();
        Invoice invoice = companyEntityLookup.requireInvoice(company, request.invoiceId());
        Dealer dealer = invoice.getDealer();
        if (dealer == null || dealer.getReceivableAccount() == null) {
            throw new IllegalStateException("Invoice is missing dealer receivable context");
        }
        Map<Long, InvoiceLine> invoiceLines = invoice.getLines().stream()
                .collect(Collectors.toMap(InvoiceLine::getId, line -> line));
        if (request.lines() == null || request.lines().isEmpty()) {
            throw new IllegalArgumentException("Return lines are required");
        }

        BigDecimal receivableCredit = BigDecimal.ZERO;
        Map<Long, BigDecimal> totalReturnQtyByFg = new LinkedHashMap<>();
        Map<Long, List<InventoryMovement>> returnMovementsByFg = new LinkedHashMap<>();
        Long salesOrderId = invoice.getSalesOrder() != null ? invoice.getSalesOrder().getId() : null;
        Map<Long, java.util.List<InventoryMovement>> dispatchMovementsByFg = salesOrderId != null
                ? loadDispatchMovements(salesOrderId)
                : Map.of();

        // Process returns and restock inventory
        for (SalesReturnRequest.ReturnLine lineRequest : request.lines()) {
            InvoiceLine invoiceLine = invoiceLines.get(lineRequest.invoiceLineId());
            if (invoiceLine == null) {
                throw new IllegalArgumentException("Invoice line not found: " + lineRequest.invoiceLineId());
            }
            BigDecimal quantity = requirePositive(lineRequest.quantity(), "lines.quantity");
            if (quantity.compareTo(invoiceLine.getQuantity()) > 0) {
                throw new IllegalArgumentException("Return quantity exceeds invoiced amount for " + invoiceLine.getProductCode());
            }
            FinishedGood finishedGood = lockFinishedGood(company, invoiceLine.getProductCode());

            BigDecimal baseAmount = MoneyUtils.safeMultiply(invoiceLine.getUnitPrice(), quantity);
            BigDecimal taxPerUnit = perUnitTax(invoiceLine);
            BigDecimal taxAmount = MoneyUtils.safeMultiply(taxPerUnit, quantity);
            BigDecimal totalLineAmount = baseAmount.add(taxAmount);
            receivableCredit = receivableCredit.add(totalLineAmount);
            totalReturnQtyByFg.merge(finishedGood.getId(), quantity, BigDecimal::add);

            BigDecimal restockUnitCost = resolveReturnUnitCost(finishedGood, quantity, dispatchMovementsByFg, invoiceLine);

            InventoryMovement returnMovement = restockFinishedGood(
                    finishedGood,
                    quantity,
                    invoice.getInvoiceNumber(),
                    restockUnitCost
            );
            if (returnMovement != null) {
                returnMovementsByFg
                        .computeIfAbsent(finishedGood.getId(), ignored -> new ArrayList<>())
                        .add(returnMovement);
            }
        }
        if (receivableCredit.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Return amount must be greater than zero");
        }

        // Build return lines map (revenue/tax accounts to amounts)
        Map<Long, BigDecimal> returnLines = new LinkedHashMap<>();
        for (SalesReturnRequest.ReturnLine lineRequest : request.lines()) {
            InvoiceLine invoiceLine = invoiceLines.get(lineRequest.invoiceLineId());
            if (invoiceLine == null) {
                continue;
            }
            BigDecimal quantity = requirePositive(lineRequest.quantity(), "lines.quantity");
            FinishedGood finishedGood = lockFinishedGood(company, invoiceLine.getProductCode());

            Long revenueAccountId = finishedGood.getDiscountAccountId() != null
                    ? finishedGood.getDiscountAccountId()
                    : finishedGood.getRevenueAccountId();
            if (revenueAccountId != null) {
                BigDecimal baseAmount = invoiceLine.getUnitPrice().multiply(quantity);
                if (baseAmount.compareTo(BigDecimal.ZERO) > 0) {
                    returnLines.merge(revenueAccountId, baseAmount, BigDecimal::add);
                }
            }

            Long taxAccountId = finishedGood.getTaxAccountId();
            if (taxAccountId != null) {
                BigDecimal taxPerUnit = perUnitTax(invoiceLine);
                BigDecimal taxAmount = MoneyUtils.safeMultiply(taxPerUnit, quantity);
                if (taxAmount.compareTo(BigDecimal.ZERO) > 0) {
                    returnLines.merge(taxAccountId, taxAmount, BigDecimal::add);
                }
            }
        }

        // Delegate to AccountingFacade
        JournalEntryDto salesReturnEntry = accountingFacade.postSalesReturn(
                dealer.getId(),
                invoice.getInvoiceNumber(),
                returnLines,
                receivableCredit,
                request.reason()
        );

        // Reverse COGS and restore inventory value using dispatch cost
        if (salesOrderId != null && !totalReturnQtyByFg.isEmpty()) {
            postCogsReversal(invoice, totalReturnQtyByFg, dispatchMovementsByFg, returnMovementsByFg);
        }
        return salesReturnEntry;
    }

    private InventoryMovement restockFinishedGood(FinishedGood finishedGood,
                                                  BigDecimal quantity,
                                                  String invoiceNumber,
                                                  BigDecimal unitCost) {
        FinishedGood fg = lockFinishedGood(finishedGood.getCompany(), finishedGood.getId());
        BigDecimal currentStock = fg.getCurrentStock() != null ? fg.getCurrentStock() : BigDecimal.ZERO;
        fg.setCurrentStock(currentStock.add(quantity));
        finishedGoodRepository.save(fg);

        FinishedGoodBatch returnBatch = new FinishedGoodBatch();
        returnBatch.setFinishedGood(fg);
        returnBatch.setBatchCode(batchNumberService.nextFinishedGoodBatchCode(fg, LocalDate.now(ZoneOffset.UTC)));
        returnBatch.setQuantityTotal(quantity);
        returnBatch.setQuantityAvailable(quantity);
        returnBatch.setUnitCost(unitCost);
        returnBatch.setManufacturedAt(java.time.Instant.now());
        FinishedGoodBatch savedBatch = finishedGoodBatchRepository.save(returnBatch);

        InventoryMovement movement = new InventoryMovement();
        movement.setFinishedGood(fg);
        movement.setFinishedGoodBatch(savedBatch);
        movement.setReferenceType("SALES_RETURN");
        movement.setReferenceId(invoiceNumber);
        movement.setMovementType("RETURN");
        movement.setQuantity(quantity);
        movement.setUnitCost(unitCost);
        return inventoryMovementRepository.save(movement);
    }

    private BigDecimal perUnitTax(InvoiceLine line) {
        BigDecimal total = Optional.ofNullable(line.getLineTotal()).orElse(BigDecimal.ZERO);
        BigDecimal base = MoneyUtils.safeMultiply(line.getUnitPrice(), Optional.ofNullable(line.getQuantity()).orElse(BigDecimal.ONE));
        BigDecimal taxTotal = total.subtract(base);
        if (line.getQuantity() == null || line.getQuantity().compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        return MoneyUtils.safeDivide(taxTotal, line.getQuantity(), 4, RoundingMode.HALF_UP);
    }

    private void postCogsReversal(Invoice invoice,
                                  Map<Long, BigDecimal> returnQuantitiesByFinishedGood,
                                  Map<Long, java.util.List<InventoryMovement>> dispatchMovements,
                                  Map<Long, List<InventoryMovement>> returnMovementsByFg) {
        int reversalIndex = 0;
        for (Map.Entry<Long, BigDecimal> entry : returnQuantitiesByFinishedGood.entrySet()) {
            Long fgId = entry.getKey();
            BigDecimal remainingQty = entry.getValue();
            if (remainingQty.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            java.util.List<InventoryMovement> movements = dispatchMovements.getOrDefault(fgId, java.util.List.of());
            BigDecimal reversalAmount = BigDecimal.ZERO;
            for (InventoryMovement mv : movements) {
                BigDecimal available = mv.getQuantity() == null ? BigDecimal.ZERO : mv.getQuantity();
                if (available.compareTo(BigDecimal.ZERO) <= 0) {
                    continue;
                }
                BigDecimal take = remainingQty.min(available);
                if (take.compareTo(BigDecimal.ZERO) <= 0) {
                    continue;
                }
                BigDecimal unitCost = mv.getUnitCost() == null ? BigDecimal.ZERO : mv.getUnitCost();
                reversalAmount = reversalAmount.add(unitCost.multiply(take));
                remainingQty = remainingQty.subtract(take);
                if (remainingQty.compareTo(BigDecimal.ZERO) <= 0) {
                    break;
                }
            }
            FinishedGood fg = finishedGoodRepository.findById(fgId).orElse(null);
            if (fg == null) {
                continue;
            }
            Long invAcctId = fg.getValuationAccountId();
            Long cogsAcctId = fg.getCogsAccountId();
            if (reversalAmount.compareTo(BigDecimal.ZERO) > 0 && invAcctId != null && cogsAcctId != null) {
                String ref = String.format("CRN-%s-COGS-%d", invoice.getInvoiceNumber(), reversalIndex++);
                JournalEntryDto cogsReversal = accountingFacade.postInventoryAdjustment(
                        "SALES_RETURN_COGS",
                        ref,
                        cogsAcctId,
                        Map.of(invAcctId, reversalAmount),
                        true,
                        false,
                        "COGS reversal for return " + invoice.getInvoiceNumber()
                );
                if (cogsReversal != null) {
                    linkReturnMovements(returnMovementsByFg, fgId, cogsReversal.id());
                }
            }
        }
    }

    private void linkReturnMovements(Map<Long, List<InventoryMovement>> returnMovementsByFg,
                                     Long finishedGoodId,
                                     Long journalEntryId) {
        if (journalEntryId == null || returnMovementsByFg == null) {
            return;
        }
        List<InventoryMovement> movements = returnMovementsByFg.getOrDefault(finishedGoodId, List.of());
        if (movements.isEmpty()) {
            return;
        }
        List<InventoryMovement> toUpdate = new ArrayList<>();
        for (InventoryMovement movement : movements) {
            if (movement.getJournalEntryId() == null) {
                movement.setJournalEntryId(journalEntryId);
                toUpdate.add(movement);
            }
        }
        if (!toUpdate.isEmpty()) {
            inventoryMovementRepository.saveAll(toUpdate);
        }
    }

    private Map<Long, java.util.List<InventoryMovement>> loadDispatchMovements(Long salesOrderId) {
        return inventoryMovementRepository
                .findByReferenceTypeAndReferenceIdOrderByCreatedAtAsc(InventoryReference.SALES_ORDER, salesOrderId.toString())
                .stream()
                .filter(mv -> "DISPATCH".equalsIgnoreCase(mv.getMovementType()))
                .filter(mv -> mv.getFinishedGood() != null)
                .collect(Collectors.groupingBy(mv -> mv.getFinishedGood().getId(), LinkedHashMap::new, Collectors.toList()));
    }

    private BigDecimal resolveReturnUnitCost(FinishedGood finishedGood,
                                             BigDecimal quantity,
                                             Map<Long, java.util.List<InventoryMovement>> dispatchMovements,
                                             InvoiceLine invoiceLine) {
        java.util.List<InventoryMovement> movements = dispatchMovements.getOrDefault(finishedGood.getId(), java.util.List.of());
        BigDecimal remaining = quantity;
        BigDecimal costTotal = BigDecimal.ZERO;
        for (InventoryMovement mv : movements) {
            BigDecimal available = mv.getQuantity() == null ? BigDecimal.ZERO : mv.getQuantity();
            if (available.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            BigDecimal take = remaining.min(available);
            if (take.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            BigDecimal unitCost = mv.getUnitCost() == null ? BigDecimal.ZERO : mv.getUnitCost();
            costTotal = costTotal.add(unitCost.multiply(take));
            remaining = remaining.subtract(take);
            if (remaining.compareTo(BigDecimal.ZERO) <= 0) {
                break;
            }
        }
        if (costTotal.compareTo(BigDecimal.ZERO) > 0 && quantity.compareTo(BigDecimal.ZERO) > 0) {
            return MoneyUtils.safeDivide(costTotal, quantity, 4, RoundingMode.HALF_UP);
        }
        // Fallback to sale price if no cost history found
        return invoiceLine.getUnitPrice();
    }

    private BigDecimal requirePositive(BigDecimal value, String field) {
        if (value == null || value.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException(field + " must be positive");
        }
        return value;
    }

    private FinishedGood lockFinishedGood(Company company, String productCode) {
        return finishedGoodRepository.lockByCompanyAndProductCode(company, productCode)
                .orElseThrow(() -> new IllegalStateException("Finished good not found for " + productCode));
    }

    private FinishedGood lockFinishedGood(Company company, Long id) {
        return finishedGoodRepository.lockByCompanyAndId(company, id)
                .orElseThrow(() -> new IllegalStateException("Finished good not found"));
    }
}
