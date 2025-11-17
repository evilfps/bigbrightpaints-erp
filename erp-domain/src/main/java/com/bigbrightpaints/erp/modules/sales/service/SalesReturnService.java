package com.bigbrightpaints.erp.modules.sales.service;

import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryDto;
import com.bigbrightpaints.erp.modules.accounting.dto.SalesReturnRequest;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingFacade;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGood;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryMovement;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryMovementRepository;
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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class SalesReturnService {

    private final CompanyContextService companyContextService;
    private final InvoiceRepository invoiceRepository;
    private final FinishedGoodRepository finishedGoodRepository;
    private final InventoryMovementRepository inventoryMovementRepository;
    private final AccountingFacade accountingFacade;

    public SalesReturnService(CompanyContextService companyContextService,
                              InvoiceRepository invoiceRepository,
                              FinishedGoodRepository finishedGoodRepository,
                              InventoryMovementRepository inventoryMovementRepository,
                              AccountingFacade accountingFacade) {
        this.companyContextService = companyContextService;
        this.invoiceRepository = invoiceRepository;
        this.finishedGoodRepository = finishedGoodRepository;
        this.inventoryMovementRepository = inventoryMovementRepository;
        this.accountingFacade = accountingFacade;
    }

    @Transactional
    public JournalEntryDto processReturn(SalesReturnRequest request) {
        Company company = companyContextService.requireCurrentCompany();
        Invoice invoice = invoiceRepository.findByCompanyAndId(company, request.invoiceId())
                .orElseThrow(() -> new IllegalArgumentException("Invoice not found"));
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

            BigDecimal baseAmount = invoiceLine.getUnitPrice().multiply(quantity);
            BigDecimal taxPerUnit = perUnitTax(invoiceLine);
            BigDecimal taxAmount = taxPerUnit.multiply(quantity);
            BigDecimal totalLineAmount = baseAmount.add(taxAmount);
            receivableCredit = receivableCredit.add(totalLineAmount);

            restockFinishedGood(finishedGood, quantity, invoice.getInvoiceNumber(), totalLineAmount.divide(quantity, 4, RoundingMode.HALF_UP));
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
                BigDecimal taxAmount = taxPerUnit.multiply(quantity);
                if (taxAmount.compareTo(BigDecimal.ZERO) > 0) {
                    returnLines.merge(taxAccountId, taxAmount, BigDecimal::add);
                }
            }
        }

        // Delegate to AccountingFacade
        return accountingFacade.postSalesReturn(
                dealer.getId(),
                invoice.getInvoiceNumber(),
                returnLines,
                receivableCredit,
                request.reason()
        );
    }

    private void restockFinishedGood(FinishedGood finishedGood, BigDecimal quantity, String invoiceNumber, BigDecimal unitCost) {
        finishedGood.setCurrentStock(finishedGood.getCurrentStock().add(quantity));
        finishedGoodRepository.save(finishedGood);
        InventoryMovement movement = new InventoryMovement();
        movement.setFinishedGood(finishedGood);
        movement.setReferenceType("SALES_RETURN");
        movement.setReferenceId(invoiceNumber);
        movement.setMovementType("RETURN");
        movement.setQuantity(quantity);
        movement.setUnitCost(unitCost);
        inventoryMovementRepository.save(movement);
    }

    private BigDecimal perUnitTax(InvoiceLine line) {
        BigDecimal total = Optional.ofNullable(line.getLineTotal()).orElse(BigDecimal.ZERO);
        BigDecimal base = line.getUnitPrice().multiply(Optional.ofNullable(line.getQuantity()).orElse(BigDecimal.ONE));
        BigDecimal taxTotal = total.subtract(base);
        if (line.getQuantity() == null || line.getQuantity().compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        return taxTotal.divide(line.getQuantity(), 4, RoundingMode.HALF_UP);
    }

    private BigDecimal requirePositive(BigDecimal value, String field) {
        if (value == null || value.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException(field + " must be positive");
        }
        return value;
    }

    private String resolveMemo(String reason, String invoiceNumber) {
        String suffix = StringUtils.hasText(reason) ? reason.trim() : "Return";
        return suffix + " - " + invoiceNumber;
    }

    private FinishedGood lockFinishedGood(Company company, String productCode) {
        return finishedGoodRepository.lockByCompanyAndProductCode(company, productCode)
                .orElseThrow(() -> new IllegalStateException("Finished good not found for " + productCode));
    }
}
