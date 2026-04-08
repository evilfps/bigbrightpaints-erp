package com.bigbrightpaints.erp.modules.sales.service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.validation.ValidationUtils;
import com.bigbrightpaints.erp.modules.sales.domain.DealerPaymentTerms;
import com.bigbrightpaints.erp.modules.sales.dto.CreateDealerRequest;
import com.bigbrightpaints.erp.modules.sales.dto.DealerImportResponse;
import com.bigbrightpaints.erp.modules.sales.dto.DealerImportResponse.ImportError;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;

@Service
public class DealerImportService {

  private static final Set<String> REQUIRED_HEADERS =
      Set.of("name", "email", "creditlimit", "region", "paymentterms");

  private final DealerService dealerService;
  private final Validator validator;

  public DealerImportService(DealerService dealerService, Validator validator) {
    this.dealerService = dealerService;
    this.validator = validator;
  }

  public DealerImportResponse importDealers(MultipartFile file) {
    if (file == null || file.isEmpty()) {
      throw ValidationUtils.invalidInput("Dealer import file is required");
    }

    int successCount = 0;
    List<ImportError> errors = new ArrayList<>();

    try (BufferedReader reader =
            new BufferedReader(
                new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8));
        CSVParser parser =
            CSVFormat.DEFAULT
                .builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .setIgnoreHeaderCase(true)
                .setTrim(true)
                .build()
                .parse(reader)) {
      validateCsvHeaders(parser);

      for (CSVRecord record : parser) {
        try {
          CreateDealerRequest request = toCreateDealerRequest(record);
          if (request == null) {
            continue;
          }
          validateRequest(request);
          dealerService.createDealer(request);
          successCount++;
        } catch (RuntimeException ex) {
          errors.add(new ImportError(record.getRecordNumber(), resolveErrorMessage(ex)));
        }
      }
    } catch (IOException ex) {
      throw ValidationUtils.invalidInput("Unable to read dealer import CSV");
    }

