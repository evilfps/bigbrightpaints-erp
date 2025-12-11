package com.bigbrightpaints.erp.modules.sales.service;

import com.bigbrightpaints.erp.core.util.MoneyUtils;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryDto;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingFacade;
import com.bigbrightpaints.erp.modules.inventory.service.FinishedGoodsService;
import com.bigbrightpaints.erp.modules.inventory.service.FinishedGoodsService.FinishedGoodAccountingProfile;
import com.bigbrightpaints.erp.modules.sales.domain.Dealer;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrder;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrderItem;
import com.bigbrightpaints.erp.modules.production.domain.ProductionProduct;
import com.bigbrightpaints.erp.modules.production.domain.ProductionProductRepository;
import com.bigbrightpaints.erp.modules.accounting.service.CompanyDefaultAccountsService;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import java.math.BigDecimal;
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

    public SalesJournalService(FinishedGoodsService finishedGoodsService,
                               AccountingFacade accountingFacade,
                               ProductionProductRepository productionProductRepository,
                               CompanyDefaultAccountsService companyDefaultAccountsService) {
        this.finishedGoodsService = finishedGoodsService;
        this.accountingFacade = accountingFacade;
        this.productionProductRepository = productionProductRepository;
        this.companyDefaultAccountsService = companyDefaultAccountsService;
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
        Map<String, ProductAccounts> accountCache = new LinkedHashMap<>();

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

            if (lineSubtotal != null && lineSubtotal.compareTo(BigDecimal.ZERO) > 0) {
                revenueLines.merge(accounts.revenueAccountId(), lineSubtotal, BigDecimal::add);
            }

            BigDecimal lineTax = item.getGstAmount() != null ? item.getGstAmount() : BigDecimal.ZERO;
            if (lineTax.compareTo(BigDecimal.ZERO) > 0) {
                if (accounts.taxAccountId() == null) {
                    throw new IllegalStateException("Product " + productCode + " missing tax account for GST posting");
                }
                taxLines.merge(accounts.taxAccountId(), lineTax, BigDecimal::add);
            }
        }

        if (revenueLines.isEmpty()) {
            throw new IllegalStateException("No revenue lines derived for order " + order.getOrderNumber());
        }

        // Balance adjustment if needed (rounding differences)
        BigDecimal totalCredits = revenueLines.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                .add(taxLines.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add));
        BigDecimal delta = journalAmount.abs().subtract(totalCredits);

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
        String resolvedReference = StringUtils.hasText(referenceNumber) ? referenceNumber.trim() : null;

        // Delegate to AccountingFacade
        JournalEntryDto result = accountingFacade.postSalesJournal(
                dealer.getId(),
                order.getOrderNumber(),
                entryDate,
                resolvedMemo,
                revenueLines,
                taxLines,
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
        if (revenueAccountId != null && taxAccountId != null) {
            return new ProductAccounts(revenueAccountId, taxAccountId);
        }
        ProductionProduct product = productionProductRepository.findByCompanyAndSkuCode(company, productCode)
                .orElseThrow(() -> new IllegalStateException("Product " + productCode + " missing finished good and metadata account mapping"));
        if (revenueAccountId == null) {
            revenueAccountId = metadataLong(product, "fgRevenueAccountId");
        }
        if (taxAccountId == null) {
            taxAccountId = metadataLong(product, "fgTaxAccountId");
        }
        if (revenueAccountId == null || taxAccountId == null) {
            var defaults = companyDefaultAccountsService.requireDefaults();
            if (revenueAccountId == null) {
                revenueAccountId = defaults.revenueAccountId();
            }
            if (taxAccountId == null) {
                taxAccountId = defaults.taxAccountId();
            }
        }
        if (revenueAccountId == null || taxAccountId == null) {
            throw new IllegalStateException("Company default revenue/tax accounts are not configured for product " + productCode);
        }
        return new ProductAccounts(revenueAccountId, taxAccountId);
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

    private record ProductAccounts(Long revenueAccountId, Long taxAccountId) {}

}
