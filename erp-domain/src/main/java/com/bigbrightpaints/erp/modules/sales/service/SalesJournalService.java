package com.bigbrightpaints.erp.modules.sales.service;

import com.bigbrightpaints.erp.core.util.MoneyUtils;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryDto;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingFacade;
import com.bigbrightpaints.erp.modules.inventory.service.FinishedGoodsService;
import com.bigbrightpaints.erp.modules.inventory.service.FinishedGoodsService.FinishedGoodAccountingProfile;
import com.bigbrightpaints.erp.modules.sales.domain.Dealer;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrder;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrderItem;
import com.bigbrightpaints.erp.modules.sales.util.SalesOrderReference;
import com.bigbrightpaints.erp.modules.production.domain.ProductionProduct;
import com.bigbrightpaints.erp.modules.production.domain.ProductionProductRepository;
import com.bigbrightpaints.erp.modules.accounting.service.CompanyDefaultAccountsService;
import com.bigbrightpaints.erp.modules.accounting.service.CompanyAccountingSettingsService;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Service for posting sales journal entries.
 * Delegates to AccountingFacade for actual journal creation.
 */
@Service
public class SalesJournalService {

    private static final Logger log = LoggerFactory.getLogger(SalesJournalService.class);
    private static final BigDecimal ROUNDING_TOLERANCE = new BigDecimal("0.05");

    private final FinishedGoodsService finishedGoodsService;
    private final AccountingFacade accountingFacade;
    private final ProductionProductRepository productionProductRepository;
    private final CompanyDefaultAccountsService companyDefaultAccountsService;
    private final CompanyAccountingSettingsService companyAccountingSettingsService;

    public SalesJournalService(FinishedGoodsService finishedGoodsService,
                               AccountingFacade accountingFacade,
                               ProductionProductRepository productionProductRepository,
                               CompanyDefaultAccountsService companyDefaultAccountsService,
                               CompanyAccountingSettingsService companyAccountingSettingsService) {
        this.finishedGoodsService = finishedGoodsService;
        this.accountingFacade = accountingFacade;
        this.productionProductRepository = productionProductRepository;
        this.companyDefaultAccountsService = companyDefaultAccountsService;
        this.companyAccountingSettingsService = companyAccountingSettingsService;
    }

