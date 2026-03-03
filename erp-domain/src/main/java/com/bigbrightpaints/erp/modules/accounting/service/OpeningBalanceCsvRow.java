package com.bigbrightpaints.erp.modules.accounting.service;

import com.bigbrightpaints.erp.core.validation.ValidationUtils;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountType;
import org.apache.commons.csv.CSVRecord;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Map;

record OpeningBalanceCsvRow(String accountCode,
                            String accountName,
                            AccountType accountType,
                            BigDecimal debitAmount,
                            BigDecimal creditAmount,
                            String narration) {

    private static final String DEFAULT_NARRATION = "Opening balance import";

    static OpeningBalanceCsvRow from(CSVRecord record) {
        String accountCode = read(record, "account_code");
        String accountName = read(record, "account_name");
        String accountTypeRaw = read(record, "account_type");
        String debitRaw = read(record, "debit_amount");
        String creditRaw = read(record, "credit_amount");
        String narrationRaw = read(record, "narration");

        boolean blankRow = !StringUtils.hasText(accountCode)
                && !StringUtils.hasText(accountName)
                && !StringUtils.hasText(accountTypeRaw)
                && !StringUtils.hasText(debitRaw)
                && !StringUtils.hasText(creditRaw)
                && !StringUtils.hasText(narrationRaw);
        if (blankRow) {
            return null;
        }

        String normalizedCode = ValidationUtils.requireNotBlank(accountCode, "account_code");
        AccountType accountType = parseAccountType(accountTypeRaw);
        BigDecimal debitAmount = parseAmount(debitRaw, "debit_amount");
        BigDecimal creditAmount = parseAmount(creditRaw, "credit_amount");

        if (debitAmount.compareTo(BigDecimal.ZERO) == 0 && creditAmount.compareTo(BigDecimal.ZERO) == 0) {
            throw ValidationUtils.invalidInput("Either debit_amount or credit_amount must be greater than zero for account_code "
                    + normalizedCode);
        }
        if (debitAmount.compareTo(BigDecimal.ZERO) > 0 && creditAmount.compareTo(BigDecimal.ZERO) > 0) {
            throw ValidationUtils.invalidInput("Both debit_amount and credit_amount cannot be non-zero for account_code "
                    + normalizedCode);
        }

        String narration = StringUtils.hasText(narrationRaw)
                ? narrationRaw.trim()
                : DEFAULT_NARRATION + " " + normalizedCode;

        return new OpeningBalanceCsvRow(
                normalizedCode,
                StringUtils.hasText(accountName) ? accountName.trim() : null,
                accountType,
                debitAmount,
                creditAmount,
                narration
        );
    }

    private static String read(CSVRecord record, String headerName) {
        Map<String, String> map = record.toMap();
        for (Map.Entry<String, String> entry : map.entrySet()) {
            if (normalizeHeader(entry.getKey()).equals(headerName)) {
                String value = entry.getValue();
                return StringUtils.hasText(value) ? value.trim() : null;
            }
        }
        return null;
    }

    private static AccountType parseAccountType(String rawValue) {
        String normalized = ValidationUtils.requireNotBlank(rawValue, "account_type").toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "ASSET" -> AccountType.ASSET;
            case "LIABILITY" -> AccountType.LIABILITY;
            case "EQUITY" -> AccountType.EQUITY;
            case "REVENUE" -> AccountType.REVENUE;
            case "EXPENSE" -> AccountType.EXPENSE;
            default -> throw ValidationUtils.invalidInput("Invalid account_type: " + rawValue);
        };
    }

    private static BigDecimal parseAmount(String rawValue, String fieldName) {
        if (!StringUtils.hasText(rawValue)) {
            return BigDecimal.ZERO;
        }
        try {
            BigDecimal amount = new BigDecimal(rawValue.trim());
            if (amount.compareTo(BigDecimal.ZERO) < 0) {
                throw ValidationUtils.invalidInput(fieldName + " cannot be negative");
            }
            return amount;
        } catch (NumberFormatException ex) {
            throw ValidationUtils.invalidInput("Invalid numeric value for " + fieldName + ": " + rawValue);
        }
    }

    static String normalizeHeader(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        return value.replace("\uFEFF", "").trim().toLowerCase(Locale.ROOT);
    }
}
