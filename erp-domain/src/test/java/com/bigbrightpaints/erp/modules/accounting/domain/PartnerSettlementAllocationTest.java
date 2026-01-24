package com.bigbrightpaints.erp.modules.accounting.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class PartnerSettlementAllocationTest {

    @Test
    void prePersist_setsCreatedAtWhenMissing() {
        PartnerSettlementAllocation allocation = new PartnerSettlementAllocation();

        allocation.prePersist();

        assertThat(allocation.getCreatedAt()).isNotNull();
    }

    @Test
    void prePersist_setsSettlementDateWhenMissing() {
        PartnerSettlementAllocation allocation = new PartnerSettlementAllocation();

        allocation.prePersist();

        assertThat(allocation.getSettlementDate()).isNotNull();
    }

    @Test
    void prePersist_doesNotOverrideSettlementDate() {
        PartnerSettlementAllocation allocation = new PartnerSettlementAllocation();
        LocalDate settlementDate = LocalDate.of(2024, 2, 2);
        allocation.setSettlementDate(settlementDate);

        allocation.prePersist();

        assertThat(allocation.getSettlementDate()).isEqualTo(settlementDate);
    }
}
