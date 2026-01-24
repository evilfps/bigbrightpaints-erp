package com.bigbrightpaints.erp.orchestrator.event;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DomainEventTest {

    @Test
    void of_populatesFields() {
        DomainEvent event = DomainEvent.of("CREATED", "COMP", "USER", "ORDER", "1", "payload");
        assertThat(event.id()).isNotNull();
        assertThat(event.timestamp()).isNotNull();
        assertThat(event.eventType()).isEqualTo("CREATED");
        assertThat(event.companyId()).isEqualTo("COMP");
        assertThat(event.userId()).isEqualTo("USER");
        assertThat(event.entity()).isEqualTo("ORDER");
        assertThat(event.entityId()).isEqualTo("1");
        assertThat(event.payload()).isEqualTo("payload");
    }

    @Test
    void of_generatesUniqueIds() {
        DomainEvent first = DomainEvent.of("CREATED", "COMP", "USER", "ORDER", "1", null);
        DomainEvent second = DomainEvent.of("CREATED", "COMP", "USER", "ORDER", "1", null);
        assertThat(first.id()).isNotEqualTo(second.id());
    }
}
