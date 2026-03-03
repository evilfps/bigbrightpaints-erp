package com.bigbrightpaints.erp.modules.factory.service;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.core.util.MoneyUtils;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalCreationRequest;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingFacade;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.factory.domain.ProductionLog;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGood;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;

@Service
public class PackingCompletionService {

    private static final RoundingMode COST_ROUNDING = RoundingMode.HALF_UP;

    private final PackingProductSupport packingProductSupport;
    private final AccountingFacade accountingFacade;

    public PackingCompletionService(PackingProductSupport packingProductSupport,
                                    AccountingFacade accountingFacade) {
        this.packingProductSupport = packingProductSupport;
        this.accountingFacade = accountingFacade;
    }

    public void postCompletionEntries(Company company,
                                      ProductionLog log,
                                      FinishedGood finishedGood,
                                      BigDecimal packedQty,
                                      BigDecimal wastageQty) {
        if (wastageQty.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }

        BigDecimal materialUnitCost = calculateUnitCost(log.getMaterialCostTotal(), log.getMixedQuantity());
        BigDecimal baseUnitCost = Optional.ofNullable(log.getUnitCost()).orElse(BigDecimal.ZERO);
        if (baseUnitCost.compareTo(BigDecimal.ZERO) <= 0) {
            baseUnitCost = materialUnitCost;
        }

        Long wipAccountId = packingProductSupport.requireWipAccountId(log.getProduct());
        LocalDate entryDate = resolveJournalDate(company, log);
        BigDecimal wastageValue = MoneyUtils.safeMultiply(baseUnitCost, wastageQty).setScale(2, COST_ROUNDING);
        Long wastageAccountId = packingProductSupport.metadataLong(log.getProduct(), "wastageAccountId");
        if (wastageAccountId == null) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_REFERENCE,
                    "Product " + log.getProduct().getProductName() + " missing wastageAccountId metadata");
        }

        accountingFacade.createStandardJournal(new JournalCreationRequest(
                wastageValue,
                wastageAccountId,
                wipAccountId,
                "Manufacturing wastage for " + log.getProductionCode(),
                "FACTORY_PACKING",
                log.getProductionCode() + "-WASTE",
                null,
                null,
                entryDate,
                null,
                null,
                Boolean.FALSE));
    }

    private BigDecimal calculateUnitCost(BigDecimal total, BigDecimal quantity) {
        if (total == null || quantity == null || quantity.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        return total.divide(quantity, 6, COST_ROUNDING);
    }

    private LocalDate resolveJournalDate(Company company, ProductionLog log) {
        ZoneId zoneId = Optional.ofNullable(company.getTimezone())
                .filter(org.springframework.util.StringUtils::hasText)
                .map(ZoneId::of)
                .orElse(ZoneOffset.UTC);
        return log.getProducedAt().atZone(zoneId).toLocalDate();
    }
}
