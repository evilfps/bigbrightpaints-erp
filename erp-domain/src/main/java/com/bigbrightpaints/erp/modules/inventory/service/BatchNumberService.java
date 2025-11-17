package com.bigbrightpaints.erp.modules.inventory.service;

import com.bigbrightpaints.erp.core.service.NumberSequenceService;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGood;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterial;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class BatchNumberService {

    private static final int DEFAULT_PADDING = 3;

    private final NumberSequenceService numberSequenceService;

    public BatchNumberService(NumberSequenceService numberSequenceService) {
        this.numberSequenceService = numberSequenceService;
    }

    public String nextRawMaterialBatchCode(RawMaterial material) {
        Company company = material.getCompany();
        YearMonth period = YearMonth.now(resolveZone(company));
        String sku = sanitize(material.getSku() != null ? material.getSku() : String.valueOf(material.getId()));
        String key = "RM-%s-%s".formatted(sku, formatPeriod(period));
        return formatted(key, numberSequenceService.nextValue(company, key));
    }

    public String nextFinishedGoodBatchCode(FinishedGood finishedGood, LocalDate packedDate) {
        Company company = finishedGood.getCompany();
        YearMonth period = packedDate != null
                ? YearMonth.of(packedDate.getYear(), packedDate.getMonth())
                : YearMonth.now(resolveZone(company));
        String sku = sanitize(finishedGood.getProductCode());
        String key = "%s-FG-%s-%s".formatted(company.getCode(), sku, formatPeriod(period));
        return formatted(key, numberSequenceService.nextValue(company, key));
    }

    private ZoneId resolveZone(Company company) {
        return ZoneId.of(company.getTimezone() == null ? "UTC" : company.getTimezone());
    }

    private String formatPeriod(YearMonth period) {
        return "%04d%02d".formatted(period.getYear(), period.getMonthValue());
    }

    private String formatted(String key, long sequence) {
        return "%s-%0" + DEFAULT_PADDING + "d".formatted(key, sequence);
    }

    private String sanitize(String value) {
        if (!StringUtils.hasText(value)) {
            return "ITEM";
        }
        return value.replaceAll("[^A-Z0-9]", "").toUpperCase();
    }
}
