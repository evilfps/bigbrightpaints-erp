package com.bigbrightpaints.erp.modules.purchasing.domain;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SupplierLifecycleGuardTest {

    @Test
    void requireTransactionalUsage_allowsActiveSupplier() {
        Supplier supplier = supplier("ACTIVE", SupplierStatus.ACTIVE);

        assertThat(supplier.isTransactionalUsageAllowed()).isTrue();
        assertThatCode(() -> supplier.requireTransactionalUsage("post purchase invoices"))
                .doesNotThrowAnyException();
    }

    @Test
    void requireTransactionalUsage_blocksPendingAndApprovedSuppliersWithReferenceOnlyReason() {
        Supplier pending = supplier("PENDING", SupplierStatus.PENDING);
        Supplier approved = supplier("APPROVED", SupplierStatus.APPROVED);

        assertThatThrownBy(() -> pending.requireTransactionalUsage("create purchase orders"))
                .isInstanceOfSatisfying(ApplicationException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.BUSINESS_INVALID_STATE);
                    assertThat(ex).hasMessageContaining("pending approval")
                            .hasMessageContaining("reference only")
                            .hasMessageContaining("create purchase orders");
                });

        assertThatThrownBy(() -> approved.requireTransactionalUsage("post purchase journals"))
                .isInstanceOfSatisfying(ApplicationException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.BUSINESS_INVALID_STATE);
                    assertThat(ex).hasMessageContaining("approved but not yet active")
                            .hasMessageContaining("reference only")
                            .hasMessageContaining("post purchase journals");
                });
    }

    @Test
    void requireTransactionalUsage_defaultsNullStatusToPendingLifecycle() {
        Supplier supplier = supplier("NULL", null);
        supplier.setName("   ");
        ReflectionTestUtils.setField(supplier, "status", null);

        assertThat(supplier.isTransactionalUsageAllowed()).isFalse();
        assertThatThrownBy(() -> supplier.requireTransactionalUsage(null))
                .isInstanceOfSatisfying(ApplicationException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.BUSINESS_INVALID_STATE);
                    assertThat(ex).hasMessageContaining("Supplier is pending approval")
                            .hasMessageContaining("continue this purchasing flow");
                });
    }

    @Test
    void requireTransactionalUsage_blocksSuspendedSuppliersWithExplicitAction() {
        Supplier supplier = supplier("SUSP", SupplierStatus.SUSPENDED);

        assertThat(supplier.isTransactionalUsageAllowed()).isFalse();
        assertThatThrownBy(() -> supplier.requireTransactionalUsage("  settle supplier invoices  "))
                .isInstanceOfSatisfying(ApplicationException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.BUSINESS_INVALID_STATE);
                    assertThat(ex).hasMessageContaining("suspended")
                            .hasMessageContaining("reference only")
                            .hasMessageContaining("settle supplier invoices");
                });
    }

    private Supplier supplier(String suffix, SupplierStatus status) {
        Supplier supplier = new Supplier();
        ReflectionTestUtils.setField(supplier, "id", 100L + suffix.length());
        supplier.setCode("SUP-" + suffix);
        supplier.setName("Supplier " + suffix);
        supplier.setStatus(status);
        return supplier;
    }
}
