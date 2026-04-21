package com.bigbrightpaints.erp.modules.accounting.service;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.bigbrightpaints.erp.core.audit.AuditEvent;
import com.bigbrightpaints.erp.core.audit.AuditService;
import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.core.idempotency.IdempotencyReservationService;
import com.bigbrightpaints.erp.core.idempotency.IdempotencyUtils;
import com.bigbrightpaints.erp.core.validation.ValidationUtils;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountType;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.TallyImport;
import com.bigbrightpaints.erp.modules.accounting.domain.TallyImportRepository;
import com.bigbrightpaints.erp.modules.accounting.dto.OpeningBalanceImportResponse;
import com.bigbrightpaints.erp.modules.accounting.dto.TallyImportResponse;
import com.bigbrightpaints.erp.modules.accounting.dto.TallyImportResponse.ImportError;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;

@Service
public class TallyImportService {

  private static final Logger log = LoggerFactory.getLogger(TallyImportService.class);

  private static final Map<String, AccountType> TALLY_GROUP_ACCOUNT_TYPE_MAP =
      Map.ofEntries(
          Map.entry("SUNDRY DEBTORS", AccountType.ASSET),
          Map.entry("SUNDRY CREDITORS", AccountType.LIABILITY),
          Map.entry("BANK ACCOUNTS", AccountType.ASSET),
          Map.entry("BANK OD A/C", AccountType.LIABILITY),
          Map.entry("BANK OCC A/C", AccountType.LIABILITY),
          Map.entry("CASH-IN-HAND", AccountType.ASSET),
          Map.entry("CAPITAL ACCOUNT", AccountType.EQUITY),
          Map.entry("RESERVES & SURPLUS", AccountType.EQUITY),
          Map.entry("CURRENT ASSETS", AccountType.ASSET),
          Map.entry("CURRENT LIABILITIES", AccountType.LIABILITY),
          Map.entry("FIXED ASSETS", AccountType.ASSET),
          Map.entry("LOANS (LIABILITY)", AccountType.LIABILITY),
          Map.entry("LOANS & ADVANCES (ASSET)", AccountType.ASSET),
          Map.entry("DUTIES & TAXES", AccountType.LIABILITY),
          Map.entry("SALES ACCOUNTS", AccountType.REVENUE),
          Map.entry("PURCHASE ACCOUNTS", AccountType.EXPENSE),
          Map.entry("INDIRECT INCOMES", AccountType.REVENUE),
          Map.entry("INDIRECT EXPENSES", AccountType.EXPENSE),
          Map.entry("DIRECT INCOMES", AccountType.REVENUE),
          Map.entry("DIRECT EXPENSES", AccountType.EXPENSE),
          Map.entry("BRANCH / DIVISIONS", AccountType.ASSET),
          Map.entry("SUSPENSE A/C", AccountType.ASSET),
          Map.entry("PROVISIONS", AccountType.LIABILITY),
          Map.entry("SECURED LOANS", AccountType.LIABILITY),
          Map.entry("UNSECURED LOANS", AccountType.LIABILITY));

  private final CompanyContextService companyContextService;
  private final AccountRepository accountRepository;
  private final OpeningBalanceImportService openingBalanceImportService;
  private final JournalEntryRepository journalEntryRepository;
  private final TallyImportRepository tallyImportRepository;
  private final AuditService auditService;
  private final ObjectMapper objectMapper;
  private final TransactionTemplate transactionTemplate;
  private final IdempotencyReservationService idempotencyReservationService =
      new IdempotencyReservationService();

  public TallyImportService(
      CompanyContextService companyContextService,
      AccountRepository accountRepository,
      OpeningBalanceImportService openingBalanceImportService,
      JournalEntryRepository journalEntryRepository,
      TallyImportRepository tallyImportRepository,
      AuditService auditService,
      ObjectMapper objectMapper,
      PlatformTransactionManager transactionManager) {
    this.companyContextService = companyContextService;
    this.accountRepository = accountRepository;
    this.openingBalanceImportService = openingBalanceImportService;
    this.journalEntryRepository = journalEntryRepository;
    this.tallyImportRepository = tallyImportRepository;
    this.auditService = auditService;
    this.objectMapper = objectMapper;
    this.transactionTemplate = new TransactionTemplate(transactionManager);
  }

