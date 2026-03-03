package com.bigbrightpaints.erp.modules.factory.service;

import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryRequest;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

@Component
public class PackingJournalBuilder {

    private static final int MONEY_SCALE = 2;

    public List<JournalEntryRequest.JournalLineRequest> buildWipPackagingConsumptionLines(Long wipAccountId,
                                                                                           String memo,
                                                                                           BigDecimal totalCost,
                                                                                           java.util.Map<Long, BigDecimal> accountTotals) {
        List<JournalEntryRequest.JournalLineRequest> lines = new ArrayList<>();
        lines.add(line(wipAccountId, memo, totalCost, BigDecimal.ZERO));

        accountTotals.forEach((accountId, amount) -> {
            BigDecimal roundedAmount = roundedMoney(amount);
            if (roundedAmount.compareTo(BigDecimal.ZERO) > 0) {
                lines.add(line(accountId, memo, BigDecimal.ZERO, roundedAmount));
            }
        });

        return rebalance(lines, true);
    }

    public List<JournalEntryRequest.JournalLineRequest> buildBulkToSizePackingLines(Long bulkAccountId,
                                                                                     BigDecimal bulkValue,
                                                                                     java.util.Map<Long, BigDecimal> packagingCredits,
                                                                                     java.util.List<JournalEntryRequest.JournalLineRequest> childDebitLines) {
        List<JournalEntryRequest.JournalLineRequest> lines = new ArrayList<>();
        lines.add(line(bulkAccountId, "Bulk consumption for packaging", BigDecimal.ZERO, bulkValue));

        packagingCredits.forEach((accountId, amount) -> {
            BigDecimal roundedAmount = roundedMoney(amount);
            if (roundedAmount.compareTo(BigDecimal.ZERO) > 0) {
                lines.add(line(accountId, "Packaging material consumption", BigDecimal.ZERO, roundedAmount));
            }
        });

        for (JournalEntryRequest.JournalLineRequest childDebitLine : childDebitLines) {
            lines.add(line(
                    childDebitLine.accountId(),
                    childDebitLine.description(),
                    childDebitLine.debit(),
                    childDebitLine.credit()));
        }

        return rebalance(lines, false);
    }

    private List<JournalEntryRequest.JournalLineRequest> rebalance(List<JournalEntryRequest.JournalLineRequest> lines,
                                                                   boolean adjustFirstDebit) {
        BigDecimal totalDebit = total(lines, true);
        BigDecimal totalCredit = total(lines, false);
        if (totalDebit.compareTo(totalCredit) == 0 || lines.isEmpty()) {
            return lines;
        }

        BigDecimal variance = totalCredit.subtract(totalDebit);
        if (adjustFirstDebit) {
            JournalEntryRequest.JournalLineRequest debitLine = lines.get(0);
            lines.set(0, line(
                    debitLine.accountId(),
                    debitLine.description(),
                    debitLine.debit().add(variance),
                    debitLine.credit()));
            return lines;
        }

        for (int index = lines.size() - 1; index >= 0; index--) {
            JournalEntryRequest.JournalLineRequest candidate = lines.get(index);
            if (candidate.debit().compareTo(BigDecimal.ZERO) > 0) {
                lines.set(index, line(
                        candidate.accountId(),
                        candidate.description(),
                        candidate.debit().add(variance),
                        candidate.credit()));
                return lines;
            }
        }

        JournalEntryRequest.JournalLineRequest first = lines.get(0);
        lines.set(0, line(
                first.accountId(),
                first.description(),
                first.debit().add(variance),
                first.credit()));
        return lines;
    }

    private BigDecimal total(List<JournalEntryRequest.JournalLineRequest> lines, boolean debit) {
        return lines.stream()
                .map(debit
                        ? JournalEntryRequest.JournalLineRequest::debit
                        : JournalEntryRequest.JournalLineRequest::credit)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal roundedMoney(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    private JournalEntryRequest.JournalLineRequest line(Long accountId,
                                                        String description,
                                                        BigDecimal debit,
                                                        BigDecimal credit) {
        return new JournalEntryRequest.JournalLineRequest(
                accountId,
                description,
                roundedMoney(debit),
                roundedMoney(credit));
    }
}
