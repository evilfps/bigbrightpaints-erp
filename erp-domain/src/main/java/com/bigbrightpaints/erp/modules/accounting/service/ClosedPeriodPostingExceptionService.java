package com.bigbrightpaints.erp.modules.accounting.service;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.core.util.CompanyTime;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriod;
import com.bigbrightpaints.erp.modules.accounting.domain.ClosedPeriodPostingException;
import com.bigbrightpaints.erp.modules.accounting.domain.ClosedPeriodPostingExceptionRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class ClosedPeriodPostingExceptionService {

    private static final String ADMIN_EXCEPTION_REQUIRED =
            "Closed-period posting requires an explicit admin exception with a mandatory reason";

    private final ClosedPeriodPostingExceptionRepository repository;

    public ClosedPeriodPostingExceptionService(ClosedPeriodPostingExceptionRepository repository) {
        this.repository = repository;
    }

    public ClosedPeriodPostingException authorize(Company company,
                                                  AccountingPeriod period,
                                                  String documentType,
                                                  String documentReference,
                                                  String reason) {
        if (company == null || period == null) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                    "Company and accounting period are required for closed-period exception handling");
        }
        String normalizedDocumentType = normalizeRequired(documentType, "documentType");
        String normalizedDocumentReference = normalizeRequired(documentReference, "documentReference");
        String normalizedReason = normalizeRequired(reason, "reason");
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new ApplicationException(ErrorCode.AUTH_INSUFFICIENT_PERMISSIONS, ADMIN_EXCEPTION_REQUIRED);
        }
        boolean admin = authentication.getAuthorities().stream().anyMatch(authority -> {
            String name = authority.getAuthority();
            return "ROLE_ADMIN".equalsIgnoreCase(name) || "ROLE_SUPER_ADMIN".equalsIgnoreCase(name);
        });
        if (!admin) {
            throw new ApplicationException(ErrorCode.AUTH_INSUFFICIENT_PERMISSIONS, ADMIN_EXCEPTION_REQUIRED);
        }
        String actor = normalizeRequired(authentication.getName(), "approvedBy");
        Instant now = CompanyTime.now(company);

        ClosedPeriodPostingException exception = repository
                .findByCompanyAndDocumentTypeIgnoreCaseAndDocumentReferenceIgnoreCaseOrderByApprovedAtDescIdDesc(
                        company,
                        normalizedDocumentType,
                        normalizedDocumentReference)
                .stream()
                .filter(existing -> existing.getExpiresAt() != null && existing.getExpiresAt().isAfter(now))
                .findFirst()
                .orElseGet(ClosedPeriodPostingException::new);

        exception.setCompany(company);
        exception.setAccountingPeriod(period);
        exception.setDocumentType(normalizedDocumentType);
        exception.setDocumentReference(normalizedDocumentReference);
        exception.setReason(normalizedReason);
        exception.setApprovedBy(actor);
        exception.setApprovedAt(now);
        exception.setExpiresAt(now.plus(1, ChronoUnit.HOURS));
        exception.setUsedBy(actor);
        exception.setUsedAt(now);
        return repository.save(exception);
    }

    public void linkJournalEntry(Company company,
                                 String documentType,
                                 String documentReference,
                                 JournalEntry journalEntry) {
        if (company == null || journalEntry == null || !StringUtils.hasText(documentType) || !StringUtils.hasText(documentReference)) {
            return;
        }
        repository.findByCompanyAndDocumentTypeIgnoreCaseAndDocumentReferenceIgnoreCaseOrderByApprovedAtDescIdDesc(
                        company,
                        documentType.trim(),
                        documentReference.trim())
                .stream()
                .findFirst()
                .ifPresent(exception -> {
                    exception.setJournalEntry(journalEntry);
                    repository.save(exception);
                });
    }

    private String normalizeRequired(String value, String field) {
        if (StringUtils.hasText(value)) {
            return value.trim();
        }
        throw new ApplicationException(ErrorCode.VALIDATION_MISSING_REQUIRED_FIELD,
                field + " is required for closed-period posting exception")
                .withDetail("field", field)
                .withDetail("exceptionType", "CLOSED_PERIOD_POSTING")
                .withDetail("reasonCode", field.toUpperCase(Locale.ROOT));
    }
}
