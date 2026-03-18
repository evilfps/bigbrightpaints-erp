package com.bigbrightpaints.erp.truthsuite.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.core.util.CompanyTime;
import com.bigbrightpaints.erp.core.audit.AuditService;
import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.modules.admin.domain.ChangelogEntry;
import com.bigbrightpaints.erp.modules.admin.domain.ChangelogEntryRepository;
import com.bigbrightpaints.erp.modules.admin.dto.ChangelogEntryRequest;
import com.bigbrightpaints.erp.modules.admin.service.ChangelogService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

@Tag("critical")
@ExtendWith(MockitoExtension.class)
class TS_RuntimeChangelogServiceExecutableCoverageTest {

    @Mock
    private ChangelogEntryRepository changelogEntryRepository;

    @Mock
    private AuditService auditService;

    @Test
    void createUpdateSoftDeleteAndQueries_coverChangelogLifecycle() {
        Instant fixedNow = Instant.parse("2026-03-04T00:00:00Z");
        installCompanyTime(fixedNow);
        assertThat(CompanyTime.now()).isEqualTo(fixedNow);
        assertThat(CompanyTime.today()).isEqualTo(java.time.LocalDate.of(2026, 3, 4));
        ChangelogService service = new ChangelogService(changelogEntryRepository, auditService);

        when(changelogEntryRepository.save(any(ChangelogEntry.class))).thenAnswer(invocation -> {
            ChangelogEntry entry = invocation.getArgument(0);
            org.springframework.test.util.ReflectionTestUtils.setField(entry, "id", 55L);
            return entry;
        });

        ChangelogEntry created = buildEntry(55L, "1.2.0", true, false, Instant.parse("2026-03-04T00:00:00Z"));
        when(changelogEntryRepository.findByIdAndDeletedFalse(55L)).thenReturn(Optional.of(created));
        when(changelogEntryRepository.findByDeletedFalseOrderByPublishedAtDescIdDesc(any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(created), PageRequest.of(0, 20), 1));
        when(changelogEntryRepository.findFirstByHighlightedTrueAndDeletedFalseOrderByPublishedAtDescIdDesc())
                .thenReturn(Optional.of(created));

        var createResponse = service.create(new ChangelogEntryRequest(
                "1.2.0",
                "Release 1.2.0",
                "- Added changelog system",
                true));
        assertThat(createResponse.id()).isEqualTo(55L);
        assertThat(createResponse.isHighlighted()).isTrue();

        var updateResponse = service.update(55L, new ChangelogEntryRequest(
                "1.2.1",
                "Release 1.2.1",
                "- Updated highlighted notes",
                false));
        assertThat(updateResponse.version()).isEqualTo("1.2.1");
        assertThat(updateResponse.isHighlighted()).isFalse();

        var listResponse = service.list(0, 20);
        assertThat(listResponse.totalElements()).isEqualTo(1);
        assertThat(listResponse.content()).hasSize(1);

        var highlightedResponse = service.latestHighlighted();
        assertThat(highlightedResponse.id()).isEqualTo(55L);

        service.softDelete(55L);
        assertThat(created.isDeleted()).isTrue();
        assertThat(created.getDeletedAt()).isNotNull();

        verify(auditService, atLeast(3)).logAuthSuccess(any(), any(), any(), any());
    }

    @Test
    void latestHighlighted_throwsBusinessEntityNotFoundWhenNoRecordPresent() {
        ChangelogService service = new ChangelogService(changelogEntryRepository, auditService);
        when(changelogEntryRepository.findFirstByHighlightedTrueAndDeletedFalseOrderByPublishedAtDescIdDesc())
                .thenReturn(Optional.empty());

        assertThatThrownBy(service::latestHighlighted)
                .isInstanceOf(ApplicationException.class)
                .satisfies(ex -> assertThat(((ApplicationException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.BUSINESS_ENTITY_NOT_FOUND));
    }

    @Test
    void list_respectsPaginationBoundsAndRepositoryProjection() {
        ChangelogService service = new ChangelogService(changelogEntryRepository, auditService);

        ChangelogEntry first = buildEntry(88L, "2.0.0", true, false, Instant.parse("2026-03-04T10:00:00Z"));
        ChangelogEntry second = buildEntry(87L, "1.9.0", false, false, Instant.parse("2026-03-02T10:00:00Z"));
        Page<ChangelogEntry> page = new PageImpl<>(List.of(first, second), PageRequest.of(0, 2), 2);
        when(changelogEntryRepository.findByDeletedFalseOrderByPublishedAtDescIdDesc(any(PageRequest.class)))
                .thenReturn(page);

        var response = service.list(-3, 999);

        assertThat(response.page()).isEqualTo(0);
        assertThat(response.size()).isEqualTo(100);
        assertThat(response.totalElements()).isEqualTo(2);
        assertThat(response.content()).hasSize(2);
        assertThat(response.content().get(0).version()).isEqualTo("2.0.0");
    }

    private static void installCompanyTime(Instant now) {
        CompanyClock companyClock = mock(CompanyClock.class);
        java.time.LocalDate today = java.time.LocalDate.ofInstant(now, java.time.ZoneOffset.UTC);
        when(companyClock.now(any())).thenReturn(now);
        when(companyClock.now(null)).thenReturn(now);
        when(companyClock.today(any())).thenReturn(today);
        when(companyClock.today(null)).thenReturn(today);
        new CompanyTime(companyClock);
    }

    private ChangelogEntry buildEntry(Long id,
                                      String version,
                                      boolean highlighted,
                                      boolean deleted,
                                      Instant publishedAt) {
        ChangelogEntry entry = new ChangelogEntry();
        org.springframework.test.util.ReflectionTestUtils.setField(entry, "id", id);
        entry.setVersionLabel(version);
        entry.setTitle("Release " + version);
        entry.setBody("Body " + version);
        entry.setCreatedBy("admin@bbp.com");
        entry.setPublishedAt(publishedAt);
        entry.setHighlighted(highlighted);
        entry.setDeleted(deleted);
        entry.setUpdatedAt(publishedAt);
        return entry;
    }
}