    /**
     * Post sales journal entry for a sales order.
     * Delegates to AccountingFacade which handles idempotency, validation, and posting.
     *
     * @param order Sales order to post journal for
     * @param amountOverride Optional amount override (null = use order total)
     * @param referenceNumber Optional custom reference number
     * @param entryDate Optional entry date (null = current date)
     * @param memo Optional memo text
     * @return Journal entry ID or null if skipped
     */
    public Long postSalesJournal(SalesOrder order,
                                 BigDecimal amountOverride,
                                 String referenceNumber,
                                 LocalDate entryDate,
                                 String memo) {
        Objects.requireNonNull(order, "Sales order is required for journal posting");

        Dealer dealer = order.getDealer();
        if (dealer == null) {
            throw new IllegalStateException("Dealer is required to post a sales journal");
        }

        BigDecimal journalAmount = amountOverride != null ? amountOverride : order.getTotalAmount();
        if (journalAmount == null || journalAmount.compareTo(BigDecimal.ZERO) == 0) {
            log.info("Skipping sales journal for order {} because amount is zero", order.getOrderNumber());
            return null;
        }

        List<SalesOrderItem> items = order.getItems();
        if (items == null || items.isEmpty()) {
            throw new IllegalStateException("Sales order " + order.getOrderNumber() + " has no items");
        }

        Company company = order.getCompany();
        if (company == null) {
            throw new IllegalStateException("Sales order " + order.getOrderNumber() + " missing company context");
        }

        // Get accounting profiles for all products
        List<String> productCodes = items.stream()
                .map(SalesOrderItem::getProductCode)
                .filter(Objects::nonNull)
                .distinct()
                .toList();

        Map<String, FinishedGoodAccountingProfile> profiles = finishedGoodsService.accountingProfiles(productCodes);

        // Build revenue and tax line maps
        Map<Long, BigDecimal> revenueLines = new LinkedHashMap<>();
        Map<Long, BigDecimal> taxLines = new LinkedHashMap<>();
        Map<Long, BigDecimal> discountLines = new LinkedHashMap<>();
        Map<String, ProductAccounts> accountCache = new LinkedHashMap<>();
        Long gstOutputAccountId = null;
        boolean gstInclusive = order.isGstInclusive();

        for (SalesOrderItem item : items) {
            String productCode = item.getProductCode();
            if (productCode == null) {
                throw new IllegalStateException("Sales order item missing product code for order " + order.getOrderNumber());
            }

            FinishedGoodAccountingProfile profile = profiles.get(productCode);
            ProductAccounts accounts = accountCache.computeIfAbsent(productCode,
                    code -> resolveAccounts(company, code, profile));

            BigDecimal lineSubtotal = item.getLineSubtotal() != null
                    ? item.getLineSubtotal()
                    : MoneyUtils.safeMultiply(item.getQuantity(), item.getUnitPrice());
            BigDecimal lineTax = item.getGstAmount() != null ? item.getGstAmount() : BigDecimal.ZERO;
            BigDecimal lineGross = MoneyUtils.safeMultiply(item.getQuantity(), item.getUnitPrice());
            BigDecimal discountBase = gstInclusive ? lineSubtotal.add(lineTax) : lineSubtotal;
            BigDecimal lineDiscount = lineGross.subtract(discountBase);
            if (lineDiscount.compareTo(BigDecimal.ZERO) < 0) {
                lineDiscount = BigDecimal.ZERO;
            }
            BigDecimal discountNet = normalizeDiscountNet(lineDiscount, item.getGstRate(), gstInclusive);
            BigDecimal grossNet = currency(lineSubtotal.add(discountNet));

            if (grossNet.compareTo(BigDecimal.ZERO) > 0) {
                revenueLines.merge(accounts.revenueAccountId(), grossNet, BigDecimal::add);
            }

            if (discountNet.compareTo(BigDecimal.ZERO) > 0) {
                Long discountAccountId = accounts.discountAccountId();
                if (discountAccountId == null) {
                    throw new IllegalStateException("Discount account is required when a discount is applied for product " + productCode);
                }
                discountLines.merge(discountAccountId, discountNet, BigDecimal::add);
            }

            if (lineTax.compareTo(BigDecimal.ZERO) > 0) {
                if (gstOutputAccountId == null) {
                    gstOutputAccountId = companyAccountingSettingsService.requireTaxAccounts().outputTaxAccountId();
                }
                if (accounts.taxAccountId() != null && !accounts.taxAccountId().equals(gstOutputAccountId)) {
                    throw new IllegalStateException("Product " + productCode + " tax account must match GST output account");
                }
                taxLines.merge(gstOutputAccountId, lineTax, BigDecimal::add);
            }
        }

        if (revenueLines.isEmpty()) {
            throw new IllegalStateException("No revenue lines derived for order " + order.getOrderNumber());
        }

        // Balance adjustment if needed (rounding differences)
        BigDecimal totalCredits = revenueLines.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                .add(taxLines.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add));
        BigDecimal totalDiscount = discountLines.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal expectedCredits = journalAmount.abs().add(totalDiscount);
        BigDecimal delta = expectedCredits.subtract(totalCredits);

        if (delta.compareTo(BigDecimal.ZERO) != 0) {
            if (delta.abs().compareTo(ROUNDING_TOLERANCE) > 0) {
                throw new IllegalStateException(String.format(
                        "Sales journal delta %.2f exceeds tolerance for order %s",
                        delta, order.getOrderNumber()));
            }
            Long firstAccount = revenueLines.keySet().iterator().next();
            revenueLines.merge(firstAccount, delta, BigDecimal::add);
        }

