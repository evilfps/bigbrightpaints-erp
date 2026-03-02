package com.bigbrightpaints.erp.modules.hr.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

public record AttendanceBulkImportRequest(
        @NotEmpty @Valid List<BulkMarkAttendanceRequest> records
) {
}
