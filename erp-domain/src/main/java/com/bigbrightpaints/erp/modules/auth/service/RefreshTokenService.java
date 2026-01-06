package com.bigbrightpaints.erp.modules.auth.service;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class RefreshTokenService {

    private final Map<String, TokenRecord> store = new ConcurrentHashMap<>();

    public String issue(String userEmail, Instant expiresAt) {
        String token = UUID.randomUUID().toString();
        store.put(token, new TokenRecord(userEmail, Instant.now(), expiresAt));
        return token;
    }

    public Optional<TokenRecord> consume(String refreshToken) {
        TokenRecord record = store.remove(refreshToken);
        if (record == null) {
            return Optional.empty();
        }
        if (record.expiresAt().isBefore(Instant.now())) {
            return Optional.empty();
        }
        return Optional.of(record);
    }

    public void revoke(String refreshToken) {
        store.remove(refreshToken);
    }

    public void revokeAllForUser(String userEmail) {
        if (userEmail == null) {
            return;
        }
        store.entrySet().removeIf(entry -> userEmail.equalsIgnoreCase(entry.getValue().userEmail()));
    }

    public record TokenRecord(String userEmail, Instant issuedAt, Instant expiresAt) {}
}
