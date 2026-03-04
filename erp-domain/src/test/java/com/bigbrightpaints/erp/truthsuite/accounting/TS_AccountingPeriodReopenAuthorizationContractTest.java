package com.bigbrightpaints.erp.truthsuite.accounting;

import com.bigbrightpaints.erp.truthsuite.support.TruthSuiteFileAssert;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("critical")
@Tag("security")
class TS_AccountingPeriodReopenAuthorizationContractTest {

    private static final String ACCOUNTING_CONTROLLER =
            "src/main/java/com/bigbrightpaints/erp/modules/accounting/controller/AccountingController.java";

    @Test
    void reopenEndpointIsRestrictedToSuperAdminOnly() {
        TruthSuiteFileAssert.assertContainsInOrder(
                ACCOUNTING_CONTROLLER,
                "@PostMapping(\"/periods/{periodId}/reopen\")",
                "@PreAuthorize(\"hasAuthority('ROLE_SUPER_ADMIN')\")",
                "public ResponseEntity<ApiResponse<AccountingPeriodDto>> reopenPeriod(");
    }
}