  public TallyImportResponse importTallyXml(MultipartFile file) {
    Company company = companyContextService.requireCurrentCompany();
    if (file == null || file.isEmpty()) {
      throw ValidationUtils.invalidInput("Tally XML file is required");
    }

    String fileHash = resolveFileHash(file);
    String idempotencyKey = normalizeIdempotencyKey(fileHash);
    String referenceNumber = resolveImportReference(company, fileHash);

    TallyImport existing =
        tallyImportRepository.findByCompanyAndIdempotencyKey(company, idempotencyKey).orElse(null);
    if (existing != null) {
      assertIdempotencyMatch(existing, fileHash, idempotencyKey);
      return toResponse(existing);
    }

    if (journalEntryRepository
        .findByCompanyAndReferenceNumber(company, referenceNumber)
        .isPresent()) {
      throw new ApplicationException(
              ErrorCode.BUSINESS_DUPLICATE_ENTRY, "Tally import already processed for this file")
          .withDetail("referenceNumber", referenceNumber);
    }

    byte[] payload;
    try {
      payload = file.getBytes();
    } catch (Exception ex) {
      throw ValidationUtils.invalidInput("Unable to read Tally XML file", ex);
    }

    try {
      TallyImportResponse response =
          transactionTemplate.execute(
              status ->
                  importTallyXmlInternal(
                      company, file, payload, idempotencyKey, fileHash, referenceNumber));
      if (response == null) {
        throw ValidationUtils.invalidState("Tally import failed to return a response");
      }
      return response;
    } catch (RuntimeException ex) {
      if (!isDataIntegrityViolation(ex)) {
        throw ex;
      }
      TallyImport concurrent =
          tallyImportRepository
              .findByCompanyAndIdempotencyKey(company, idempotencyKey)
              .orElseThrow(() -> ex);
      assertIdempotencyMatch(concurrent, fileHash, idempotencyKey);
      return toResponse(concurrent);
    }
  }

  private TallyImportResponse importTallyXmlInternal(
      Company company,
      MultipartFile file,
      byte[] payload,
      String idempotencyKey,
      String fileHash,
      String referenceNumber) {
    TallyImport record = new TallyImport();
    record.setCompany(company);
    record.setIdempotencyKey(idempotencyKey);
    record.setIdempotencyHash(fileHash);
    record.setReferenceNumber(referenceNumber);
    record.setFileHash(fileHash);
    record.setFileName(file.getOriginalFilename());
    record = tallyImportRepository.saveAndFlush(record);

    TallyImportResponse response = processImport(company, payload, referenceNumber);

    record.setLedgersProcessed(response.ledgersProcessed());
    record.setMappedLedgers(response.mappedLedgers());
    record.setAccountsCreated(response.accountsCreated());
    record.setOpeningVoucherEntriesProcessed(response.openingVoucherEntriesProcessed());
    record.setOpeningBalanceRowsProcessed(response.openingBalanceRowsProcessed());
    record.setUnmappedGroupsJson(serializeStringList(response.unmappedGroups()));
    record.setUnmappedItemsJson(serializeStringList(response.unmappedItems()));
    record.setErrorsJson(serializeErrors(response.errors()));
    Long journalEntryId = resolveJournalEntryIdByReference(company, referenceNumber);
    record.setJournalEntryId(journalEntryId);
    tallyImportRepository.save(record);

    Map<String, String> auditMetadata = new LinkedHashMap<>();
    auditMetadata.put("operation", "tally-xml-import");
    auditMetadata.put("idempotencyKey", idempotencyKey);
    auditMetadata.put("fileHash", fileHash);
    auditMetadata.put("referenceNumber", referenceNumber);
    auditMetadata.put("ledgersProcessed", Integer.toString(response.ledgersProcessed()));
    auditMetadata.put("mappedLedgers", Integer.toString(response.mappedLedgers()));
    auditMetadata.put("accountsCreated", Integer.toString(response.accountsCreated()));
    auditMetadata.put(
        "openingVoucherEntriesProcessed",
        Integer.toString(response.openingVoucherEntriesProcessed()));
    auditMetadata.put(
        "openingBalanceRowsProcessed", Integer.toString(response.openingBalanceRowsProcessed()));
    if (journalEntryId != null) {
      auditMetadata.put("journalEntryId", journalEntryId.toString());
    }
    auditService.logSuccess(AuditEvent.DATA_CREATE, auditMetadata);

    return response;
  }

