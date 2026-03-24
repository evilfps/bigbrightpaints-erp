package com.bigbrightpaints.erp.modules.inventory.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PackagingSlipEntityTest {

    @Test
    void logisticsFields_roundTripThroughEntityAccessors() {
        PackagingSlip slip = new PackagingSlip();

        slip.setSlipNumber("PS-500");
        slip.setStatus("DISPATCHED");
        slip.setBackorder(true);
        slip.setTransporterName("FastMove");
        slip.setDriverName("Ayaan");
        slip.setVehicleNumber("MH12AB1234");
        slip.setChallanReference("LR-500");

        assertThat(slip.getSlipNumber()).isEqualTo("PS-500");
        assertThat(slip.getStatus()).isEqualTo("DISPATCHED");
        assertThat(slip.isBackorder()).isTrue();
        assertThat(slip.getTransporterName()).isEqualTo("FastMove");
        assertThat(slip.getDriverName()).isEqualTo("Ayaan");
        assertThat(slip.getVehicleNumber()).isEqualTo("MH12AB1234");
        assertThat(slip.getChallanReference()).isEqualTo("LR-500");
    }
}
