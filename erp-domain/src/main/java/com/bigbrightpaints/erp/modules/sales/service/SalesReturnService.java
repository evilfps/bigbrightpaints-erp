package com.bigbrightpaints.erp.modules.sales.service;

import com.bigbrightpaints.erp.core.idempotency.IdempotencySignatureBuilder;
import com.bigbrightpaints.erp.core.idempotency.IdempotencyUtils;
import com.bigbrightpaints.erp.core.validation.ValidationUtils;
import com.bigbrightpaints.erp.core.util.CompanyTime;
import com.bigbrightpaints.erp.core.util.MoneyUtils;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalCorrectionType;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryRepository;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryDto;
import com.bigbrightpaints.erp.modules.accounting.dto.SalesReturnRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.SalesReturnPreviewDto;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingFacade;
import com.bigbrightpaints.erp.modules.accounting.service.CompanyAccountingSettingsService;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGood;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodBatch;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodBatchRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryReference;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryBatchSource;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryMovement;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryMovementRepository;
import com.bigbrightpaints.erp.modules.inventory.service.BatchNumberService;
import com.bigbrightpaints.erp.modules.inventory.service.FinishedGoodsService;
import com.bigbrightpaints.erp.modules.invoice.domain.Invoice;
import com.bigbrightpaints.erp.modules.invoice.domain.InvoiceLine;
import com.bigbrightpaints.erp.modules.invoice.domain.InvoiceRepository;
import com.bigbrightpaints.erp.modules.sales.domain.Dealer;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Comparator;
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
    private static final String SALES_RETURN_LINE_SEPARATOR = ":";
    private static final String SALES_RETURN_KEY_SEPARATOR = ":RET-";
    private static final BigDecimal DISCOUNT_TOLERANCE = new BigDecimal("0.02");

    private final CompanyContextService companyContextService;
    private final FinishedGoodRepository finishedGoodRepository;
    private final FinishedGoodBatchRepository finishedGoodBatchRepository;
    private final InventoryMovementRepository inventoryMovementRepository;
    private final BatchNumberService batchNumberService;
    private final AccountingFacade accountingFacade;
    private final JournalEntryRepository journalEntryRepository;
    private final InvoiceRepository invoiceRepository;
    private final CompanyAccountingSettingsService companyAccountingSettingsService;
    private final FinishedGoodsService finishedGoodsService;

    public SalesReturnService(CompanyContextService companyContextService,
                              FinishedGoodRepository finishedGoodRepository,
                              FinishedGoodBatchRepository finishedGoodBatchRepository,
                              InventoryMovementRepository inventoryMovementRepository,
                              BatchNumberService batchNumberService,
                              AccountingFacade accountingFacade,
                              JournalEntryRepository journalEntryRepository,
                              InvoiceRepository invoiceRepository,
                              CompanyAccountingSettingsService companyAccountingSettingsService,
                              FinishedGoodsService finishedGoodsService) {
        this.companyContextService = companyContextService;
        this.finishedGoodRepository = finishedGoodRepository;
        this.finishedGoodBatchRepository = finishedGoodBatchRepository;
        this.inventoryMovementRepository = inventoryMovementRepository;
        this.batchNumberService = batchNumberService;
        this.accountingFacade = accountingFacade;
        this.journalEntryRepository = journalEntryRepository;
        this.invoiceRepository = invoiceRepository;
        this.companyAccountingSettingsService = companyAccountingSettingsService;
        this.finishedGoodsService = finishedGoodsService;
    }

    @Transactional
    public SalesReturnPreviewDto previewReturn(SalesReturnRequest request) {
        Company company = companyContextService.requireCurrentCompany();
        Invoice invoice = invoiceRepository.lockByCompanyAndId(company, request.invoiceId())
                .orElseThrow(() -> ValidationUtils.invalidInput("Invoice not found: id=" + request.invoiceId()));
        ensurePostedInvoice(invoice);
        Map<Long, InvoiceLine> invoiceLines = invoice.getLines().stream()
                .collect(Collectors.toMap(InvoiceLine::getId, line -> line));
        if (request.lines() == null || request.lines().isEmpty()) {
            throw ValidationUtils.invalidInput("Return lines are required");
        }

        Map<String, FinishedGood> finishedGoodsByCode = new HashMap<>();
        for (SalesReturnRequest.ReturnLine lineRequest : request.lines()) {
            InvoiceLine invoiceLine = invoiceLines.get(lineRequest.invoiceLineId());
            if (invoiceLine == null) {
                throw ValidationUtils.invalidInput("Invoice line not found: " + lineRequest.invoiceLineId());
            }
            finishedGoodsByCode.computeIfAbsent(
                    invoiceLine.getProductCode(),
                    code -> lockFinishedGood(company, code)
            );
        }

        validateReturnQuantities(company, invoice, request, invoiceLines, finishedGoodsByCode);
        Map<Long, java.util.List<InventoryMovement>> dispatchMovementsByFg = invoice.getSalesOrder() != null
                ? loadDispatchMovements(company, invoice.getSalesOrder().getId())
                : Map.of();

        java.util.List<SalesReturnPreviewDto.LinePreview> previews = new java.util.ArrayList<>();
        BigDecimal totalReturnAmount = BigDecimal.ZERO;
        BigDecimal totalInventoryValue = BigDecimal.ZERO;
        ReturnMovementSummary existingReturns = loadReturnMovements(company, invoice.getInvoiceNumber());
        for (SalesReturnRequest.ReturnLine lineRequest : request.lines()) {
            InvoiceLine invoiceLine = invoiceLines.get(lineRequest.invoiceLineId());
            if (invoiceLine == null) {
                continue;
            }
            BigDecimal quantity = ValidationUtils.requirePositive(lineRequest.quantity(), "lines.quantity");
            FinishedGood finishedGood = finishedGoodsByCode.computeIfAbsent(
                    invoiceLine.getProductCode(),
                    code -> lockFinishedGood(company, code)
            );
            BigDecimal baseAmount = currency(MoneyUtils.safeMultiply(perUnitBase(invoiceLine), quantity));
            BigDecimal taxAmount = currency(MoneyUtils.safeMultiply(perUnitTax(invoiceLine), quantity));
            BigDecimal lineAmount = baseAmount.add(taxAmount);
            BigDecimal inventoryUnitCost = resolveReturnUnitCost(finishedGood, quantity, dispatchMovementsByFg, invoiceLine);
            BigDecimal inventoryValue = currency(MoneyUtils.safeMultiply(inventoryUnitCost, quantity));
            BigDecimal alreadyReturned = existingReturns.byInvoiceLineId().getOrDefault(invoiceLine.getId(), BigDecimal.ZERO);
            BigDecimal remainingAfterReturn = quantityValue(invoiceLine.getQuantity())
                    .subtract(alreadyReturned)
                    .subtract(quantity);

            previews.add(new SalesReturnPreviewDto.LinePreview(
                    invoiceLine.getId(),
                    invoiceLine.getProductCode(),
                    quantity,
                    alreadyReturned,
                    remainingAfterReturn.max(BigDecimal.ZERO),
                    lineAmount,
                    taxAmount,
                    inventoryUnitCost,
                    inventoryValue
            ));
            totalReturnAmount = totalReturnAmount.add(lineAmount);
            totalInventoryValue = totalInventoryValue.add(inventoryValue);
        }

        return new SalesReturnPreviewDto(
                invoice.getId(),
                invoice.getInvoiceNumber(),
                currency(totalReturnAmount),
                currency(totalInventoryValue),
                List.copyOf(previews)
        );
    }

    @Transactional
    public JournalEntryDto processReturn(SalesReturnRequest request) {
        Company company = companyContextService.requireCurrentCompany();
        Invoice invoice = invoiceRepository.lockByCompanyAndId(company, request.invoiceId())
                .orElseThrow(() -> com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput("Invoice not found: id=" + request.invoiceId()));
        ensurePostedInvoice(invoice);
        Dealer dealer = invoice.getDealer();
        if (dealer == null || dealer.getReceivableAccount() == null) {
            throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidState("Invoice is missing dealer receivable context");
        }
        boolean gstInclusive = invoice.getSalesOrder() != null && invoice.getSalesOrder().isGstInclusive();
        Map<Long, InvoiceLine> invoiceLines = invoice.getLines().stream()
                .collect(Collectors.toMap(InvoiceLine::getId, line -> line));
        if (request.lines() == null || request.lines().isEmpty()) {
            throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput("Return lines are required");
        }
        String returnKey = buildReturnIdempotencyKey(invoice, request);

        Map<Long, BigDecimal> requestedReturnQtyByLine = new LinkedHashMap<>();
        Map<Long, BigDecimal> requestedReturnQtyByFg = new LinkedHashMap<>();
        Map<Long, BigDecimal> invoicedQtyByFg = new LinkedHashMap<>();
        Map<Long, List<Long>> invoiceLineIdsByFg = new LinkedHashMap<>();
        Map<String, FinishedGood> finishedGoodsByCode = new HashMap<>();
        Map<Long, FinishedGood> finishedGoodsById = new HashMap<>();
        Set<String> returnProductCodes = new HashSet<>();

        for (SalesReturnRequest.ReturnLine lineRequest : request.lines()) {
            InvoiceLine invoiceLine = invoiceLines.get(lineRequest.invoiceLineId());
            if (invoiceLine == null) {
                throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput("Invoice line not found: " + lineRequest.invoiceLineId());
            }
            BigDecimal quantity = ValidationUtils.requirePositive(lineRequest.quantity(), "lines.quantity");
            if (quantity.compareTo(invoiceLine.getQuantity()) > 0) {
                throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput("Return quantity exceeds invoiced amount for " + invoiceLine.getProductCode());
            }
            requestedReturnQtyByLine.merge(invoiceLine.getId(), quantity, BigDecimal::add);
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
            if (invoiceLine.getId() != null) {
                invoiceLineIdsByFg.computeIfAbsent(finishedGood.getId(), id -> new java.util.ArrayList<>())
                        .add(invoiceLine.getId());
            }
        }

        boolean returnAlreadyProcessed = false;
        if (StringUtils.hasText(returnKey)) {
            String marker = SALES_RETURN_KEY_SEPARATOR + returnKey;
            returnAlreadyProcessed = inventoryMovementRepository
                    .existsByFinishedGood_CompanyAndReferenceTypeAndReferenceIdContainingIgnoreCase(
                            company, SALES_RETURN_REFERENCE, marker);
        }
        if (!returnAlreadyProcessed) {
            ReturnMovementSummary existingReturns = loadReturnMovements(company, invoice.getInvoiceNumber());
            Map<Long, BigDecimal> existingReturnsByLine = existingReturns.byInvoiceLineId();
            Map<Long, BigDecimal> legacyReturnsByLine = new LinkedHashMap<>();
            Map<Long, BigDecimal> legacyReturnsByFg = new LinkedHashMap<>();
            Map<Long, BigDecimal> existingReturnsByFg = existingReturns.byFinishedGoodId();
            existingReturnsByFg.forEach(legacyReturnsByFg::put);
            for (Map.Entry<Long, BigDecimal> entry : existingReturnsByLine.entrySet()) {
                InvoiceLine invoiceLine = invoiceLines.get(entry.getKey());
                if (invoiceLine == null || invoiceLine.getProductCode() == null) {
                    continue;
                }
                if (!returnProductCodes.contains(invoiceLine.getProductCode())) {
                    continue;
                }
                FinishedGood finishedGood = finishedGoodsByCode.get(invoiceLine.getProductCode());
                if (finishedGood == null || finishedGood.getId() == null) {
                    continue;
                }
                legacyReturnsByFg.merge(finishedGood.getId(), entry.getValue().negate(), BigDecimal::add);
            }
            for (Map.Entry<Long, BigDecimal> entry : legacyReturnsByFg.entrySet()) {
                BigDecimal remainingLegacy = entry.getValue();
                if (remainingLegacy == null || remainingLegacy.compareTo(BigDecimal.ZERO) <= 0) {
                    continue;
                }
                List<Long> lineIds = invoiceLineIdsByFg.getOrDefault(entry.getKey(), List.of());
                if (lineIds.isEmpty()) {
                    continue;
                }
                java.util.List<Long> orderedLineIds = new java.util.ArrayList<>(lineIds);
                orderedLineIds.sort(java.util.Comparator.naturalOrder());
                for (Long lineId : orderedLineIds) {
                    InvoiceLine invoiceLine = invoiceLines.get(lineId);
                    if (invoiceLine == null) {
                        continue;
                    }
                    BigDecimal lineQty = invoiceLine.getQuantity() != null ? invoiceLine.getQuantity() : BigDecimal.ZERO;
                    BigDecimal lineReturned = existingReturnsByLine.getOrDefault(lineId, BigDecimal.ZERO);
                    BigDecimal lineRemaining = lineQty.subtract(lineReturned);
                    if (lineRemaining.compareTo(BigDecimal.ZERO) <= 0) {
                        continue;
                    }
                    BigDecimal allocate = remainingLegacy.min(lineRemaining);
                    if (allocate.compareTo(BigDecimal.ZERO) > 0) {
                        legacyReturnsByLine.merge(lineId, allocate, BigDecimal::add);
                        remainingLegacy = remainingLegacy.subtract(allocate);
                    }
                    if (remainingLegacy.compareTo(BigDecimal.ZERO) <= 0) {
                        break;
                    }
                }
            }
            for (Map.Entry<Long, BigDecimal> entry : requestedReturnQtyByLine.entrySet()) {
                InvoiceLine invoiceLine = invoiceLines.get(entry.getKey());
                if (invoiceLine == null) {
                    continue;
                }
                BigDecimal invoicedQty = invoiceLine.getQuantity() != null ? invoiceLine.getQuantity() : BigDecimal.ZERO;
                BigDecimal priorReturnedQty = existingReturnsByLine.getOrDefault(entry.getKey(), BigDecimal.ZERO);
                BigDecimal legacyReturnedQty = legacyReturnsByLine.getOrDefault(entry.getKey(), BigDecimal.ZERO);
                if (legacyReturnedQty.compareTo(BigDecimal.ZERO) > 0) {
                    priorReturnedQty = priorReturnedQty.add(legacyReturnedQty);
                }
                if (priorReturnedQty.add(entry.getValue()).compareTo(invoicedQty) > 0) {
                    throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput("Return quantity exceeds remaining invoiced amount for " + invoiceLine.getProductCode());
                }
            }

            for (Map.Entry<Long, BigDecimal> entry : requestedReturnQtyByFg.entrySet()) {
                BigDecimal invoicedQty = invoicedQtyByFg.getOrDefault(entry.getKey(), BigDecimal.ZERO);
                BigDecimal priorReturnedQty = existingReturnsByFg.getOrDefault(entry.getKey(), BigDecimal.ZERO);
                if (priorReturnedQty.add(entry.getValue()).compareTo(invoicedQty) > 0) {
                    FinishedGood finishedGood = finishedGoodsById.get(entry.getKey());
                    String productCode = finishedGood != null ? finishedGood.getProductCode() : entry.getKey().toString();
                    throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput("Return quantity exceeds remaining invoiced amount for " + productCode);
                }
            }
        }

        BigDecimal receivableCredit = BigDecimal.ZERO;
        Map<Long, BigDecimal> totalReturnQtyByFg = new LinkedHashMap<>();
        Long salesOrderId = invoice.getSalesOrder() != null ? invoice.getSalesOrder().getId() : null;
        // Build return amount and line totals
        for (SalesReturnRequest.ReturnLine lineRequest : request.lines()) {
            InvoiceLine invoiceLine = invoiceLines.get(lineRequest.invoiceLineId());
            if (invoiceLine == null) {
                throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput("Invoice line not found: " + lineRequest.invoiceLineId());
            }
            BigDecimal quantity = ValidationUtils.requirePositive(lineRequest.quantity(), "lines.quantity");
            if (quantity.compareTo(invoiceLine.getQuantity()) > 0) {
                throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput("Return quantity exceeds invoiced amount for " + invoiceLine.getProductCode());
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
        }

        if (receivableCredit.compareTo(BigDecimal.ZERO) <= 0) {
            throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput("Return amount must be greater than zero");
        }

        Map<Long, BigDecimal> returnLines = buildReturnLines(request, invoiceLines, finishedGoodsByCode, company, gstInclusive);

        if (returnAlreadyProcessed) {
            JournalEntryDto replayEntry = accountingFacade.postSalesReturn(
                    dealer.getId(),
                    invoice.getInvoiceNumber(),
                    returnLines,
                    receivableCredit,
                    request.reason()
            );
            ensureLinkedCorrectionJournal(company, replayEntry, invoice.getJournalEntry(), invoice.getInvoiceNumber());
            relinkExistingReturnMovements(company, invoice.getInvoiceNumber(), request, replayEntry != null ? replayEntry.id() : null, returnKey);
            return replayEntry;
        }

        Map<Long, java.util.List<InventoryMovement>> dispatchMovementsByFg = salesOrderId != null
                ? loadDispatchMovements(company, salesOrderId)
                : Map.of();
        Map<Long, BigDecimal> restockCostByLine = new LinkedHashMap<>();
        for (SalesReturnRequest.ReturnLine lineRequest : request.lines()) {
            InvoiceLine invoiceLine = invoiceLines.get(lineRequest.invoiceLineId());
            if (invoiceLine == null) {
                continue;
            }
            BigDecimal quantity = ValidationUtils.requirePositive(lineRequest.quantity(), "lines.quantity");
            FinishedGood finishedGood = finishedGoodsByCode.computeIfAbsent(
                    invoiceLine.getProductCode(),
                    code -> lockFinishedGood(company, code)
            );
            BigDecimal restockUnitCost = resolveReturnUnitCost(finishedGood, quantity, dispatchMovementsByFg, invoiceLine);
            restockCostByLine.put(invoiceLine.getId(), restockUnitCost);
        }

        JournalEntryDto salesReturnEntry = accountingFacade.postSalesReturn(
                dealer.getId(),
                invoice.getInvoiceNumber(),
                returnLines,
                receivableCredit,
                request.reason()
        );
        ensureLinkedCorrectionJournal(company, salesReturnEntry, invoice.getJournalEntry(), invoice.getInvoiceNumber());

        if (!returnAlreadyProcessed) {
            // Process returns and restock inventory
            for (SalesReturnRequest.ReturnLine lineRequest : request.lines()) {
                InvoiceLine invoiceLine = invoiceLines.get(lineRequest.invoiceLineId());
                if (invoiceLine == null) {
                    continue;
                }
                BigDecimal quantity = ValidationUtils.requirePositive(lineRequest.quantity(), "lines.quantity");
                FinishedGood finishedGood = finishedGoodsByCode.computeIfAbsent(
                        invoiceLine.getProductCode(),
                        code -> lockFinishedGood(company, code)
                );

                BigDecimal restockUnitCost = restockCostByLine.getOrDefault(invoiceLine.getId(), BigDecimal.ZERO);
                restockFinishedGood(
                        finishedGood,
                        quantity,
                        buildReturnReference(invoice.getInvoiceNumber(), invoiceLine.getId(), returnKey),
                        restockUnitCost,
                        salesReturnEntry != null ? salesReturnEntry.id() : null
                );
            }

            // Reverse COGS and restore inventory value using dispatch cost
            if (salesOrderId != null && !totalReturnQtyByFg.isEmpty()) {
                postCogsReversal(invoice, totalReturnQtyByFg, dispatchMovementsByFg);
            }
        }
        return salesReturnEntry;
    }

    private Map<Long, BigDecimal> buildReturnLines(SalesReturnRequest request,
                                                   Map<Long, InvoiceLine> invoiceLines,
                                                   Map<String, FinishedGood> finishedGoodsByCode,
                                                   Company company,
                                                   boolean gstInclusive) {
        Long gstOutputAccountId = null;
        Map<Long, BigDecimal> returnLines = new LinkedHashMap<>();
        for (SalesReturnRequest.ReturnLine lineRequest : request.lines()) {
            InvoiceLine invoiceLine = invoiceLines.get(lineRequest.invoiceLineId());
            if (invoiceLine == null) {
                continue;
            }
            BigDecimal quantity = ValidationUtils.requirePositive(lineRequest.quantity(), "lines.quantity");
            FinishedGood finishedGood = finishedGoodsByCode.computeIfAbsent(
                    invoiceLine.getProductCode(),
                    code -> lockFinishedGood(company, code)
            );

            Long revenueAccountId = finishedGood.getRevenueAccountId();
            if (revenueAccountId == null) {
                throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidState("Finished good " + finishedGood.getProductCode()
                        + " missing revenue account for sales return");
            }
            BigDecimal baseAmount = currency(MoneyUtils.safeMultiply(perUnitBase(invoiceLine), quantity));
            boolean discountTaxInclusive = isDiscountTaxInclusive(invoiceLine, gstInclusive);
            BigDecimal discountAmount = currency(MoneyUtils.safeMultiply(perUnitDiscount(invoiceLine, discountTaxInclusive), quantity));
            BigDecimal grossAmount = baseAmount.add(discountAmount);
            if (grossAmount.compareTo(BigDecimal.ZERO) > 0) {
                returnLines.merge(revenueAccountId, grossAmount, BigDecimal::add);
            }
            if (discountAmount.compareTo(BigDecimal.ZERO) > 0) {
                Long discountAccountId = finishedGood.getDiscountAccountId();
                if (discountAccountId == null) {
                    throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidState("Discount account is required when return line has a discount for "
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
                    throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidState("Finished good " + finishedGood.getProductCode()
                            + " tax account must match GST output account");
                }
                returnLines.merge(gstOutputAccountId, taxAmount, BigDecimal::add);
            }
        }
        return returnLines;
    }

    private void restockFinishedGood(FinishedGood finishedGood,
                                     BigDecimal quantity,
                                     String invoiceNumber,
                                     BigDecimal unitCost,
                                     Long journalEntryId) {
        FinishedGood fg = lockFinishedGood(finishedGood.getCompany(), finishedGood.getId());
        BigDecimal currentStock = fg.getCurrentStock() != null ? fg.getCurrentStock() : BigDecimal.ZERO;
        fg.setCurrentStock(currentStock.add(quantity));
        finishedGoodRepository.save(fg);
        finishedGoodsService.invalidateWeightedAverageCost(fg.getId());

        FinishedGoodBatch returnBatch = new FinishedGoodBatch();
        returnBatch.setFinishedGood(fg);
        returnBatch.setBatchCode(batchNumberService.nextFinishedGoodBatchCode(fg, CompanyTime.today(fg.getCompany())));
        returnBatch.setQuantityTotal(quantity);
        returnBatch.setQuantityAvailable(quantity);
        returnBatch.setUnitCost(unitCost);
        returnBatch.setManufacturedAt(CompanyTime.now(fg.getCompany()));
        returnBatch.setSource(InventoryBatchSource.ADJUSTMENT);
        FinishedGoodBatch savedBatch = finishedGoodBatchRepository.save(returnBatch);

        InventoryMovement movement = new InventoryMovement();
        movement.setFinishedGood(fg);
        movement.setFinishedGoodBatch(savedBatch);
        movement.setReferenceType(SALES_RETURN_REFERENCE);
        movement.setReferenceId(invoiceNumber);
        movement.setMovementType("RETURN");
        movement.setQuantity(quantity);
        movement.setUnitCost(unitCost);
        movement.setJournalEntryId(journalEntryId);
        inventoryMovementRepository.save(movement);
    }

    private void ensurePostedInvoice(Invoice invoice) {
        if (invoice == null) {
            return;
        }
        if (invoice.getJournalEntry() == null || invoice.getJournalEntry().getId() == null) {
            throw ValidationUtils.invalidState(
                    "Only posted invoices can be corrected through sales return");
        }
        String status = invoice.getStatus();
        if (!StringUtils.hasText(status)
                || "DRAFT".equalsIgnoreCase(status)
                || "VOID".equalsIgnoreCase(status)
                || "REVERSED".equalsIgnoreCase(status)) {
            throw ValidationUtils.invalidState(
                    "Only posted invoices can be corrected through sales return");
        }
    }

    private void validateReturnQuantities(Company company,
                                          Invoice invoice,
                                          SalesReturnRequest request,
                                          Map<Long, InvoiceLine> invoiceLines,
                                          Map<String, FinishedGood> finishedGoodsByCode) {
        Map<Long, BigDecimal> requestedReturnQtyByLine = new LinkedHashMap<>();
        Map<Long, BigDecimal> requestedReturnQtyByFg = new LinkedHashMap<>();
        Map<Long, BigDecimal> invoicedQtyByFg = new LinkedHashMap<>();
        Map<Long, List<Long>> invoiceLineIdsByFg = new LinkedHashMap<>();
        Map<Long, FinishedGood> finishedGoodsById = new HashMap<>();
        Set<String> returnProductCodes = new HashSet<>();

        for (SalesReturnRequest.ReturnLine lineRequest : request.lines()) {
            InvoiceLine invoiceLine = invoiceLines.get(lineRequest.invoiceLineId());
            if (invoiceLine == null) {
                throw ValidationUtils.invalidInput("Invoice line not found: " + lineRequest.invoiceLineId());
            }
            BigDecimal quantity = ValidationUtils.requirePositive(lineRequest.quantity(), "lines.quantity");
            if (quantity.compareTo(quantityValue(invoiceLine.getQuantity())) > 0) {
                throw ValidationUtils.invalidInput("Return quantity exceeds invoiced amount for " + invoiceLine.getProductCode());
            }
            requestedReturnQtyByLine.merge(invoiceLine.getId(), quantity, BigDecimal::add);
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
            BigDecimal invoicedQty = quantityValue(invoiceLine.getQuantity());
            invoicedQtyByFg.merge(finishedGood.getId(), invoicedQty, BigDecimal::add);
            if (invoiceLine.getId() != null) {
                invoiceLineIdsByFg.computeIfAbsent(finishedGood.getId(), id -> new java.util.ArrayList<>())
                        .add(invoiceLine.getId());
            }
        }

        ReturnMovementSummary existingReturns = loadReturnMovements(company, invoice.getInvoiceNumber());
        Map<Long, BigDecimal> existingReturnsByLine = existingReturns.byInvoiceLineId();
        Map<Long, BigDecimal> legacyReturnsByLine = new LinkedHashMap<>();
        Map<Long, BigDecimal> legacyReturnsByFg = new LinkedHashMap<>();
        Map<Long, BigDecimal> existingReturnsByFg = existingReturns.byFinishedGoodId();
        existingReturnsByFg.forEach(legacyReturnsByFg::put);
        for (Map.Entry<Long, BigDecimal> entry : existingReturnsByLine.entrySet()) {
            InvoiceLine invoiceLine = invoiceLines.get(entry.getKey());
            if (invoiceLine == null || invoiceLine.getProductCode() == null) {
                continue;
            }
            if (!returnProductCodes.contains(invoiceLine.getProductCode())) {
                continue;
            }
            FinishedGood finishedGood = finishedGoodsByCode.get(invoiceLine.getProductCode());
            if (finishedGood == null || finishedGood.getId() == null) {
                continue;
            }
            legacyReturnsByFg.merge(finishedGood.getId(), entry.getValue().negate(), BigDecimal::add);
        }
        for (Map.Entry<Long, BigDecimal> entry : legacyReturnsByFg.entrySet()) {
            BigDecimal remainingLegacy = entry.getValue();
            if (remainingLegacy == null || remainingLegacy.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            List<Long> lineIds = invoiceLineIdsByFg.getOrDefault(entry.getKey(), List.of());
            if (lineIds.isEmpty()) {
                continue;
            }
            java.util.List<Long> orderedLineIds = new java.util.ArrayList<>(lineIds);
            orderedLineIds.sort(java.util.Comparator.naturalOrder());
            for (Long lineId : orderedLineIds) {
                InvoiceLine invoiceLine = invoiceLines.get(lineId);
                if (invoiceLine == null) {
                    continue;
                }
                BigDecimal lineQty = quantityValue(invoiceLine.getQuantity());
                BigDecimal lineReturned = existingReturnsByLine.getOrDefault(lineId, BigDecimal.ZERO);
                BigDecimal lineRemaining = lineQty.subtract(lineReturned);
                if (lineRemaining.compareTo(BigDecimal.ZERO) <= 0) {
                    continue;
                }
                BigDecimal allocate = remainingLegacy.min(lineRemaining);
                if (allocate.compareTo(BigDecimal.ZERO) > 0) {
                    legacyReturnsByLine.merge(lineId, allocate, BigDecimal::add);
                    remainingLegacy = remainingLegacy.subtract(allocate);
                }
                if (remainingLegacy.compareTo(BigDecimal.ZERO) <= 0) {
                    break;
                }
            }
        }
        for (Map.Entry<Long, BigDecimal> entry : requestedReturnQtyByLine.entrySet()) {
            InvoiceLine invoiceLine = invoiceLines.get(entry.getKey());
            if (invoiceLine == null) {
                continue;
            }
            BigDecimal invoicedQty = quantityValue(invoiceLine.getQuantity());
            BigDecimal priorReturnedQty = existingReturnsByLine.getOrDefault(entry.getKey(), BigDecimal.ZERO);
            BigDecimal legacyReturnedQty = legacyReturnsByLine.getOrDefault(entry.getKey(), BigDecimal.ZERO);
            if (legacyReturnedQty.compareTo(BigDecimal.ZERO) > 0) {
                priorReturnedQty = priorReturnedQty.add(legacyReturnedQty);
            }
            if (priorReturnedQty.add(entry.getValue()).compareTo(invoicedQty) > 0) {
                throw ValidationUtils.invalidInput("Return quantity exceeds remaining invoiced amount for " + invoiceLine.getProductCode());
            }
        }

        for (Map.Entry<Long, BigDecimal> entry : requestedReturnQtyByFg.entrySet()) {
            BigDecimal invoicedQty = invoicedQtyByFg.getOrDefault(entry.getKey(), BigDecimal.ZERO);
            BigDecimal priorReturnedQty = existingReturnsByFg.getOrDefault(entry.getKey(), BigDecimal.ZERO);
            if (priorReturnedQty.add(entry.getValue()).compareTo(invoicedQty) > 0) {
                FinishedGood finishedGood = finishedGoodsById.get(entry.getKey());
                String productCode = finishedGood != null ? finishedGood.getProductCode() : entry.getKey().toString();
                throw ValidationUtils.invalidInput("Return quantity exceeds remaining invoiced amount for " + productCode);
            }
        }
    }

    private void ensureLinkedCorrectionJournal(Company company,
                                               JournalEntryDto salesReturnEntry,
                                               JournalEntry sourceJournal,
                                               String invoiceNumber) {
        if (salesReturnEntry == null || salesReturnEntry.id() == null || sourceJournal == null || sourceJournal.getId() == null) {
            return;
        }
        journalEntryRepository.findByCompanyAndId(company, salesReturnEntry.id())
                .ifPresent(entry -> {
                    boolean changed = false;
                    if (entry.getCorrectionType() != JournalCorrectionType.REVERSAL) {
                        entry.setCorrectionType(JournalCorrectionType.REVERSAL);
                        changed = true;
                    }
                    if (!"SALES_RETURN".equalsIgnoreCase(entry.getCorrectionReason())) {
                        entry.setCorrectionReason("SALES_RETURN");
                        changed = true;
                    }
                    if (!"SALES_RETURN".equalsIgnoreCase(entry.getSourceModule())) {
                        entry.setSourceModule("SALES_RETURN");
                        changed = true;
                    }
                    if (!invoiceNumber.equals(entry.getSourceReference())) {
                        entry.setSourceReference(invoiceNumber);
                        changed = true;
                    }
                    if (changed) {
                        journalEntryRepository.save(entry);
                    }
                });
    }

    private void relinkExistingReturnMovements(Company company,
                                               String invoiceNumber,
                                               SalesReturnRequest request,
                                               Long journalEntryId,
                                               String returnKey) {
        if (journalEntryId == null || request == null || request.lines() == null || request.lines().isEmpty()) {
            return;
        }
        List<InventoryMovement> existingMovements = inventoryMovementRepository
                .findByFinishedGood_CompanyAndReferenceTypeAndReferenceIdStartingWithOrderByCreatedAtAsc(
                        company,
                        SALES_RETURN_REFERENCE,
                        invoiceNumber + SALES_RETURN_LINE_SEPARATOR);
        if (existingMovements == null || existingMovements.isEmpty()) {
            return;
        }
        Set<String> expectedReferences = request.lines().stream()
                .map(line -> buildReturnReference(invoiceNumber, line.invoiceLineId(), returnKey))
                .collect(Collectors.toSet());
        boolean changed = false;
        for (InventoryMovement movement : existingMovements) {
            if (!expectedReferences.contains(movement.getReferenceId())) {
                continue;
            }
            if (!journalEntryId.equals(movement.getJournalEntryId())) {
                movement.setJournalEntryId(journalEntryId);
                changed = true;
            }
        }
        if (changed) {
            inventoryMovementRepository.saveAll(existingMovements);
        }
    }

    private BigDecimal quantityValue(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
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

    private BigDecimal perUnitDiscount(InvoiceLine line, boolean discountTaxInclusive) {
        BigDecimal quantity = line.getQuantity();
        if (quantity == null || quantity.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal discount = MoneyUtils.zeroIfNull(line.getDiscountAmount());
        if (discountTaxInclusive && discount.compareTo(BigDecimal.ZERO) > 0) {
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

    private boolean isDiscountTaxInclusive(InvoiceLine line, boolean orderGstInclusive) {
        if (line == null) {
            return orderGstInclusive;
        }
        BigDecimal discount = MoneyUtils.zeroIfNull(line.getDiscountAmount());
        if (discount.compareTo(BigDecimal.ZERO) <= 0) {
            return orderGstInclusive;
        }
        BigDecimal quantity = line.getQuantity();
        BigDecimal unitPrice = line.getUnitPrice();
        if (quantity == null || unitPrice == null) {
            return orderGstInclusive;
        }
        BigDecimal gross = MoneyUtils.safeMultiply(unitPrice, quantity);
        if (gross.compareTo(BigDecimal.ZERO) <= 0) {
            return orderGstInclusive;
        }
        BigDecimal lineTotal = line.getLineTotal();
        if (lineTotal == null) {
            lineTotal = MoneyUtils.safeAdd(line.getTaxableAmount(), line.getTaxAmount());
        }
        if (lineTotal == null) {
            return orderGstInclusive;
        }
        BigDecimal expectedNetRaw = gross.subtract(discount);
        return MoneyUtils.withinTolerance(lineTotal, expectedNetRaw, DISCOUNT_TOLERANCE);
    }

    private BigDecimal currency(BigDecimal value) {
        return MoneyUtils.roundCurrency(value);
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
            FinishedGood fg = finishedGoodRepository.findByCompanyAndId(invoice.getCompany(), fgId).orElse(null);
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

    private Map<Long, java.util.List<InventoryMovement>> loadDispatchMovements(Company company, Long salesOrderId) {
        return inventoryMovementRepository
                .findByFinishedGood_CompanyAndReferenceTypeAndReferenceIdOrderByCreatedAtAsc(
                        company,
                        InventoryReference.SALES_ORDER,
                        salesOrderId.toString())
                .stream()
                .filter(mv -> "DISPATCH".equalsIgnoreCase(mv.getMovementType()))
                .filter(mv -> mv.getFinishedGood() != null)
                .collect(Collectors.groupingBy(mv -> mv.getFinishedGood().getId(), LinkedHashMap::new, Collectors.toList()));
    }

    private ReturnMovementSummary loadReturnMovements(Company company, String invoiceNumber) {
        String normalized = invoiceNumber != null ? invoiceNumber.trim() : null;
        if (normalized == null || normalized.isEmpty()) {
            return ReturnMovementSummary.empty();
        }
        List<InventoryMovement> movements = new java.util.ArrayList<>();
        List<InventoryMovement> legacyMovements = inventoryMovementRepository
                .findByFinishedGood_CompanyAndReferenceTypeAndReferenceIdOrderByCreatedAtAsc(
                        company,
                        SALES_RETURN_REFERENCE,
                        normalized);
        if (legacyMovements != null && !legacyMovements.isEmpty()) {
            movements.addAll(legacyMovements);
        }
        String prefix = normalized + SALES_RETURN_LINE_SEPARATOR;
        List<InventoryMovement> lineMovements = inventoryMovementRepository
                .findByFinishedGood_CompanyAndReferenceTypeAndReferenceIdStartingWithOrderByCreatedAtAsc(
                        company,
                        SALES_RETURN_REFERENCE,
                        prefix);
        if (lineMovements != null && !lineMovements.isEmpty()) {
            movements.addAll(lineMovements);
        }
        if (movements.isEmpty()) {
            return ReturnMovementSummary.empty();
        }
        Map<Long, BigDecimal> totalsByLine = new LinkedHashMap<>();
        Map<Long, BigDecimal> totalsByFinishedGood = new LinkedHashMap<>();
        for (InventoryMovement movement : movements) {
            FinishedGood finishedGood = movement.getFinishedGood();
            if (finishedGood == null || finishedGood.getId() == null) {
                continue;
            }
            String referenceId = movement.getReferenceId();
            if (referenceId != null) {
                referenceId = referenceId.trim();
            }
            if (referenceId == null || referenceId.isEmpty()) {
                continue;
            }
            if (!referenceId.equals(normalized) && !referenceId.startsWith(prefix)) {
                continue;
            }
            BigDecimal quantity = movement.getQuantity() != null ? movement.getQuantity() : BigDecimal.ZERO;
            totalsByFinishedGood.merge(finishedGood.getId(), quantity, BigDecimal::add);
            if (referenceId.equals(normalized)) {
                continue;
            }
            String lineRemainder = referenceId.substring(prefix.length()).trim();
            int delimiter = lineRemainder.indexOf(SALES_RETURN_LINE_SEPARATOR);
            String lineIdText = delimiter >= 0 ? lineRemainder.substring(0, delimiter).trim() : lineRemainder;
            if (lineIdText.isEmpty()) {
                continue;
            }
            try {
                Long lineId = Long.parseLong(lineIdText);
                totalsByLine.merge(lineId, quantity, BigDecimal::add);
            } catch (NumberFormatException ignored) {
                // ignore invalid line ids
            }
        }
        return new ReturnMovementSummary(totalsByLine, totalsByFinishedGood);
    }

    private String buildReturnReference(String invoiceNumber, Long invoiceLineId) {
        return buildReturnReference(invoiceNumber, invoiceLineId, null);
    }

    private String buildReturnReference(String invoiceNumber, Long invoiceLineId, String returnKey) {
        String normalized = invoiceNumber != null ? invoiceNumber.trim() : null;
        if (normalized == null || normalized.isEmpty() || invoiceLineId == null) {
            return normalized;
        }
        String base = normalized + SALES_RETURN_LINE_SEPARATOR + invoiceLineId;
        if (!StringUtils.hasText(returnKey)) {
            return base;
        }
        return base + SALES_RETURN_KEY_SEPARATOR + returnKey;
    }

    private String buildReturnIdempotencyKey(Invoice invoice, SalesReturnRequest request) {
        if (invoice == null || request == null || request.lines() == null || request.lines().isEmpty()) {
            return null;
        }
        IdempotencySignatureBuilder signatureBuilder = IdempotencySignatureBuilder.create()
                .addUpperToken(invoice.getInvoiceNumber())
                .add(invoice.getId() != null ? invoice.getId() : "null")
                .addUpperToken(request.reason());
        List<SalesReturnRequest.ReturnLine> lines = request.lines().stream()
                .sorted(Comparator.comparing(SalesReturnRequest.ReturnLine::invoiceLineId))
                .toList();
        for (SalesReturnRequest.ReturnLine line : lines) {
            signatureBuilder.add((line.invoiceLineId() != null ? line.invoiceLineId() : "null")
                    + "=" + IdempotencyUtils.normalizeDecimal(line.quantity()));
        }
        return signatureBuilder.buildHash(12);
    }

    private record ReturnMovementSummary(Map<Long, BigDecimal> byInvoiceLineId,
                                         Map<Long, BigDecimal> byFinishedGoodId) {
        private static ReturnMovementSummary empty() {
            return new ReturnMovementSummary(Map.of(), Map.of());
        }
    }

    private BigDecimal resolveReturnUnitCost(FinishedGood finishedGood,
                                             BigDecimal quantity,
                                             Map<Long, java.util.List<InventoryMovement>> dispatchMovements,
                                             InvoiceLine invoiceLine) {
        java.util.List<InventoryMovement> movements = dispatchMovements.getOrDefault(finishedGood.getId(), java.util.List.of());
        if (movements.isEmpty()) {
            throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput("Return requires dispatch cost layers for " + finishedGood.getProductCode());
        }
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
        if (remaining.compareTo(BigDecimal.ZERO) > 0) {
            throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput("Return quantity exceeds dispatched quantity for " + finishedGood.getProductCode());
        }
        return MoneyUtils.safeDivide(costTotal, quantity, 4, RoundingMode.HALF_UP);
    }

    private FinishedGood lockFinishedGood(Company company, String productCode) {
        return finishedGoodRepository.lockByCompanyAndProductCode(company, productCode)
                .orElseThrow(() -> com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidState("Finished good not found for " + productCode));
    }

    private FinishedGood lockFinishedGood(Company company, Long id) {
        return finishedGoodRepository.lockByCompanyAndId(company, id)
                .orElseThrow(() -> com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidState("Finished good not found"));
    }
}