  private TallyImportResponse processImport(
      Company company, byte[] payload, String referenceNumber) {
    ParsedTallyData parsed = parseTallyXml(payload);

    Set<String> unmappedGroups = new LinkedHashSet<>();
    Set<String> unmappedItems = new LinkedHashSet<>();
    List<ImportError> errors = new ArrayList<>();

    Map<String, Account> ledgerAccountByName = new LinkedHashMap<>();
    int mappedLedgers = 0;
    int accountsCreated = 0;

    for (TallyLedger ledger : parsed.ledgers()) {
      ResolvedLedger resolved = resolveLedger(company, ledger, unmappedGroups, errors);
      if (resolved == null) {
        continue;
      }
      if (resolved.created()) {
        accountsCreated++;
      }
      mappedLedgers++;
      ledgerAccountByName.put(normalizeLedgerNameKey(ledger.name()), resolved.account());
    }

    Map<String, Account> accountByCode = new LinkedHashMap<>();
    Map<String, BigDecimal> debitLines = new LinkedHashMap<>();
    Map<String, BigDecimal> creditLines = new LinkedHashMap<>();

    for (OpeningBalanceRow row : parsed.openingRows()) {
      Account account =
          resolveAccountForOpeningRow(company, row, ledgerAccountByName, unmappedItems, errors);
      if (account == null) {
        continue;
      }
      String accountCode = account.getCode();
      if (!StringUtils.hasText(accountCode)) {
        errors.add(
            new ImportError("opening-row:" + row.ledgerName(), "Resolved account has no code"));
        continue;
      }
      accountByCode.putIfAbsent(accountCode, account);
      if (row.amount().compareTo(BigDecimal.ZERO) > 0) {
        debitLines.merge(accountCode, row.amount(), BigDecimal::add);
      } else {
        creditLines.merge(accountCode, row.amount().abs(), BigDecimal::add);
      }
    }

    List<OpeningBalanceImportService.ParsedOpeningBalanceRow> openingBalanceRows =
        buildOpeningBalanceRows(accountByCode, debitLines, creditLines);

    OpeningBalanceImportResponse importResponse =
        openingBalanceImportService.importFromParsedRows(openingBalanceRows, referenceNumber);

    List<ImportError> combinedErrors = new ArrayList<>(errors);
    if (importResponse != null
        && importResponse.errors() != null
        && !importResponse.errors().isEmpty()) {
      importResponse
          .errors()
          .forEach(
              error ->
                  combinedErrors.add(
                      new ImportError(
                          "opening-balance-row-" + error.rowNumber(), error.message())));
    }

    int openingVoucherEntriesProcessed = parsed.openingRows().size();
    int openingBalanceRowsProcessed =
        importResponse != null ? importResponse.rowsProcessed() : openingBalanceRows.size();

    return new TallyImportResponse(
        parsed.ledgers().size(),
        mappedLedgers,
        accountsCreated,
        openingVoucherEntriesProcessed,
        openingBalanceRowsProcessed,
        unmappedGroups.stream().sorted().toList(),
        unmappedItems.stream().sorted().toList(),
        combinedErrors);
  }

  private List<OpeningBalanceImportService.ParsedOpeningBalanceRow> buildOpeningBalanceRows(
      Map<String, Account> accountByCode,
      Map<String, BigDecimal> debitLines,
      Map<String, BigDecimal> creditLines) {
    List<OpeningBalanceImportService.ParsedOpeningBalanceRow> rows = new ArrayList<>();
    long rowCounter = 1L;

    if (debitLines != null) {
      for (Map.Entry<String, BigDecimal> entry : debitLines.entrySet()) {
        BigDecimal amount = entry.getValue();
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
          continue;
        }
        Account account = accountByCode.get(entry.getKey());
        if (account == null) {
          continue;
        }
        rows.add(
            new OpeningBalanceImportService.ParsedOpeningBalanceRow(
                rowCounter++,
                account.getCode(),
                account.getName(),
                account.getType(),
                amount,
                BigDecimal.ZERO,
                OpeningBalanceImportService.DEFAULT_NARRATION + " " + account.getCode()));
      }
    }