        String resolvedMemo = memo != null ? memo : "Sales order " + order.getOrderNumber();
        String resolvedReference = StringUtils.hasText(referenceNumber)
                ? referenceNumber.trim()
                : SalesOrderReference.invoiceReference(order);

        // Delegate to AccountingFacade
        JournalEntryDto result = accountingFacade.postSalesJournal(
                dealer.getId(),
                order.getOrderNumber(),
                entryDate,
                resolvedMemo,
                revenueLines,
                taxLines,
                discountLines.isEmpty() ? null : discountLines,
                journalAmount,
                resolvedReference
        );

        return result != null ? result.id() : null;
    }

    private ProductAccounts resolveAccounts(Company company,
                                            String productCode,
                                            FinishedGoodAccountingProfile profile) {
        Long revenueAccountId = profile != null ? profile.revenueAccountId() : null;
        Long taxAccountId = profile != null ? profile.taxAccountId() : null;
        Long discountAccountId = profile != null ? profile.discountAccountId() : null;
        ProductionProduct product = null;
        if (revenueAccountId == null || taxAccountId == null || discountAccountId == null) {
            product = productionProductRepository.findByCompanyAndSkuCode(company, productCode)
                    .orElseThrow(() -> new IllegalStateException("Product " + productCode + " missing finished good and metadata account mapping"));
        }
        if (revenueAccountId == null && product != null) {
            revenueAccountId = metadataLong(product, "fgRevenueAccountId");
        }
        if (taxAccountId == null && product != null) {
            taxAccountId = metadataLong(product, "fgTaxAccountId");
        }
        if (discountAccountId == null && product != null) {
            discountAccountId = metadataLong(product, "fgDiscountAccountId");
        }
        if (revenueAccountId == null || taxAccountId == null || discountAccountId == null) {
            var defaults = companyDefaultAccountsService.getDefaults();
            if (revenueAccountId == null) {
                revenueAccountId = defaults.revenueAccountId();
            }
            if (taxAccountId == null) {
                taxAccountId = defaults.taxAccountId();
            }
            if (discountAccountId == null) {
                discountAccountId = defaults.discountAccountId();
            }
        }
        if (revenueAccountId == null || taxAccountId == null) {
            throw new IllegalStateException("Company default revenue/tax accounts are not configured for product " + productCode);
        }
        return new ProductAccounts(revenueAccountId, taxAccountId, discountAccountId);
    }

    private Long metadataLong(ProductionProduct product, String key) {
        if (product.getMetadata() == null) {
            return null;
        }
        Object value = product.getMetadata().get(key);
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Long.parseLong(text.trim());
            } catch (NumberFormatException ex) {
                log.warn("Unable to parse metadata {}={} for product {}", key, text, product.getSkuCode());
            }
        }
        return null;
    }

    private BigDecimal normalizeDiscountNet(BigDecimal discount, BigDecimal taxRate, boolean gstInclusive) {
        BigDecimal normalized = discount != null ? discount : BigDecimal.ZERO;
        if (!gstInclusive || normalized.compareTo(BigDecimal.ZERO) <= 0) {
            return currency(normalized);
        }
        BigDecimal rate = taxRate != null ? taxRate : BigDecimal.ZERO;
        if (rate.compareTo(BigDecimal.ZERO) <= 0) {
            return currency(normalized);
        }
        BigDecimal divisor = BigDecimal.ONE.add(rate.divide(new BigDecimal("100"), 6, RoundingMode.HALF_UP));
        if (divisor.compareTo(BigDecimal.ZERO) == 0) {
            return currency(normalized);
        }
        return currency(normalized.divide(divisor, 6, RoundingMode.HALF_UP));
    }

    private BigDecimal currency(BigDecimal value) {
        if (value == null) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private record ProductAccounts(Long revenueAccountId, Long taxAccountId, Long discountAccountId) {}

}
