package com.bigbrightpaints.erp.modules.inventory.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrder;
import java.time.Instant;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("critical")
class PackagingSlipTest {

    @Test
    void lifecycleCallbacksAndAccessorsPreserveDispatchMetadata() {
        PackagingSlip slip = new PackagingSlip();
        Company company = new Company();
        company.setTimezone("UTC");
        SalesOrder order = new SalesOrder();
        slip.setCompany(company);
        slip.setSalesOrder(order);
        slip.setSlipNumber("PS-100");
        slip.setStatus("READY");
        slip.setBackorder(true);
        slip.setConfirmedAt(Instant.parse("2026-03-11T10:15:30Z"));
        slip.setConfirmedBy("factory.user");
        slip.setDispatchedAt(Instant.parse("2026-03-11T11:15:30Z"));
        slip.setDispatchNotes("Loaded");
        slip.setTransporterName("FastMove");
        slip.setDriverName("Ayaan");
        slip.setVehicleNumber("MH12AB1234");
        slip.setChallanReference("LR-7788");
        slip.setJournalEntryId(11L);
        slip.setCogsJournalEntryId(12L);
        slip.setInvoiceId(13L);

        slip.prePersist();

        assertThat(slip.getPublicId()).isNotNull();
        assertThat(slip.getCreatedAt()).isNotNull();
        assertThat(slip.getCompany()).isSameAs(company);
        assertThat(slip.getSalesOrder()).isSameAs(order);
        assertThat(slip.getSlipNumber()).isEqualTo("PS-100");
        assertThat(slip.getStatus()).isEqualTo("READY");
        assertThat(slip.isBackorder()).isTrue();
        assertThat(slip.getConfirmedAt()).isEqualTo(Instant.parse("2026-03-11T10:15:30Z"));
        assertThat(slip.getConfirmedBy()).isEqualTo("factory.user");
        assertThat(slip.getDispatchedAt()).isEqualTo(Instant.parse("2026-03-11T11:15:30Z"));
        assertThat(slip.getDispatchNotes()).isEqualTo("Loaded");
        assertThat(slip.getTransporterName()).isEqualTo("FastMove");
        assertThat(slip.getDriverName()).isEqualTo("Ayaan");
        assertThat(slip.getVehicleNumber()).isEqualTo("MH12AB1234");
        assertThat(slip.getChallanReference()).isEqualTo("LR-7788");
        assertThat(slip.getJournalEntryId()).isEqualTo(11L);
        assertThat(slip.getCogsJournalEntryId()).isEqualTo(12L);
        assertThat(slip.getInvoiceId()).isEqualTo(13L);
        assertThat(slip.getLines()).isEmpty();
    }
}
