package com.bigbrightpaints.erp.modules.accounting.event;

/**
 * Domain event published whenever account metadata or balances change
 * so that dependent caches can be invalidated safely.
 */
public record AccountCacheInvalidatedEvent(Long companyId) {
}