    return new DealerImportResponse(successCount, errors.size(), errors);
  }

  private void validateCsvHeaders(CSVParser parser) {
    Map<String, Integer> rawHeaderMap = parser.getHeaderMap();
    if (rawHeaderMap == null || rawHeaderMap.isEmpty()) {
      throw ValidationUtils.invalidInput("CSV headers are required for dealer import");
    }
    Set<String> normalizedHeaders =
        rawHeaderMap.keySet().stream()
            .map(DealerImportService::normalizeHeader)
            .collect(java.util.stream.Collectors.toSet());
    List<String> missing =
        REQUIRED_HEADERS.stream()
            .filter(required -> !normalizedHeaders.contains(required))
            .toList();
    if (!missing.isEmpty()) {
      throw ValidationUtils.invalidInput(
          "CSV is missing required headers: " + String.join(", ", missing));
    }
  }

  private CreateDealerRequest toCreateDealerRequest(CSVRecord record) {
    Map<String, String> values = normalizeRecord(record);
    String name = readValue(values, "name", "dealername", "dealer");
    String email = readValue(values, "email", "contactemail");
    String creditLimitValue = readValue(values, "creditlimit");
    String region = readValue(values, "region");
    String paymentTermsValue = readValue(values, "paymentterms");
    String companyName = readValue(values, "companyname");
    String contactPhone = readValue(values, "contactphone", "phone", "mobile");
    String address = readValue(values, "address");
    String gstNumber = readValue(values, "gstnumber", "gst", "gstin");
    String stateCode = readValue(values, "statecode", "state");

    if (!StringUtils.hasText(name)
        && !StringUtils.hasText(email)
        && !StringUtils.hasText(creditLimitValue)
        && !StringUtils.hasText(region)
        && !StringUtils.hasText(paymentTermsValue)
        && !StringUtils.hasText(companyName)
        && !StringUtils.hasText(contactPhone)
        && !StringUtils.hasText(address)
        && !StringUtils.hasText(gstNumber)
        && !StringUtils.hasText(stateCode)) {
      return null;
    }

    if (!StringUtils.hasText(name)) {
      throw ValidationUtils.invalidInput("name is required");
    }
    if (!StringUtils.hasText(email)) {
      throw ValidationUtils.invalidInput("email is required");
    }

    BigDecimal creditLimit = parseDecimal(creditLimitValue, "creditLimit");
    DealerPaymentTerms paymentTerms = parsePaymentTerms(paymentTermsValue);
    String resolvedCompanyName =
        StringUtils.hasText(companyName) ? companyName.trim() : name.trim();
    String resolvedContactPhone =
        StringUtils.hasText(contactPhone) ? contactPhone.trim() : "IMPORT-UNSPECIFIED";

    return new CreateDealerRequest(
        name.trim(),
        resolvedCompanyName,
        email.trim(),
        resolvedContactPhone,
        StringUtils.hasText(address) ? address.trim() : null,
        creditLimit,
        StringUtils.hasText(gstNumber) ? gstNumber.trim() : null,
        StringUtils.hasText(stateCode) ? stateCode.trim() : null,
        null,
        paymentTerms,
        StringUtils.hasText(region) ? region.trim() : null);
  }

  private void validateRequest(CreateDealerRequest request) {
    Set<ConstraintViolation<CreateDealerRequest>> violations = validator.validate(request);
    if (!violations.isEmpty()) {
      throw ValidationUtils.invalidInput(formatViolations(violations));
    }
  }

  private String formatViolations(Set<ConstraintViolation<CreateDealerRequest>> violations) {
    return violations.stream()
        .sorted(Comparator.comparing(violation -> violation.getPropertyPath().toString()))
        .map(violation -> violation.getPropertyPath() + ": " + violation.getMessage())
        .collect(Collectors.joining("; "));
  }

  private BigDecimal parseDecimal(String value, String fieldName) {
    if (!StringUtils.hasText(value)) {
      return null;
    }
    try {
      BigDecimal parsed = new BigDecimal(value.trim());
      if (parsed.compareTo(BigDecimal.ZERO) < 0) {
        throw ValidationUtils.invalidInput(fieldName + " must be greater than or equal to 0");
      }
      return parsed;
    } catch (NumberFormatException ex) {
      throw ValidationUtils.invalidInput(fieldName + " must be a valid decimal value");
    }
  }

  private DealerPaymentTerms parsePaymentTerms(String value) {
    if (!StringUtils.hasText(value)) {
      return null;
    }
    String normalized = value.trim().toUpperCase(Locale.ROOT).replace('-', '_');
    return switch (normalized) {
      case "30", "NET30", "NET_30" -> DealerPaymentTerms.NET_30;
      case "60", "NET60", "NET_60" -> DealerPaymentTerms.NET_60;
      case "90", "NET90", "NET_90" -> DealerPaymentTerms.NET_90;
      default ->
          throw ValidationUtils.invalidInput("paymentTerms must be one of NET_30, NET_60, NET_90");
    };
  }

  private String resolveErrorMessage(RuntimeException ex) {
    if (ex instanceof ApplicationException applicationException
        && StringUtils.hasText(applicationException.getUserMessage())) {
      return applicationException.getUserMessage();
    }
    if (StringUtils.hasText(ex.getMessage())) {
      return ex.getMessage();
    }
    return "Unexpected error while importing dealer row";
  }

  private static Map<String, String> normalizeRecord(CSVRecord record) {
    Map<String, String> normalized = new LinkedHashMap<>();
    for (Map.Entry<String, String> entry : record.toMap().entrySet()) {
      normalized.put(normalizeHeader(entry.getKey()), entry.getValue());
    }
    return normalized;
  }

  private static String readValue(Map<String, String> values, String... keys) {
    for (String key : keys) {
      String value = values.get(normalizeHeader(key));
      if (StringUtils.hasText(value)) {
        return value;
      }
    }
    return null;
  }

  private static String normalizeHeader(String header) {
    if (!StringUtils.hasText(header)) {
      return "";
    }
    return header.trim().toLowerCase(Locale.ROOT).replaceAll("[_\\s-]+", "");
  }
}
