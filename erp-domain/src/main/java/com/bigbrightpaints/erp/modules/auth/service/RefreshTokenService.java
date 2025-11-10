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
        store.put(token, new TokenRecord(userEmail, expiresAt));
        return token;
    }

    public Optional<String> consume(String refreshToken) {
        TokenRecord record = store.get(refreshToken);
        if (record == null) {
            return Optional.empty();
        }
        if (record.expiresAt().isBefore(Instant.now())) {
            store.remove(refreshToken);
            return Optional.empty();
        }
        return Optional.of(record.userEmail());
    }

    public void revoke(String refreshToken) {
        store.remove(refreshToken);
    }

    private record TokenRecord(String userEmail, Instant expiresAt) {}
}
