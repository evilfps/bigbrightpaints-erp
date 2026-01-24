package com.bigbrightpaints.erp.modules.sales.service;

import com.bigbrightpaints.erp.core.util.CompanyEntityLookup;
import com.bigbrightpaints.erp.core.util.MoneyUtils;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryDto;
import com.bigbrightpaints.erp.modules.accounting.dto.SalesReturnRequest;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingFacade;
import com.bigbrightpaints.erp.modules.accounting.service.CompanyAccountingSettingsService;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class SalesReturnService {

    private static final String SALES_RETURN_REFERENCE = "SALES_RETURN";

    private final CompanyContextService companyContextService;
    private final FinishedGoodRepository finishedGoodRepository;
    private final FinishedGoodBatchRepository finishedGoodBatchRepository;
    private final InventoryMovementRepository inventoryMovementRepository;
    private final BatchNumberService batchNumberService;
    private final AccountingFacade accountingFacade;
    private final CompanyEntityLookup companyEntityLookup;
    private final CompanyAccountingSettingsService companyAccountingSettingsService;

    public SalesReturnService(CompanyContextService companyContextService,
                              FinishedGoodRepository finishedGoodRepository,
                              FinishedGoodBatchRepository finishedGoodBatchRepository,
                              InventoryMovementRepository inventoryMovementRepository,
                              BatchNumberService batchNumberService,
                              AccountingFacade accountingFacade,
                              CompanyEntityLookup companyEntityLookup,
                              CompanyAccountingSettingsService companyAccountingSettingsService) {
        this.companyContextService = companyContextService;
        this.finishedGoodRepository = finishedGoodRepository;
        this.finishedGoodBatchRepository = finishedGoodBatchRepository;
        this.inventoryMovementRepository = inventoryMovementRepository;
        this.batchNumberService = batchNumberService;
        this.accountingFacade = accountingFacade;
        this.companyEntityLookup = companyEntityLookup;
        this.companyAccountingSettingsService = companyAccountingSettingsService;
    }

    @Transactional
    public JournalEntryDto processReturn(SalesReturnRequest request) {
        Company company = companyContextService.requireCurrentCompany();
        Invoice invoice = companyEntityLookup.requireInvoice(company, request.invoiceId());
        Dealer dealer = invoice.getDealer();
        if (dealer == null || dealer.getReceivableAccount() == null) {
            throw new IllegalStateException("Invoice is missing dealer receivable context");
        }
        boolean gstInclusive = invoice.getSalesOrder() != null && invoice.getSalesOrder().isGstInclusive();
        Map<Long, InvoiceLine> invoiceLines = invoice.getLines().stream()
                .collect(Collectors.toMap(InvoiceLine::getId, line -> line));
        if (request.lines() == null || request.lines().isEmpty()) {
            throw new IllegalArgumentException("Return lines are required");
        }

        Map<Long, BigDecimal> existingReturnsByFg = loadReturnMovements(invoice.getInvoiceNumber());
        Map<Long, BigDecimal> requestedReturnQtyByFg = new LinkedHashMap<>();
        Map<Long, BigDecimal> invoicedQtyByFg = new LinkedHashMap<>();
        Map<String, FinishedGood> finishedGoodsByCode = new HashMap<>();
        Map<Long, FinishedGood> finishedGoodsById = new HashMap<>();
        Set<String> returnProductCodes = new HashSet<>();

        for (SalesReturnRequest.ReturnLine lineRequest : request.lines()) {
            InvoiceLine invoiceLine = invoiceLines.get(lineRequest.invoiceLineId());
            if (invoiceLine == null) {
                throw new IllegalArgumentException("Invoice line not found: " + lineRequest.invoiceLineId());
            }
            BigDecimal quantity = requirePositive(lineRequest.quantity(), "lines.quantity");
            if (quantity.compareTo(invoiceLine.getQuantity()) > 0) {
                throw new IllegalArgumentException("Return quantity exceeds invoiced amount for " + invoiceLine.getProductCode());
            }
            returnProductCodes.add(invoiceLine.getProductCode());
            FinishedGood finishedGood = finishedGoodsByCode.computeIfAbsent(
                    invoiceLine.getProductCode(),
                    code -> lockFinishedGood(company, code)
            );
            finishedGoodsById.putIfAbsent(finishedGood.getId(), finishedGood);
            requestedReturnQtyByFg.merge(finishedGood.getId(), quantity, BigDecimal::add);
        }

        for (InvoiceLine invoiceLine : invoice.getLines()) {
            if (!returnProductCodes.contains(invoiceLine.getProductCode())) {
                continue;
            }
            FinishedGood finishedGood = finishedGoodsByCode.computeIfAbsent(
                    invoiceLine.getProductCode(),
                    code -> lockFinishedGood(company, code)
            );
            finishedGoodsById.putIfAbsent(finishedGood.getId(), finishedGood);
            BigDecimal invoicedQty = invoiceLine.getQuantity() != null ? invoiceLine.getQuantity() : BigDecimal.ZERO;
            invoicedQtyByFg.merge(finishedGood.getId(), invoicedQty, BigDecimal::add);
        }

        for (Map.Entry<Long, BigDecimal> entry : requestedReturnQtyByFg.entrySet()) {
            BigDecimal invoicedQty = invoicedQtyByFg.getOrDefault(entry.getKey(), BigDecimal.ZERO);
            BigDecimal priorReturnedQty = existingReturnsByFg.getOrDefault(entry.getKey(), BigDecimal.ZERO);
            if (priorReturnedQty.add(entry.getValue()).compareTo(invoicedQty) > 0) {
                FinishedGood finishedGood = finishedGoodsById.get(entry.getKey());
                String productCode = finishedGood != null ? finishedGood.getProductCode() : entry.getKey().toString();
                throw new IllegalArgumentException("Return quantity exceeds remaining invoiced amount for " + productCode);
            }
        }

        BigDecimal receivableCredit = BigDecimal.ZERO;
        Map<Long, BigDecimal> totalReturnQtyByFg = new LinkedHashMap<>();
        Long salesOrderId = invoice.getSalesOrder() != null ? invoice.getSalesOrder().getId() : null;
        Map<Long, java.util.List<InventoryMovement>> dispatchMovementsByFg = salesOrderId != null
                ? loadDispatchMovements(salesOrderId)
                : Map.of();
        Long gstOutputAccountId = null;

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
            FinishedGood finishedGood = finishedGoodsByCode.computeIfAbsent(
                    invoiceLine.getProductCode(),
                    code -> lockFinishedGood(company, code)
            );

            BigDecimal baseAmount = currency(MoneyUtils.safeMultiply(perUnitBase(invoiceLine), quantity));
            BigDecimal taxPerUnit = perUnitTax(invoiceLine);
            BigDecimal taxAmount = currency(MoneyUtils.safeMultiply(taxPerUnit, quantity));
            BigDecimal totalLineAmount = baseAmount.add(taxAmount);
            receivableCredit = receivableCredit.add(totalLineAmount);
            totalReturnQtyByFg.merge(finishedGood.getId(), quantity, BigDecimal::add);

            BigDecimal restockUnitCost = resolveReturnUnitCost(finishedGood, quantity, dispatchMovementsByFg, invoiceLine);

            restockFinishedGood(
                    finishedGood,
                    quantity,
                    invoice.getInvoiceNumber(),
                    restockUnitCost
            );
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
            FinishedGood finishedGood = finishedGoodsByCode.computeIfAbsent(
                    invoiceLine.getProductCode(),
                    code -> lockFinishedGood(company, code)
            );

            Long revenueAccountId = finishedGood.getRevenueAccountId();
            if (revenueAccountId == null) {
                throw new IllegalStateException("Finished good " + finishedGood.getProductCode()
                        + " missing revenue account for sales return");
            }
            BigDecimal baseAmount = currency(MoneyUtils.safeMultiply(perUnitBase(invoiceLine), quantity));
            BigDecimal discountAmount = currency(MoneyUtils.safeMultiply(perUnitDiscount(invoiceLine, gstInclusive), quantity));
            BigDecimal grossAmount = baseAmount.add(discountAmount);
            if (grossAmount.compareTo(BigDecimal.ZERO) > 0) {
                returnLines.merge(revenueAccountId, grossAmount, BigDecimal::add);
            }
            if (discountAmount.compareTo(BigDecimal.ZERO) > 0) {
                Long discountAccountId = finishedGood.getDiscountAccountId();
                if (discountAccountId == null) {
                    throw new IllegalStateException("Discount account is required when return line has a discount for "
                            + finishedGood.getProductCode());
                }
                returnLines.merge(discountAccountId, discountAmount.negate(), BigDecimal::add);
            }

            BigDecimal taxPerUnit = perUnitTax(invoiceLine);
            BigDecimal taxAmount = currency(MoneyUtils.safeMultiply(taxPerUnit, quantity));
            if (taxAmount.compareTo(BigDecimal.ZERO) > 0) {
                if (gstOutputAccountId == null) {
                    gstOutputAccountId = companyAccountingSettingsService.requireTaxAccounts().outputTaxAccountId();
                }
                if (finishedGood.getTaxAccountId() != null && !finishedGood.getTaxAccountId().equals(gstOutputAccountId)) {
                    throw new IllegalStateException("Finished good " + finishedGood.getProductCode()
                            + " tax account must match GST output account");
                }
                returnLines.merge(gstOutputAccountId, taxAmount, BigDecimal::add);
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
            postCogsReversal(invoice, totalReturnQtyByFg, dispatchMovementsByFg);
        }
        return salesReturnEntry;
    }

    private void restockFinishedGood(FinishedGood finishedGood, BigDecimal quantity, String invoiceNumber, BigDecimal unitCost) {
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
        movement.setReferenceType(SALES_RETURN_REFERENCE);
        movement.setReferenceId(invoiceNumber);
        movement.setMovementType("RETURN");
        movement.setQuantity(quantity);
        movement.setUnitCost(unitCost);
        inventoryMovementRepository.save(movement);
    }

    private BigDecimal perUnitTax(InvoiceLine line) {
        BigDecimal quantity = line.getQuantity();
        if (quantity == null || quantity.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal storedTax = line.getTaxAmount();
        if (storedTax != null) {
            return MoneyUtils.safeDivide(storedTax, quantity, 4, RoundingMode.HALF_UP);
        }
        BigDecimal total = Optional.ofNullable(line.getLineTotal()).orElse(BigDecimal.ZERO);
        BigDecimal gross = MoneyUtils.safeMultiply(line.getUnitPrice(), quantity);
        BigDecimal discount = MoneyUtils.zeroIfNull(line.getDiscountAmount());
        BigDecimal net = gross.subtract(discount);
        BigDecimal taxTotal = total.subtract(net);
        return MoneyUtils.safeDivide(taxTotal, quantity, 4, RoundingMode.HALF_UP);
    }

    private BigDecimal perUnitBase(InvoiceLine line) {
        BigDecimal quantity = line.getQuantity();
        if (quantity == null || quantity.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal taxableAmount = line.getTaxableAmount();
        if (taxableAmount != null) {
            return MoneyUtils.safeDivide(taxableAmount, quantity, 4, RoundingMode.HALF_UP);
        }
        BigDecimal gross = MoneyUtils.safeMultiply(line.getUnitPrice(), quantity);
        BigDecimal discount = MoneyUtils.zeroIfNull(line.getDiscountAmount());
        BigDecimal net = gross.subtract(discount);
        return MoneyUtils.safeDivide(net, quantity, 4, RoundingMode.HALF_UP);
    }

    private BigDecimal perUnitDiscount(InvoiceLine line, boolean gstInclusive) {
        BigDecimal quantity = line.getQuantity();
        if (quantity == null || quantity.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal discount = MoneyUtils.zeroIfNull(line.getDiscountAmount());
        if (gstInclusive && discount.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal rate = line.getTaxRate() != null ? line.getTaxRate() : BigDecimal.ZERO;
            if (rate.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal divisor = BigDecimal.ONE.add(rate.divide(new BigDecimal("100"), 6, RoundingMode.HALF_UP));
                if (divisor.compareTo(BigDecimal.ZERO) > 0) {
                    discount = discount.divide(divisor, 6, RoundingMode.HALF_UP);
                }
            }
        }
        return MoneyUtils.safeDivide(discount, quantity, 4, RoundingMode.HALF_UP);
    }

    private BigDecimal currency(BigDecimal value) {
        if (value == null) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private void postCogsReversal(Invoice invoice,
                                  Map<Long, BigDecimal> returnQuantitiesByFinishedGood,
                                  Map<Long, java.util.List<InventoryMovement>> dispatchMovements) {
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
                accountingFacade.postInventoryAdjustment(
                        "SALES_RETURN_COGS",
                        ref,
                        cogsAcctId,
                        Map.of(invAcctId, reversalAmount),
                        true,
                        false,
                        "COGS reversal for return " + invoice.getInvoiceNumber()
                );
            }
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

    private Map<Long, BigDecimal> loadReturnMovements(String invoiceNumber) {
        if (invoiceNumber == null) {
            return Map.of();
        }
        List<InventoryMovement> movements = inventoryMovementRepository
                .findByReferenceTypeAndReferenceIdOrderByCreatedAtAsc(SALES_RETURN_REFERENCE, invoiceNumber);
        if (movements == null || movements.isEmpty()) {
            return Map.of();
        }
        Map<Long, BigDecimal> totals = new LinkedHashMap<>();
        for (InventoryMovement movement : movements) {
            FinishedGood finishedGood = movement.getFinishedGood();
            if (finishedGood == null || finishedGood.getId() == null) {
                continue;
            }
            BigDecimal quantity = movement.getQuantity() != null ? movement.getQuantity() : BigDecimal.ZERO;
            totals.merge(finishedGood.getId(), quantity, BigDecimal::add);
        }
        return totals;
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
