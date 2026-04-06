package com.bigbrightpaints.erp.modules.accounting.service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.company.domain.Company;

@Service
final class AccountingFacadeAccountResolver {

  private static final long CACHE_TTL_MILLIS = 5 * 60 * 1000;

  private final CompanyScopedAccountingLookupService accountingLookupService;
  private final Map<String, CachedAccount> accountCache = new ConcurrentHashMap<>();

  AccountingFacadeAccountResolver(CompanyScopedAccountingLookupService accountingLookupService) {
    this.accountingLookupService = accountingLookupService;
  }

  Account requireAccountById(Company company, Long accountId, String accountType) {
    if (company == null || company.getId() == null || accountId == null) {
      throw new ApplicationException(
              ErrorCode.BUSINESS_ENTITY_NOT_FOUND, accountType + " not found")
          .withDetail("accountId", accountId);
    }
    String cacheKey = company.getId() + ":" + accountId;
    CachedAccount cached = accountCache.get(cacheKey);
    if (cached != null && !cached.isExpired()) {
      return cached.account();
    }
    Account account = fetchAccount(company, accountId, accountType);
    accountCache.put(cacheKey, new CachedAccount(account, System.currentTimeMillis()));
    return account;
  }

  void clearAccountCache() {
    clearAccountCache(null);
  }

  void clearAccountCache(Long companyId) {
    if (companyId == null) {
      accountCache.clear();
      return;
    }
    String prefix = companyId + ":";
    accountCache.keySet().removeIf(key -> StringUtils.hasText(key) && key.startsWith(prefix));
  }

  private Account fetchAccount(Company company, Long accountId, String accountType) {
    try {
      return accountingLookupService.requireAccount(company, accountId);
    } catch (IllegalArgumentException ex) {
      throw new ApplicationException(
              ErrorCode.BUSINESS_ENTITY_NOT_FOUND, accountType + " not found")
          .withDetail("accountId", accountId);
    }
  }

  private record CachedAccount(Account account, long cachedAt) {
    boolean isExpired() {
      return System.currentTimeMillis() - cachedAt > CACHE_TTL_MILLIS;
    }
  }
}
