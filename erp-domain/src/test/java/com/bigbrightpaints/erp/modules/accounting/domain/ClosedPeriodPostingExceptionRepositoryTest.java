package com.bigbrightpaints.erp.modules.accounting.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.test.AbstractIntegrationTest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

class ClosedPeriodPostingExceptionRepositoryTest extends AbstractIntegrationTest {

    @Autowired
    private CompanyRepository companyRepository;

    @Autowired
    private AccountingPeriodRepository accountingPeriodRepository;

    @Autowired
    private ClosedPeriodPostingExceptionRepository repository;

    @Test
    @Transactional
    void findByCompanyAndDocumentTypeAndReference_ordersNewestFirstCaseInsensitively() {
        Company company = persistCompany();
        AccountingPeriod period = persistPeriod(company);

        ClosedPeriodPostingException older = persistException(
                company,
                period,
                "sales_return",
                "sr-1001",
                Instant.parse("2026-03-12T10:00:00Z"));
        ClosedPeriodPostingException newer = persistException(
                company,
                period,
                "SALES_RETURN",
                "SR-1001",
                Instant.parse("2026-03-12T11:00:00Z"));

        List<ClosedPeriodPostingException> results =
                repository.findByCompanyAndDocumentTypeIgnoreCaseAndDocumentReferenceIgnoreCaseOrderByApprovedAtDescIdDesc(
                        company,
                        "sales_return",
                        "sr-1001");

        assertThat(results).extracting(ClosedPeriodPostingException::getId)
                .containsExactly(newer.getId(), older.getId());
    }

    private Company persistCompany() {
        Company company = new Company();
        company.setName("Repository Coverage");
        company.setCode("COV-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        company.setTimezone("UTC");
        return companyRepository.save(company);
    }

    private AccountingPeriod persistPeriod(Company company) {
        AccountingPeriod period = new AccountingPeriod();
        period.setCompany(company);
        period.setYear(2026);
        period.setMonth(3);
        period.setStartDate(LocalDate.of(2026, 3, 1));
        period.setEndDate(LocalDate.of(2026, 3, 31));
        return accountingPeriodRepository.save(period);
    }

    private ClosedPeriodPostingException persistException(Company company,
                                                          AccountingPeriod period,
                                                          String documentType,
                                                          String documentReference,
                                                          Instant approvedAt) {
        ClosedPeriodPostingException exception = new ClosedPeriodPostingException();
        exception.setCompany(company);
        exception.setAccountingPeriod(period);
        exception.setDocumentType(documentType);
        exception.setDocumentReference(documentReference);
        exception.setReason("reason");
        exception.setApprovedBy("admin.user");
        exception.setApprovedAt(approvedAt);
        exception.setExpiresAt(approvedAt.plusSeconds(300));
        return repository.saveAndFlush(exception);
    }
}
