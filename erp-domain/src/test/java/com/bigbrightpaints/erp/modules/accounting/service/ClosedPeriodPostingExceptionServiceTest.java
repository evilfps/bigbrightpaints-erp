package com.bigbrightpaints.erp.modules.accounting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriod;
import com.bigbrightpaints.erp.modules.accounting.domain.ClosedPeriodPostingException;
import com.bigbrightpaints.erp.modules.accounting.domain.ClosedPeriodPostingExceptionRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

@ExtendWith(MockitoExtension.class)
class ClosedPeriodPostingExceptionServiceTest {

    @Mock
    private ClosedPeriodPostingExceptionRepository repository;

    @InjectMocks
    private ClosedPeriodPostingExceptionService service;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void authorize_reusesActiveExceptionForAdminAndMarksItUsed() {
        Company company = company();
        AccountingPeriod period = period(company);
        ClosedPeriodPostingException existing = new ClosedPeriodPostingException();
        existing.setExpiresAt(Instant.now().plusSeconds(600));
        when(repository.findByCompanyAndDocumentTypeIgnoreCaseAndDocumentReferenceIgnoreCaseOrderByApprovedAtDescIdDesc(
                company,
                "SALES_RETURN",
                "SR-1001")).thenReturn(List.of(existing));
        when(repository.save(existing)).thenReturn(existing);
        authenticate("admin.user", "ROLE_ADMIN");

        ClosedPeriodPostingException saved = service.authorize(
                company,
                period,
                " SALES_RETURN ",
                " SR-1001 ",
                " close-month exception ");

        assertThat(saved).isSameAs(existing);
        assertThat(saved.getCompany()).isSameAs(company);
        assertThat(saved.getAccountingPeriod()).isSameAs(period);
        assertThat(saved.getDocumentType()).isEqualTo("SALES_RETURN");
        assertThat(saved.getDocumentReference()).isEqualTo("SR-1001");
        assertThat(saved.getReason()).isEqualTo("close-month exception");
        assertThat(saved.getApprovedBy()).isEqualTo("admin.user");
        assertThat(saved.getUsedBy()).isEqualTo("admin.user");
        assertThat(saved.getApprovedAt()).isNotNull();
        assertThat(saved.getUsedAt()).isEqualTo(saved.getApprovedAt());
        assertThat(saved.getExpiresAt()).isAfter(saved.getApprovedAt());
        verify(repository).save(existing);
    }

    @Test
    void authorize_rejectsMissingAdminAuthority() {
        Company company = company();
        AccountingPeriod period = period(company);
        authenticate("accounting.user", "ROLE_ACCOUNTING");

        assertThatThrownBy(() -> service.authorize(company, period, "JOURNAL", "JE-100", "override"))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("explicit admin exception");
    }

    @Test
    void authorize_rejectsMissingAuthentication() {
        Company company = company();
        AccountingPeriod period = period(company);
        SecurityContextHolder.clearContext();

        assertThatThrownBy(() -> service.authorize(company, period, "JOURNAL", "JE-100", "override"))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("explicit admin exception");
    }

    @Test
    void authorize_rejectsUnauthenticatedPrincipal() {
        Company company = company();
        AccountingPeriod period = period(company);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("admin.user", "N/A", List.of())
        );

