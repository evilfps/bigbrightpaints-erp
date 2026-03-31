package com.bigbrightpaints.erp.core.auditaccess;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("critical")
class AuditFeedFilterTest {

  @Test
  void fetchLimit_capsTheMergeWindowAtFiveThousandRows() {
    AuditFeedFilter filter = new AuditFeedFilter(null, null, null, null, null, null, null, null, 30, 200);

    assertThat(filter.fetchLimit()).isEqualTo(5000);
  }

  @Test
  void exceedsMergeWindow_reportsWhenRequestedWindowIsTooLarge() {
    AuditFeedFilter filter = new AuditFeedFilter(null, null, null, null, null, null, null, null, 30, 200);

    assertThat(filter.exceedsMergeWindow()).isTrue();
  }

  @Test
  void exceedsMergeWindow_handlesExtremePagesWithoutOverflow() {
    AuditFeedFilter filter =
        new AuditFeedFilter(null, null, null, null, null, null, null, null, Integer.MAX_VALUE, 50);

    assertThat(filter.exceedsMergeWindow()).isTrue();
    assertThat(filter.fetchLimit()).isEqualTo(filter.maxMergeWindow());
  }

  @Test
  void normalizedFields_trimNonBlankValuesAndDropBlanks() {
    AuditFeedFilter filter =
        new AuditFeedFilter(
            null,
            null,
            " accounting ",
            " JOURNAL_ENTRY_POSTED ",
            " failure ",
            " ops@example.com ",
            " journal_entry ",
            " JE-17 ",
            0,
            50);
    AuditFeedFilter blankFilter =
        new AuditFeedFilter(null, null, " ", "\t", "", " ", "  ", "\n", 0, 50);

    assertThat(filter.normalizedModule()).isEqualTo("accounting");
    assertThat(filter.normalizedAction()).isEqualTo("JOURNAL_ENTRY_POSTED");
    assertThat(filter.normalizedStatus()).isEqualTo("failure");
    assertThat(filter.normalizedActor()).isEqualTo("ops@example.com");
    assertThat(filter.normalizedEntityType()).isEqualTo("journal_entry");
    assertThat(filter.normalizedReference()).isEqualTo("JE-17");
    assertThat(blankFilter.normalizedModule()).isNull();
    assertThat(blankFilter.normalizedAction()).isNull();
    assertThat(blankFilter.normalizedStatus()).isNull();
    assertThat(blankFilter.normalizedActor()).isNull();
    assertThat(blankFilter.normalizedEntityType()).isNull();
    assertThat(blankFilter.normalizedReference()).isNull();
  }
}
