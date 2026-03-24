package com.bigbrightpaints.erp.modules.company.domain;

import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.core.util.CompanyTime;
import java.time.Instant;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CoATemplateTest {

    @Test
    void prePersist_setsCreatedAtFromCompanyTimeWhenMissing() {
        CompanyClock companyClock = mock(CompanyClock.class);
        Instant now = Instant.parse("2026-03-18T06:30:00Z");
        when(companyClock.now(null)).thenReturn(now);
        new CompanyTime(companyClock);

        CoATemplate template = new CoATemplate();
        template.setCode("DEFAULT");
        template.setName("Default");
        template.setDescription("Default chart");
        template.setAccountCount(10);

        template.prePersist();

        assertThat(template.getCreatedAt()).isEqualTo(now);
        assertThat(template.getPublicId()).isNotNull();
    }
}
