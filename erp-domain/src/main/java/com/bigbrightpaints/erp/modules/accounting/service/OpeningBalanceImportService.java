package com.bigbrightpaints.erp.modules.accounting.service;

import com.bigbrightpaints.erp.core.audit.AuditEvent;
import com.bigbrightpaints.erp.core.audit.AuditService;
import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.core.idempotency.IdempotencyReservationService;
import com.bigbrightpaints.erp.core.idempotency.IdempotencyUtils;
import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.core.validation.ValidationUtils;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountType;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.OpeningBalanceImport;
import com.bigbrightpaints.erp.modules.accounting.domain.OpeningBalanceImportRepository;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalCreationRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryDto;
import com.bigbrightpaints.erp.modules.accounting.dto.OpeningBalanceImportResponse;
import com.bigbrightpaints.erp.modules.accounting.dto.OpeningBalanceImportResponse.ImportError;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

@Service
public class OpeningBalanceImportService {

    private static final List<String> REQUIRED_HEADERS = List.of(
            "account_code",
            "account_name",
            "account_type",
            "debit_amount",
            "credit_amount",
            "narration"
    );

    private static final String DEFAULT_NARRATION = "Opening balance import";

    private final CompanyContextService companyContextService;
    private final AccountRepository accountRepository;
    private final AccountingFacade accountingFacade;
    private final JournalEntryRepository journalEntryRepository;
    private final OpeningBalanceImportRepository openingBalanceImportRepository;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    private final CompanyClock companyClock;
    private final TransactionTemplate transactionTemplate;
    private final IdempotencyReservationService idempotencyReservationService = new IdempotencyReservationService();

