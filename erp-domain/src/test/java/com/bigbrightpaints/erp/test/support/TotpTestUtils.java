package com.bigbrightpaints.erp.test.support;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.Locale;

public final class TotpTestUtils {

    private static final int TIME_STEP_SECONDS = 30;
    private static final int DIGITS = 6;
    private static final int MODULUS = (int) Math.pow(10, DIGITS);
    private static final String HMAC_ALGORITHM = "HmacSHA1";
    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final int[] LOOKUP = buildLookup();

    private TotpTestUtils() {
    }

    public static String generateCurrentCode(String secret) {
        return generateCode(secret, Instant.now());
    }

    public static String generateCode(String secret, Instant instant) {
        byte[] decodedSecret = decode(secret);
        if (decodedSecret.length == 0) {
            throw new IllegalArgumentException("Secret cannot be decoded");
        }
        long counter = instant.getEpochSecond() / TIME_STEP_SECONDS;
        try {
            int otp = generateOtp(decodedSecret, counter);
            return String.format("%0" + DIGITS + "d", otp);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Failed to generate TOTP", e);
        }
    }

    private static byte[] decode(String value) {
        if (value == null || value.isBlank()) {
            return new byte[0];
        }
        String normalized = value.replace("=", "")
                .replace(" ", "")
                .toUpperCase(Locale.ROOT);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int buffer = 0;
        int bitsLeft = 0;
        for (char c : normalized.toCharArray()) {
            if (c >= LOOKUP.length) {
                continue;
            }
            int val = LOOKUP[c];
            if (val < 0) {
                continue;
            }
            buffer = (buffer << 5) | val;
            bitsLeft += 5;
            if (bitsLeft >= 8) {
                out.write((buffer >> (bitsLeft - 8)) & 0xFF);
                bitsLeft -= 8;
            }
        }
        return out.toByteArray();
    }

    private static int generateOtp(byte[] secretKey, long counter) throws GeneralSecurityException {
        byte[] counterBytes = ByteBuffer.allocate(8).putLong(counter).array();
        Mac mac = Mac.getInstance(HMAC_ALGORITHM);
        mac.init(new SecretKeySpec(secretKey, HMAC_ALGORITHM));
        byte[] hash = mac.doFinal(counterBytes);
        int offset = hash[hash.length - 1] & 0x0F;
        int binary = ((hash[offset] & 0x7f) << 24)
                | ((hash[offset + 1] & 0xff) << 16)
                | ((hash[offset + 2] & 0xff) << 8)
                | (hash[offset + 3] & 0xff);
        return binary % MODULUS;
    }

    private static int[] buildLookup() {
        int[] lookup = new int[128];
        for (int i = 0; i < lookup.length; i++) {
            lookup[i] = -1;
        }
        for (int i = 0; i < ALPHABET.length(); i++) {
            lookup[ALPHABET.charAt(i)] = i;
        }
        return lookup;
    }
}

