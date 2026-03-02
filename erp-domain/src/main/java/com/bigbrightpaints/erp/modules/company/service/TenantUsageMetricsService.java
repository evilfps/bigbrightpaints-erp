package com.bigbrightpaints.erp.modules.company.service;

import com.bigbrightpaints.erp.core.config.SystemSetting;
import com.bigbrightpaints.erp.core.config.SystemSettingsRepository;
import com.bigbrightpaints.erp.core.validation.ValidationUtils;
import com.bigbrightpaints.erp.core.util.CompanyTime;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class TenantUsageMetricsService {

    private static final String API_CALL_COUNT_PREFIX = "tenant.usage.api-call-count.";
    private static final String LAST_ACTIVITY_AT_PREFIX = "tenant.usage.last-activity-at.";

    private final CompanyRepository companyRepository;
    private final SystemSettingsRepository systemSettingsRepository;

    public TenantUsageMetricsService(CompanyRepository companyRepository,
                                     SystemSettingsRepository systemSettingsRepository) {
        this.companyRepository = companyRepository;
        this.systemSettingsRepository = systemSettingsRepository;
    }

    public void recordApiCall(String companyCode) {
        if (!StringUtils.hasText(companyCode)) {
            return;
        }
        Optional<Company> companyOptional = companyRepository.findByCodeIgnoreCase(companyCode.trim());
        if (companyOptional.isEmpty()) {
            return;
        }
        Company company = companyOptional.get();
        Long companyId = company.getId();
        if (companyId == null) {
            return;
        }
        systemSettingsRepository.incrementLongSetting(apiCallCountKey(companyId));
        persistSetting(lastActivityAtKey(companyId), CompanyTime.now().toString());
    }

    public long getApiCallCount(Long companyId) {
        if (companyId == null) {
            return 0L;
        }
        return parseLong(readSetting(apiCallCountKey(companyId)), 0L);
    }

    public Instant getLastActivityAt(Long companyId) {
        if (companyId == null) {
            return null;
        }
        String raw = readSetting(lastActivityAtKey(companyId));
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        try {
            return Instant.parse(raw.trim());
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private String readSetting(String key) {
        return systemSettingsRepository.findById(key)
                .map(SystemSetting::getValue)
                .orElse(null);
    }

    private void persistSetting(String key, String value) {
        systemSettingsRepository.save(new SystemSetting(key, value));
    }

    private long parseLong(String raw, long fallback) {
        if (!StringUtils.hasText(raw)) {
            return fallback;
        }
        try {
            return Long.parseLong(ValidationUtils.requireNotBlank(raw, "setting value"));
        } catch (RuntimeException ex) {
            return fallback;
        }
    }

    private String apiCallCountKey(Long companyId) {
        return API_CALL_COUNT_PREFIX + companyId;
    }

    private String lastActivityAtKey(Long companyId) {
        return LAST_ACTIVITY_AT_PREFIX + companyId;
    }
}