    if (creditLines != null) {
      for (Map.Entry<String, BigDecimal> entry : creditLines.entrySet()) {
        BigDecimal amount = entry.getValue();
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
          continue;
        }
        Account account = accountByCode.get(entry.getKey());
        if (account == null) {
          continue;
        }
        rows.add(
            new OpeningBalanceImportService.ParsedOpeningBalanceRow(
                rowCounter++,
                account.getCode(),
                account.getName(),
                account.getType(),
                BigDecimal.ZERO,
                amount,
                OpeningBalanceImportService.DEFAULT_NARRATION + " " + account.getCode()));
      }
    }

    return rows;
  }

  private ResolvedLedger resolveLedger(
      Company company, TallyLedger ledger, Set<String> unmappedGroups, List<ImportError> errors) {
    String groupKey = normalizeGroupKey(ledger.group());
    AccountType mappedType = TALLY_GROUP_ACCOUNT_TYPE_MAP.get(groupKey);
    if (mappedType == null) {
      if (StringUtils.hasText(ledger.group())) {
        unmappedGroups.add(ledger.group().trim());
      } else {
        unmappedGroups.add("<blank-group>");
      }
      return null;
    }

    String accountCode = resolveAccountCode(ledger.name(), company);
    String accountName = StringUtils.hasText(ledger.name()) ? ledger.name().trim() : accountCode;
    return resolveLedgerByCodeAndType(company, accountCode, accountName, mappedType, errors);
  }

  private ResolvedLedger resolveLedgerByCodeAndType(
      Company company,
      String accountCode,
      String accountName,
      AccountType mappedType,
      List<ImportError> errors) {
    Optional<Account> existingOptional =
        accountRepository.findByCompanyAndCodeIgnoreCase(company, accountCode);
    if (existingOptional.isPresent()) {
      Account existing = existingOptional.get();
      if (existing.getType() != mappedType) {
        errors.add(
            new ImportError(
                "ledger:" + accountName,
                "Existing account type mismatch for code "
                    + accountCode
                    + ": expected "
                    + mappedType
                    + " but found "
                    + existing.getType()));
        return null;
      }
      return new ResolvedLedger(existing, false);
    }

    Account created = new Account();
    created.setCompany(company);
    created.setCode(accountCode);
    created.setName(accountName);
    created.setType(mappedType);
    try {
      return new ResolvedLedger(accountRepository.save(created), true);
    } catch (DataIntegrityViolationException ex) {
      Account concurrent =
          accountRepository
              .findByCompanyAndCodeIgnoreCase(company, accountCode)
              .orElseThrow(() -> ex);
      if (concurrent.getType() != mappedType) {
        errors.add(
            new ImportError(
                "ledger:" + accountName,
                "Existing account type mismatch for code "
                    + accountCode
                    + ": expected "
                    + mappedType
                    + " but found "
                    + concurrent.getType()));
        return null;
      }
      return new ResolvedLedger(concurrent, false);
    }
  }

  private Account resolveAccountForOpeningRow(
      Company company,
      OpeningBalanceRow row,
      Map<String, Account> ledgerAccountByName,
      Set<String> unmappedItems,
      List<ImportError> errors) {
    Account mapped = ledgerAccountByName.get(normalizeLedgerNameKey(row.ledgerName()));
    if (mapped != null) {
      return mapped;
    }

    String code = resolveAccountCode(row.ledgerName(), company);
    Optional<Account> byCode = accountRepository.findByCompanyAndCodeIgnoreCase(company, code);
    if (byCode.isPresent()) {
      return byCode.get();
    }

    if (StringUtils.hasText(row.parentGroup())) {
      AccountType mappedType =
          TALLY_GROUP_ACCOUNT_TYPE_MAP.get(normalizeGroupKey(row.parentGroup()));
      if (mappedType != null) {
        String accountName = StringUtils.hasText(row.ledgerName()) ? row.ledgerName().trim() : code;
        ResolvedLedger resolved =
            resolveLedgerByCodeAndType(company, code, accountName, mappedType, errors);
        if (resolved != null) {
          return resolved.account();
        }
      }
    }

    String descriptor =
        StringUtils.hasText(row.ledgerName()) ? row.ledgerName().trim() : "<unnamed-ledger>";
    unmappedItems.add(descriptor);
    errors.add(
        new ImportError(
            "opening-row:" + descriptor,
            "Unable to map ledger to account; parent group=" + row.parentGroup()));
    return null;
  }

  private ParsedTallyData parseTallyXml(byte[] payload) {
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    factory.setNamespaceAware(false);
    factory.setExpandEntityReferences(false);
    try {
      factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
      factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
    } catch (ParserConfigurationException ex) {
      log.warn(
          "XML parser does not support one or more XXE-hardening features; continuing with"
              + " best-effort configuration",
          ex);
    }
    try {
      Document document = factory.newDocumentBuilder().parse(new ByteArrayInputStream(payload));
      document.getDocumentElement().normalize();

      List<TallyLedger> ledgers = parseLedgers(document);
      List<OpeningBalanceRow> openingRows = parseOpeningRows(document);

      return new ParsedTallyData(ledgers, openingRows);
    } catch (ApplicationException ex) {
      throw ex;
    } catch (Exception ex) {
      throw ValidationUtils.invalidInput("Invalid Tally XML format", ex);
    }
  }

  private List<TallyLedger> parseLedgers(Document document) {
    NodeList ledgerNodes = document.getElementsByTagName("LEDGER");
    List<TallyLedger> ledgers = new ArrayList<>();
    for (int i = 0; i < ledgerNodes.getLength(); i++) {
      Node node = ledgerNodes.item(i);
      if (node.getNodeType() != Node.ELEMENT_NODE) {
        continue;
      }
      Element element = (Element) node;
      String name = firstNonBlank(attribute(element, "NAME"), childText(element, "NAME"));
      String parent = firstNonBlank(childText(element, "PARENT"), childText(element, "GROUP"));
      if (!StringUtils.hasText(name) && !StringUtils.hasText(parent)) {
        continue;
      }
      ledgers.add(new TallyLedger(name, parent));
    }
    return ledgers;
  }

  private List<OpeningBalanceRow> parseOpeningRows(Document document) {
    List<OpeningBalanceRow> rows = new ArrayList<>();

    NodeList ledgerNodes = document.getElementsByTagName("LEDGER");
    for (int i = 0; i < ledgerNodes.getLength(); i++) {
      Node node = ledgerNodes.item(i);
      if (node.getNodeType() != Node.ELEMENT_NODE) {
        continue;
      }
      Element element = (Element) node;
      String ledgerName = firstNonBlank(attribute(element, "NAME"), childText(element, "NAME"));
      String parent = firstNonBlank(childText(element, "PARENT"), childText(element, "GROUP"));
      String openingBalance = childText(element, "OPENINGBALANCE");
      BigDecimal amount = parseSignedAmount(openingBalance);
      if (amount == null || amount.compareTo(BigDecimal.ZERO) == 0) {
        continue;
      }
      rows.add(new OpeningBalanceRow(ledgerName, parent, amount));
    }

    NodeList voucherNodes = document.getElementsByTagName("VOUCHER");
    for (int i = 0; i < voucherNodes.getLength(); i++) {
      Node voucherNode = voucherNodes.item(i);
      if (voucherNode.getNodeType() != Node.ELEMENT_NODE) {
        continue;
      }
      Element voucherElement = (Element) voucherNode;
      String voucherType =
          firstNonBlank(attribute(voucherElement, "VCHTYPE"), childText(voucherElement, "VCHTYPE"));
      String source =
          firstNonBlank(
              attribute(voucherElement, "VOUCHERTYPENAME"),
              childText(voucherElement, "VOUCHERTYPENAME"));
      if (!isOpeningVoucher(voucherType, source)) {
        continue;
      }
      NodeList ledgerEntries = voucherElement.getElementsByTagName("ALLLEDGERENTRIES.LIST");
      for (int j = 0; j < ledgerEntries.getLength(); j++) {
        Node entryNode = ledgerEntries.item(j);
        if (entryNode.getNodeType() != Node.ELEMENT_NODE) {
          continue;
        }
        Element entryElement = (Element) entryNode;
        String ledgerName =
            firstNonBlank(
                childText(entryElement, "LEDGERNAME"),
                childText(entryElement, "PARTYLEDGERNAME"),
                childText(entryElement, "LEDGER"));
        String parent = childText(entryElement, "PARENT");
        BigDecimal amount = parseSignedAmount(childText(entryElement, "AMOUNT"));
        if (amount == null || amount.compareTo(BigDecimal.ZERO) == 0) {
          continue;
        }
        rows.add(new OpeningBalanceRow(ledgerName, parent, amount));
      }
    }

    return rows;
  }

  private boolean isOpeningVoucher(String voucherType, String voucherTypeName) {
    String normalizedType = IdempotencyUtils.normalizeUpperToken(voucherType);
    String normalizedName = IdempotencyUtils.normalizeUpperToken(voucherTypeName);
    if (normalizedType.contains("OPENING") || normalizedName.contains("OPENING")) {
      return true;
    }
    return normalizedType.equals("JOURNAL") && normalizedName.contains("OPENING");
  }

  private BigDecimal parseSignedAmount(String raw) {
    if (!StringUtils.hasText(raw)) {
      return null;
    }
    String cleaned = raw.trim().replace("₹", "").replace(",", "").trim();
    if (!StringUtils.hasText(cleaned)) {
      return null;
    }
    try {
      return new BigDecimal(cleaned);
    } catch (NumberFormatException ex) {
      throw ValidationUtils.invalidInput("Invalid amount in Tally XML: " + raw);
    }
  }

  private static String childText(Element parent, String tagName) {
    if (parent == null || !StringUtils.hasText(tagName)) {
      return null;
    }
    NodeList children = parent.getElementsByTagName(tagName);
    if (children == null || children.getLength() == 0) {
      return null;
    }
    for (int i = 0; i < children.getLength(); i++) {
      Node child = children.item(i);
      if (child == null) {
        continue;
      }
      String value = child.getTextContent();
      if (StringUtils.hasText(value)) {
        return value.trim();
      }
    }
    return null;
  }

  private static String attribute(Element element, String name) {
    if (element == null || !StringUtils.hasText(name)) {
      return null;
    }
    String value = element.getAttribute(name);
    return StringUtils.hasText(value) ? value.trim() : null;
  }

  private static String firstNonBlank(String... values) {
    if (values == null) {
      return null;
    }
    for (String value : values) {
      if (StringUtils.hasText(value)) {
        return value.trim();
      }
    }
    return null;
  }

  private static String normalizeGroupKey(String group) {
    if (!StringUtils.hasText(group)) {
      return "";
    }
    return group.trim().toUpperCase(Locale.ROOT);
  }

  private static String normalizeLedgerNameKey(String ledgerName) {
    if (!StringUtils.hasText(ledgerName)) {
      return "";
    }
    return ledgerName.trim().toUpperCase(Locale.ROOT);
  }

  private String resolveAccountCode(String ledgerName, Company company) {
    String normalized =
        StringUtils.hasText(ledgerName)
            ? ledgerName.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "-")
            : "TALLY-LEDGER";
    normalized = normalized.replaceAll("-+", "-");
    normalized = normalized.replaceAll("^-", "");
    normalized = normalized.replaceAll("-$", "");
    if (!StringUtils.hasText(normalized)) {
      normalized = "TALLY-LEDGER";
    }

    String base = normalized.length() > 64 ? normalized.substring(0, 64) : normalized;
    String candidate = base;
    int attempt = 1;
    while (accountRepository
        .findByCompanyAndCodeIgnoreCase(company, candidate)
        .filter(account -> !sameLedgerName(account.getName(), ledgerName))
        .isPresent()) {
      String suffix = "-" + attempt++;
      int maxBaseLength = Math.max(1, 64 - suffix.length());
      String prefix = base.length() > maxBaseLength ? base.substring(0, maxBaseLength) : base;
      candidate = prefix + suffix;
    }
    return candidate;
  }

  private boolean sameLedgerName(String existingAccountName, String ledgerName) {
    if (!StringUtils.hasText(existingAccountName) || !StringUtils.hasText(ledgerName)) {
      return false;
    }
    return existingAccountName.trim().equalsIgnoreCase(ledgerName.trim());
  }

  private String normalizeIdempotencyKey(String fileHash) {
    String resolved =
        StringUtils.hasText(fileHash) ? IdempotencyUtils.normalizeKey(fileHash) : fileHash;
    return idempotencyReservationService.requireKey(resolved, "Tally imports");
  }

  private void assertIdempotencyMatch(
      TallyImport record, String expectedHash, String idempotencyKey) {
    idempotencyReservationService.assertAndRepairSignature(
        record,
        idempotencyKey,
        expectedHash,
        persisted ->
            StringUtils.hasText(persisted.getIdempotencyHash())
                ? persisted.getIdempotencyHash()
                : persisted.getFileHash(),
        TallyImport::setIdempotencyHash,
        tallyImportRepository::save,
        () -> idempotencyReservationService.payloadMismatch(idempotencyKey));
  }

  private String resolveImportReference(Company company, String fileHash) {
    String companyCode = sanitizeCompanyCode(company != null ? company.getCode() : null);
    String shortHash =
        StringUtils.hasText(fileHash)
            ? fileHash.substring(0, Math.min(12, fileHash.length()))
            : "UNKNOWN";
    return "TALLY-OPEN-%s-%s".formatted(companyCode, shortHash);
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
      String fallback =
          Integer.toHexString(
              file.getOriginalFilename() != null ? file.getOriginalFilename().hashCode() : 0);
      log.warn(
          "Failed to compute SHA-256 hash for uploaded Tally file; falling back to weak hash"
              + " (cause={})",
          ex.getClass().getSimpleName());
      return fallback;
    }
  }

  private boolean isDataIntegrityViolation(Throwable error) {
    return idempotencyReservationService.isDataIntegrityViolation(error);
  }

  private TallyImportResponse toResponse(TallyImport record) {
    return new TallyImportResponse(
        record.getLedgersProcessed(),
        record.getMappedLedgers(),
        record.getAccountsCreated(),
        record.getOpeningVoucherEntriesProcessed(),
        record.getOpeningBalanceRowsProcessed(),
        deserializeStringList(record.getUnmappedGroupsJson()),
        deserializeStringList(record.getUnmappedItemsJson()),
        deserializeErrors(record.getErrorsJson()));
  }

  private String serializeStringList(List<String> values) {
    if (values == null || values.isEmpty()) {
      return null;
    }
    try {
      return objectMapper.writeValueAsString(values);
    } catch (Exception ex) {
      log.warn("Failed to serialize string list for Tally import record: {}", ex.toString());
      return null;
    }
  }

  private List<String> deserializeStringList(String json) {
    if (!StringUtils.hasText(json)) {
      return List.of();
    }
    try {
      List<String> parsed = objectMapper.readValue(json, new TypeReference<List<String>>() {});
      if (parsed == null) {
        return List.of();
      }
      return parsed.stream()
          .filter(StringUtils::hasText)
          .map(String::trim)
          .collect(Collectors.toList());
    } catch (Exception ex) {
      log.warn(
          "Failed to deserialize string list from Tally import record JSON: {}", ex.toString());
      return List.of();
    }
  }

  private String serializeErrors(List<ImportError> errors) {
    if (errors == null || errors.isEmpty()) {
      return null;
    }
    try {
      return objectMapper.writeValueAsString(errors);
    } catch (Exception ex) {
      log.warn(
          "Failed to serialize {} import error(s) for Tally import record: {}",
          errors.size(),
          ex.toString());
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
      log.warn(
          "Failed to deserialize import errors from Tally import record JSON: {}", ex.toString());
      return List.of();
    }
  }

  private Long resolveJournalEntryIdByReference(Company company, String referenceNumber) {
    return journalEntryRepository
        .findByCompanyAndReferenceNumber(company, referenceNumber)
        .map(entry -> entry.getId())
        .orElse(null);
  }

  private record TallyLedger(String name, String group) {}

  private record OpeningBalanceRow(String ledgerName, String parentGroup, BigDecimal amount) {}

  private record ParsedTallyData(List<TallyLedger> ledgers, List<OpeningBalanceRow> openingRows) {}

  private record ResolvedLedger(Account account, boolean created) {}
}