    public OpeningBalanceImportService(CompanyContextService companyContextService,
                                       AccountRepository accountRepository,
                                       AccountingFacade accountingFacade,
                                       JournalEntryRepository journalEntryRepository,
                                       OpeningBalanceImportRepository openingBalanceImportRepository,
                                       AuditService auditService,
                                       ObjectMapper objectMapper,
                                       CompanyClock companyClock,
                                       PlatformTransactionManager transactionManager) {
        this.companyContextService = companyContextService;
        this.accountRepository = accountRepository;
        this.accountingFacade = accountingFacade;
        this.journalEntryRepository = journalEntryRepository;
        this.openingBalanceImportRepository = openingBalanceImportRepository;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
        this.companyClock = companyClock;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public OpeningBalanceImportResponse importOpeningBalances(MultipartFile file) {
        Company company = companyContextService.requireCurrentCompany();
        if (file == null || file.isEmpty()) {
            throw ValidationUtils.invalidInput("CSV file is required");
        }

        String fileHash = resolveFileHash(file);
        String idempotencyKey = normalizeIdempotencyKey(fileHash);
        String referenceNumber = resolveImportReference(company, fileHash);

        OpeningBalanceImport existing = openingBalanceImportRepository
                .findByCompanyAndIdempotencyKey(company, idempotencyKey)
                .orElse(null);
        if (existing != null) {
            assertIdempotencyMatch(existing, fileHash, idempotencyKey);
            return toResponse(existing);
        }

        if (journalEntryRepository.findByCompanyAndReferenceNumber(company, referenceNumber).isPresent()) {
            throw new ApplicationException(ErrorCode.BUSINESS_DUPLICATE_ENTRY,
                    "Opening balance import already processed for this file")
                    .withDetail("referenceNumber", referenceNumber);
        }

        try {
            OpeningBalanceImportResponse response = transactionTemplate.execute(status ->
                    importOpeningBalancesInternal(company, file, idempotencyKey, fileHash, referenceNumber));
            if (response == null) {
                throw ValidationUtils.invalidState("Opening balance import failed to return a response");
            }
            return response;
        } catch (RuntimeException ex) {
            if (!isDataIntegrityViolation(ex)) {
                throw ex;
            }
            OpeningBalanceImport concurrent = openingBalanceImportRepository
                    .findByCompanyAndIdempotencyKey(company, idempotencyKey)
                    .orElseThrow(() -> ex);
            assertIdempotencyMatch(concurrent, fileHash, idempotencyKey);
            return toResponse(concurrent);
        }
    }

    private OpeningBalanceImportResponse importOpeningBalancesInternal(Company company,
                                                                       MultipartFile file,
                                                                       String idempotencyKey,
                                                                       String fileHash,
                                                                       String referenceNumber) {
        OpeningBalanceImport record = new OpeningBalanceImport();
        record.setCompany(company);
        record.setIdempotencyKey(idempotencyKey);
        record.setIdempotencyHash(fileHash);
        record.setReferenceNumber(referenceNumber);
        record.setFileHash(fileHash);
        record.setFileName(file.getOriginalFilename());
        record = openingBalanceImportRepository.saveAndFlush(record);

        ImportResult result = processImport(company, file, referenceNumber);
        OpeningBalanceImportResponse response = result.response();

        record.setRowsProcessed(response.rowsProcessed());
        record.setAccountsCreated(response.accountsCreated());
        record.setErrorsJson(serializeErrors(response.errors()));
        record.setJournalEntryId(result.journalEntryId());
        openingBalanceImportRepository.save(record);

        Map<String, String> auditMetadata = new HashMap<>();
        auditMetadata.put("operation", "opening-balance-import");
        auditMetadata.put("idempotencyKey", idempotencyKey);
        auditMetadata.put("fileHash", fileHash);
        auditMetadata.put("referenceNumber", referenceNumber);
        auditMetadata.put("rowsProcessed", Integer.toString(response.rowsProcessed()));
        auditMetadata.put("accountsCreated", Integer.toString(response.accountsCreated()));
        if (result.journalEntryId() != null) {
            auditMetadata.put("journalEntryId", result.journalEntryId().toString());
        }
        auditService.logSuccess(AuditEvent.DATA_CREATE, auditMetadata);

        return response;
    }

    private ImportResult processImport(Company company, MultipartFile file, String referenceNumber) {
        int rowsProcessed = 0;
        int accountsCreated = 0;
        List<ImportError> errors = new ArrayList<>();
        List<JournalCreationRequest.LineRequest> lines = new ArrayList<>();
        BigDecimal totalDebit = BigDecimal.ZERO;
        BigDecimal totalCredit = BigDecimal.ZERO;

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8));
             CSVParser parser = CSVFormat.DEFAULT.builder()
                     .setHeader()
                     .setSkipHeaderRecord(true)
                     .setIgnoreHeaderCase(true)
                     .setTrim(true)
                     .build()
                     .parse(reader)) {

            validateCsvHeaders(parser);
            for (org.apache.commons.csv.CSVRecord record : parser) {
                OpeningBalanceCsvRow row;
                try {
                    row = OpeningBalanceCsvRow.from(record);
                } catch (RuntimeException ex) {
                    errors.add(new ImportError(record.getRecordNumber(), ex.getMessage()));
                    continue;
                }
                if (row == null) {
                    continue;
                }

                try {
                    ResolvedAccount resolvedAccount = resolveAccount(company, row);
                    if (resolvedAccount.created()) {
                        accountsCreated++;
                    }

                    lines.add(new JournalCreationRequest.LineRequest(
                            resolvedAccount.account().getId(),
                            row.debitAmount(),
                            row.creditAmount(),
                            row.narration()
                    ));
                    totalDebit = totalDebit.add(row.debitAmount());
                    totalCredit = totalCredit.add(row.creditAmount());
                    rowsProcessed++;
                } catch (RuntimeException ex) {
                    errors.add(new ImportError(record.getRecordNumber(), ex.getMessage()));
                }
            }
        } catch (IOException ex) {
            throw ValidationUtils.invalidState("Failed to read CSV file", ex);
        }

        Long journalEntryId = null;
        if (!lines.isEmpty()) {
            if (totalDebit.subtract(totalCredit).compareTo(BigDecimal.ZERO) != 0) {
                errors.add(new ImportError(0,
                        "Import totals are unbalanced: totalDebit=" + totalDebit + ", totalCredit=" + totalCredit));
            } else {
                JournalEntryDto journalEntry = postOpeningBalanceJournal(lines, totalDebit, referenceNumber);
                journalEntryId = journalEntry != null ? journalEntry.id() : null;
            }
        }

        OpeningBalanceImportResponse response = new OpeningBalanceImportResponse(
                rowsProcessed,
                accountsCreated,
                errors
        );
        return new ImportResult(response, journalEntryId);
    }

    private void validateCsvHeaders(CSVParser parser) {
        Map<String, Integer> rawHeaderMap = parser.getHeaderMap();
        Set<String> headers = new LinkedHashSet<>();
        if (rawHeaderMap != null) {
            rawHeaderMap.keySet().stream()
                    .map(OpeningBalanceCsvRow::normalizeHeader)
                    .forEach(headers::add);
        }

        List<String> missingHeaders = REQUIRED_HEADERS.stream()
                .filter(requiredHeader -> !headers.contains(requiredHeader))
                .toList();
        if (!missingHeaders.isEmpty()) {
            throw ValidationUtils.invalidInput("CSV is missing required headers: " + String.join(", ", missingHeaders));
        }
    }

    private ResolvedAccount resolveAccount(Company company, OpeningBalanceCsvRow row) {
        Optional<Account> existingOptional = accountRepository.findByCompanyAndCodeIgnoreCase(company, row.accountCode());
        if (existingOptional.isPresent()) {
            Account existing = existingOptional.get();
            if (existing.getType() != row.accountType()) {
                throw ValidationUtils.invalidInput("Account mapping mismatch for code " + row.accountCode()
                        + ": expected " + row.accountType() + " but found " + existing.getType());
            }
            return new ResolvedAccount(existing, false);
        }

        if (!StringUtils.hasText(row.accountName())) {
            throw ValidationUtils.invalidInput("account_name is required for new account code " + row.accountCode());
        }

        Account account = new Account();
        account.setCompany(company);
        account.setCode(row.accountCode());
        account.setName(row.accountName());
        account.setType(row.accountType());

        try {
            Account saved = accountRepository.save(account);
            return new ResolvedAccount(saved, true);
        } catch (DataIntegrityViolationException ex) {
            Account concurrent = accountRepository.findByCompanyAndCodeIgnoreCase(company, row.accountCode())
                    .orElseThrow(() -> ex);
            if (concurrent.getType() != row.accountType()) {
                throw ValidationUtils.invalidInput("Account mapping mismatch for code " + row.accountCode()
                        + ": expected " + row.accountType() + " but found " + concurrent.getType());
            }
            return new ResolvedAccount(concurrent, false);
        }
    }

    private JournalEntryDto postOpeningBalanceJournal(List<JournalCreationRequest.LineRequest> lines,
                                                      BigDecimal totalAmount,
                                                      String referenceNumber) {
        if (lines == null || lines.isEmpty()) {
            return null;
        }

        Long debitAccountId = lines.stream()
                .filter(line -> line.debit() != null && line.debit().compareTo(BigDecimal.ZERO) > 0)
                .map(JournalCreationRequest.LineRequest::accountId)
                .findFirst()
                .orElseThrow(() -> ValidationUtils.invalidInput("Opening balance import requires at least one debit line"));

        Long creditAccountId = lines.stream()
                .filter(line -> line.credit() != null && line.credit().compareTo(BigDecimal.ZERO) > 0)
                .map(JournalCreationRequest.LineRequest::accountId)
                .findFirst()
                .orElseThrow(() -> ValidationUtils.invalidInput("Opening balance import requires at least one credit line"));

        JournalCreationRequest request = new JournalCreationRequest(
                totalAmount,
                debitAccountId,
                creditAccountId,
                DEFAULT_NARRATION,
                "OPENING_BALANCE",
                referenceNumber,
                null,
                lines,
                companyClock.today(companyContextService.requireCurrentCompany()),
                null,
                null,
                Boolean.FALSE
        );
        return accountingFacade.createStandardJournal(request);
    }

    private String normalizeIdempotencyKey(String fileHash) {
        String resolved = StringUtils.hasText(fileHash)
                ? IdempotencyUtils.normalizeKey(fileHash)
                : fileHash;
        return idempotencyReservationService.requireKey(resolved, "opening balance imports");
    }

    private void assertIdempotencyMatch(OpeningBalanceImport record, String expectedHash, String idempotencyKey) {
        idempotencyReservationService.assertAndRepairSignature(
                record,
                idempotencyKey,
                expectedHash,
                persisted -> StringUtils.hasText(persisted.getIdempotencyHash())
                        ? persisted.getIdempotencyHash()
                        : persisted.getFileHash(),
                OpeningBalanceImport::setIdempotencyHash,
                openingBalanceImportRepository::save,
                () -> idempotencyReservationService.payloadMismatch(idempotencyKey));
    }

    private OpeningBalanceImportResponse toResponse(OpeningBalanceImport record) {
        return new OpeningBalanceImportResponse(
                record.getRowsProcessed(),
                record.getAccountsCreated(),
                deserializeErrors(record.getErrorsJson())
        );
    }

    private String serializeErrors(List<ImportError> errors) {
        if (errors == null || errors.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(errors);
        } catch (Exception ex) {
            return null;
        }
    }

    private List<ImportError> deserializeErrors(String errorsJson) {
        if (!StringUtils.hasText(errorsJson)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(errorsJson, new TypeReference<List<ImportError>>() {});
        } catch (Exception ex) {
            return List.of();
        }
    }

    private String resolveImportReference(Company company, String fileHash) {
        String companyCode = sanitizeCompanyCode(company != null ? company.getCode() : null);
        String shortHash = StringUtils.hasText(fileHash)
                ? fileHash.substring(0, Math.min(12, fileHash.length()))
                : "UNKNOWN";
        return "OPEN-BAL-%s-%s".formatted(companyCode, shortHash);
    }

    private static String sanitizeCompanyCode(String code) {
        if (!StringUtils.hasText(code)) {
            return "COMPANY";
        }
        String normalized = code.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
        return normalized.isBlank() ? "COMPANY" : normalized;
    }

    private String resolveFileHash(MultipartFile file) {
        try {
            return IdempotencyUtils.sha256Hex(file.getBytes());
        } catch (Exception ex) {
            return Integer.toHexString(file.getOriginalFilename() != null ? file.getOriginalFilename().hashCode() : 0);
        }
    }

    private boolean isDataIntegrityViolation(Throwable error) {
        return idempotencyReservationService.isDataIntegrityViolation(error);
    }

    private record ResolvedAccount(Account account, boolean created) {}

    private record ImportResult(OpeningBalanceImportResponse response, Long journalEntryId) {}
}
