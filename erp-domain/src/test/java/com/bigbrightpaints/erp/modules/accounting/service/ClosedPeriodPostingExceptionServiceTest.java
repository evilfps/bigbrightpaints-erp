package com.bigbrightpaints.erp.modules.accounting.service;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.util.CompanyTime;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriod;
import com.bigbrightpaints.erp.modules.accounting.domain.ClosedPeriodPostingException;
import com.bigbrightpaints.erp.modules.accounting.domain.ClosedPeriodPostingExceptionRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@Tag("critical")
class ClosedPeriodPostingExceptionServiceTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-03-10T00:00:00Z");

    @Mock
    private ClosedPeriodPostingExceptionRepository repository;

    private ClosedPeriodPostingExceptionService service;

    @BeforeEach
    void setUp() {
        service = new ClosedPeriodPostingExceptionService(repository);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
        ReflectionTestUtils.setField(CompanyTime.class, "companyClock", null);
    }

    @Test
    void authorize_adminPersistsNormalizedException() {
        installFixedClock();
        Company company = company();
        AccountingPeriod period = new AccountingPeriod();
        authenticate("admin.user", "ROLE_ADMIN");
        when(repository.findByCompanyAndDocumentTypeIgnoreCaseAndDocumentReferenceIgnoreCaseOrderByApprovedAtDescIdDesc(
                company,
                "sales_return",
                "SR-001")).thenReturn(List.of());
        when(repository.save(any(ClosedPeriodPostingException.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ClosedPeriodPostingException exception = service.authorize(
                company,
                period,
                "  sales_return  ",
                "  SR-001  ",
                "  late approval  ");

        assertThat(exception.getCompany()).isSameAs(company);
        assertThat(exception.getAccountingPeriod()).isSameAs(period);
        assertThat(exception.getDocumentType()).isEqualTo("sales_return");
        assertThat(exception.getDocumentReference()).isEqualTo("SR-001");
        assertThat(exception.getReason()).isEqualTo("late approval");
        assertThat(exception.getApprovedBy()).isEqualTo("admin.user");
        assertThat(exception.getApprovedAt()).isEqualTo(FIXED_NOW);
        assertThat(exception.getExpiresAt()).isEqualTo(FIXED_NOW.plusSeconds(3600));
        assertThat(exception.getUsedBy()).isEqualTo("admin.user");
        assertThat(exception.getUsedAt()).isEqualTo(FIXED_NOW);
    }

    @Test
    void authorize_reusesUnexpiredException() {
        installFixedClock();
        Company company = company();
        AccountingPeriod period = new AccountingPeriod();
        ClosedPeriodPostingException existing = new ClosedPeriodPostingException();
        existing.setExpiresAt(FIXED_NOW.plusSeconds(60));
        authenticate("super.admin", "ROLE_SUPER_ADMIN");
        when(repository.findByCompanyAndDocumentTypeIgnoreCaseAndDocumentReferenceIgnoreCaseOrderByApprovedAtDescIdDesc(
                company,
                "PURCHASE_RETURN",
                "PR-001")).thenReturn(List.of(existing));
        when(repository.save(existing)).thenReturn(existing);

        ClosedPeriodPostingException saved = service.authorize(
                company,
                period,
                "PURCHASE_RETURN",
                "PR-001",
                "approved");

        assertThat(saved).isSameAs(existing);
        assertThat(saved.getApprovedBy()).isEqualTo("super.admin");
        assertThat(saved.getAccountingPeriod()).isSameAs(period);
    }

    @Test
    void authorize_ignoresExpiredAndUndatedExistingExceptions() {
        installFixedClock();
        Company company = company();
        AccountingPeriod period = new AccountingPeriod();
        ClosedPeriodPostingException undated = new ClosedPeriodPostingException();
        ClosedPeriodPostingException expired = new ClosedPeriodPostingException();
        expired.setExpiresAt(FIXED_NOW.minusSeconds(60));
        ClosedPeriodPostingException unexpired = new ClosedPeriodPostingException();
        unexpired.setExpiresAt(FIXED_NOW.plusSeconds(60));
        authenticate("super.admin", "ROLE_SUPER_ADMIN");
        when(repository.findByCompanyAndDocumentTypeIgnoreCaseAndDocumentReferenceIgnoreCaseOrderByApprovedAtDescIdDesc(
                company,
                "PURCHASE_RETURN",
                "PR-002")).thenReturn(List.of(undated, expired, unexpired));
        when(repository.save(unexpired)).thenReturn(unexpired);

        ClosedPeriodPostingException saved = service.authorize(
                company,
                period,
                "PURCHASE_RETURN",
                "PR-002",
                "approved");

        assertThat(saved).isSameAs(unexpired);
    }

    @Test
    void authorize_rejectsMissingInputsAndNonAdminActors() {
        Company company = company();
        AccountingPeriod period = new AccountingPeriod();

        assertThatThrownBy(() -> service.authorize(null, period, "DOC", "REF", "reason"))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("Company and accounting period are required");

        authenticate("accounting.user", "ROLE_ACCOUNTING");
        assertThatThrownBy(() -> service.authorize(company, period, "DOC", "REF", "reason"))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("Closed-period posting requires an explicit admin exception");

        authenticate("admin.user", "ROLE_ADMIN");
        assertThatThrownBy(() -> service.authorize(company, period, "DOC", "REF", "   "))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("reason is required for closed-period posting exception");

        verify(repository, never()).save(any(ClosedPeriodPostingException.class));
    }

    @Test
    void linkJournalEntry_updatesFirstMatchingExceptionOnlyWhenInputsPresent() {
        Company company = company();
        ClosedPeriodPostingException exception = new ClosedPeriodPostingException();
        JournalEntry journalEntry = new JournalEntry();
        when(repository.findByCompanyAndDocumentTypeIgnoreCaseAndDocumentReferenceIgnoreCaseOrderByApprovedAtDescIdDesc(
                company,
                "SALES_RETURN",
                "SR-009")).thenReturn(List.of(exception));

        service.linkJournalEntry(company, "  SALES_RETURN  ", "  SR-009  ", journalEntry);
        service.linkJournalEntry(company, " ", "SR-009", journalEntry);

        ArgumentCaptor<ClosedPeriodPostingException> captor = ArgumentCaptor.forClass(ClosedPeriodPostingException.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getJournalEntry()).isSameAs(journalEntry);
    }

    @Test
    void authorize_requiresAuthenticatedAdminAnd_linkJournalEntrySkipsMissingMatches() {
        Company company = company();
        AccountingPeriod period = new AccountingPeriod();

        assertThatThrownBy(() -> service.authorize(company, period, "DOC", "REF", "reason"))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("Closed-period posting requires an explicit admin exception");

        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.unauthenticated(
                "admin.user",
                "N/A"));
        assertThatThrownBy(() -> service.authorize(company, period, "DOC", "REF", "reason"))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("Closed-period posting requires an explicit admin exception");

        when(repository.findByCompanyAndDocumentTypeIgnoreCaseAndDocumentReferenceIgnoreCaseOrderByApprovedAtDescIdDesc(
                company,
                "DOC",
                "REF")).thenReturn(List.of());

        service.linkJournalEntry(company, "DOC", "REF", new JournalEntry());

        verify(repository, never()).save(any(ClosedPeriodPostingException.class));
    }

    @Test
    void entityLifecycle_populatesPersistDefaultsAndExposesAccessors() {
        installFixedClock();
        Company company = company();
        AccountingPeriod period = new AccountingPeriod();
        JournalEntry journalEntry = new JournalEntry();
        ClosedPeriodPostingException exception = new ClosedPeriodPostingException();

        exception.setCompany(company);
        exception.setAccountingPeriod(period);
        exception.setDocumentType("DOC");
        exception.setDocumentReference("REF");
        exception.setReason("reason");
        exception.setApprovedBy("admin.user");
        exception.setExpiresAt(FIXED_NOW.plusSeconds(3600));
        exception.setUsedBy("admin.user");
        exception.setUsedAt(FIXED_NOW);
        exception.setJournalEntry(journalEntry);
        exception.prePersist();

        assertThat(exception.getPublicId()).isNotNull();
        assertThat(exception.getApprovedAt()).isEqualTo(FIXED_NOW);
        assertThat(exception.getCompany()).isSameAs(company);
        assertThat(exception.getAccountingPeriod()).isSameAs(period);
        assertThat(exception.getDocumentType()).isEqualTo("DOC");
        assertThat(exception.getDocumentReference()).isEqualTo("REF");
        assertThat(exception.getReason()).isEqualTo("reason");
        assertThat(exception.getApprovedBy()).isEqualTo("admin.user");
        assertThat(exception.getExpiresAt()).isEqualTo(FIXED_NOW.plusSeconds(3600));
        assertThat(exception.getUsedBy()).isEqualTo("admin.user");
        assertThat(exception.getUsedAt()).isEqualTo(FIXED_NOW);
        assertThat(exception.getJournalEntry()).isSameAs(journalEntry);
        assertThat(exception.getId()).isNull();
    }

    @Test
    void entityLifecycle_preservesExistingPublicIdAndApprovedAt() {
        ClosedPeriodPostingException exception = new ClosedPeriodPostingException();
        java.util.UUID publicId = java.util.UUID.randomUUID();
        Instant approvedAt = FIXED_NOW.minusSeconds(120);

        ReflectionTestUtils.setField(exception, "publicId", publicId);
        exception.setApprovedAt(approvedAt);

        exception.prePersist();

        assertThat(exception.getPublicId()).isEqualTo(publicId);
        assertThat(exception.getApprovedAt()).isEqualTo(approvedAt);
    }

    private Company company() {
        Company company = new Company();
        company.setTimezone("UTC");
        return company;
    }

    private void installFixedClock() {
        var companyClock = org.mockito.Mockito.mock(com.bigbrightpaints.erp.core.util.CompanyClock.class);
        when(companyClock.now(any())).thenReturn(FIXED_NOW);
        ReflectionTestUtils.setField(CompanyTime.class, "companyClock", companyClock);
    }

    private void authenticate(String username, String role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                username,
                "N/A",
                List.of(new SimpleGrantedAuthority(role))));
    }
}