        assertThatThrownBy(() -> service.authorize(company, period, "JOURNAL", "JE-101", "override"))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("explicit admin exception");
    }

    @Test
    void authorize_rejectsExplicitlyUnauthenticatedAdminPrincipal() {
        Company company = company();
        AccountingPeriod period = period(company);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("admin.user", "N/A")
        );

        assertThatThrownBy(() -> service.authorize(company, period, "JOURNAL", "JE-102", "override"))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("explicit admin exception");
    }

    @Test
    void authorize_requiresNormalizedFields() {
        Company company = company();
        AccountingPeriod period = period(company);
        authenticate("admin.user", "ROLE_SUPER_ADMIN");

        assertThatThrownBy(() -> service.authorize(company, period, " ", "DOC-1", "reason"))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("documentType is required");
        assertThatThrownBy(() -> service.authorize(company, period, "JOURNAL", " ", "reason"))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("documentReference is required");
        assertThatThrownBy(() -> service.authorize(company, period, "JOURNAL", "DOC-1", " "))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("reason is required");
    }

    @Test
    void authorize_rejectsMissingCompany() {
        authenticate("admin.user", "ROLE_ADMIN");

        assertThatThrownBy(() -> service.authorize(null, period(company()), "JOURNAL", "DOC-1", "reason"))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("Company and accounting period are required");
    }

    @Test
    void authorize_rejectsMissingPeriod() {
        authenticate("admin.user", "ROLE_ADMIN");

        assertThatThrownBy(() -> service.authorize(company(), null, "JOURNAL", "DOC-1", "reason"))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("Company and accounting period are required");
    }

    @Test
    void authorize_createsNewExceptionWhenLatestMatchIsExpired() {
        Company company = company();
        AccountingPeriod period = period(company);
        ClosedPeriodPostingException expired = new ClosedPeriodPostingException();
        expired.setExpiresAt(Instant.now().minusSeconds(60));
        when(repository.findByCompanyAndDocumentTypeIgnoreCaseAndDocumentReferenceIgnoreCaseOrderByApprovedAtDescIdDesc(
                company,
                "SALES_RETURN",
                "SR-1002")).thenReturn(List.of(expired));
        when(repository.save(org.mockito.ArgumentMatchers.any(ClosedPeriodPostingException.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        authenticate("admin.user", "ROLE_ADMIN");

        ClosedPeriodPostingException saved = service.authorize(
                company,
                period,
                " SALES_RETURN ",
                " SR-1002 ",
                " expired exception replaced ");

        assertThat(saved).isNotSameAs(expired);
        assertThat(saved.getDocumentReference()).isEqualTo("SR-1002");
        assertThat(saved.getReason()).isEqualTo("expired exception replaced");
        assertThat(saved.getApprovedBy()).isEqualTo("admin.user");
    }

    @Test
    void authorize_superAdminReusesFirstUnexpiredExceptionAfterExpiredHistory() {
        Company company = company();
        AccountingPeriod period = period(company);
        ClosedPeriodPostingException expired = new ClosedPeriodPostingException();
        expired.setExpiresAt(Instant.now().minusSeconds(60));
        ClosedPeriodPostingException active = new ClosedPeriodPostingException();
        active.setExpiresAt(Instant.now().plusSeconds(600));
        when(repository.findByCompanyAndDocumentTypeIgnoreCaseAndDocumentReferenceIgnoreCaseOrderByApprovedAtDescIdDesc(
                company,
                "JOURNAL",
                "JE-202")).thenReturn(List.of(expired, active));
        when(repository.save(active)).thenReturn(active);
        authenticate("super.admin", "ROLE_SUPER_ADMIN");

        ClosedPeriodPostingException saved = service.authorize(
                company,
                period,
                " JOURNAL ",
                " JE-202 ",
                " super admin override ");

        assertThat(saved).isSameAs(active);
        assertThat(saved.getApprovedBy()).isEqualTo("super.admin");
        assertThat(saved.getUsedBy()).isEqualTo("super.admin");
        verify(repository).save(active);
    }

    @Test
    void authorize_skipsEntriesWithoutExpiryBeforeReusingActiveException() {
        Company company = company();
        AccountingPeriod period = period(company);
        ClosedPeriodPostingException missingExpiry = new ClosedPeriodPostingException();
        ClosedPeriodPostingException active = new ClosedPeriodPostingException();
        active.setExpiresAt(Instant.now().plusSeconds(600));
        when(repository.findByCompanyAndDocumentTypeIgnoreCaseAndDocumentReferenceIgnoreCaseOrderByApprovedAtDescIdDesc(
                company,
                "JOURNAL",
                "JE-203")).thenReturn(List.of(missingExpiry, active));
        when(repository.save(active)).thenReturn(active);
        authenticate("admin.user", "ROLE_ADMIN");

        ClosedPeriodPostingException saved = service.authorize(
                company,
                period,
                " JOURNAL ",
                " JE-203 ",
                " active exception reused ");

        assertThat(saved).isSameAs(active);
        verify(repository).save(active);
    }

    @Test
    void linkJournalEntry_updatesLatestMatchingException() {
        Company company = company();
        ClosedPeriodPostingException existing = new ClosedPeriodPostingException();
        JournalEntry journalEntry = new JournalEntry();
        when(repository.findByCompanyAndDocumentTypeIgnoreCaseAndDocumentReferenceIgnoreCaseOrderByApprovedAtDescIdDesc(
                company,
                "JOURNAL",
                "JE-100")).thenReturn(List.of(existing));
        when(repository.save(existing)).thenReturn(existing);

        service.linkJournalEntry(company, " JOURNAL ", " JE-100 ", journalEntry);

        assertThat(existing.getJournalEntry()).isSameAs(journalEntry);
        verify(repository).save(existing);
    }

    @Test
    void linkJournalEntry_skipsWhenNoMatchingExceptionExists() {
        Company company = company();
        JournalEntry journalEntry = new JournalEntry();
        when(repository.findByCompanyAndDocumentTypeIgnoreCaseAndDocumentReferenceIgnoreCaseOrderByApprovedAtDescIdDesc(
                company,
                "JOURNAL",
                "JE-404")).thenReturn(List.of());

        service.linkJournalEntry(company, "JOURNAL", "JE-404", journalEntry);

        verify(repository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void linkJournalEntry_ignoresIncompleteInputs() {
        service.linkJournalEntry(null, "JOURNAL", "REF", new JournalEntry());
        service.linkJournalEntry(company(), " ", "REF", new JournalEntry());
        service.linkJournalEntry(company(), "JOURNAL", " ", new JournalEntry());
        service.linkJournalEntry(company(), "JOURNAL", "REF", null);

        verify(repository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    private void authenticate(String username, String... authorities) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        username,
                        "N/A",
                        java.util.Arrays.stream(authorities)
                                .map(SimpleGrantedAuthority::new)
                                .toList()
                )
        );
    }

    private Company company() {
        Company company = new Company();
        company.setName("Coverage Co");
        company.setCode("COV");
        company.setTimezone("UTC");
        return company;
    }

    private AccountingPeriod period(Company company) {
        AccountingPeriod period = new AccountingPeriod();
        period.setCompany(company);
        period.setYear(2026);
        period.setMonth(3);
        period.setStartDate(LocalDate.of(2026, 3, 1));
        period.setEndDate(LocalDate.of(2026, 3, 31));
        return period;
    }
}
