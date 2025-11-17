package com.bigbrightpaints.erp.core.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.util.Base64;

/**
 * Service for encrypting and decrypting sensitive data at rest.
 * Uses AES-256-GCM for authenticated encryption.
 */
@Service
public class CryptoService {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_TAG_LENGTH = 128;
    private static final int GCM_IV_LENGTH = 12;
    private static final int SALT_LENGTH = 16;
    private static final int ITERATION_COUNT = 65536;
    private static final int KEY_LENGTH = 256;

    @Value("${erp.security.encryption.key:#{null}}")
    private String encryptionKey;

    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * Encrypts a plaintext string using AES-256-GCM.
     *
     * @param plaintext The text to encrypt
     * @return Base64 encoded encrypted data with IV and salt prepended
     */
    public String encrypt(String plaintext) {
        if (plaintext == null) {
            return null;
        }

        try {
            // Generate random salt and IV
            byte[] salt = new byte[SALT_LENGTH];
            byte[] iv = new byte[GCM_IV_LENGTH];
            secureRandom.nextBytes(salt);
            secureRandom.nextBytes(iv);

            // Derive key from password and salt
            SecretKey key = deriveKey(getEncryptionKey(), salt);

            // Initialize cipher
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            GCMParameterSpec parameterSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.ENCRYPT_MODE, key, parameterSpec);

            // Encrypt
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes("UTF-8"));

            // Combine salt + iv + ciphertext
            byte[] combined = new byte[SALT_LENGTH + GCM_IV_LENGTH + ciphertext.length];
            System.arraycopy(salt, 0, combined, 0, SALT_LENGTH);
            System.arraycopy(iv, 0, combined, SALT_LENGTH, GCM_IV_LENGTH);
            System.arraycopy(ciphertext, 0, combined, SALT_LENGTH + GCM_IV_LENGTH, ciphertext.length);

            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            throw new RuntimeException("Failed to encrypt data", e);
        }
    }

    /**
     * Decrypts a Base64 encoded encrypted string.
     *
     * @param encryptedData Base64 encoded encrypted data
     * @return The decrypted plaintext
     */
    public String decrypt(String encryptedData) {
        if (encryptedData == null) {
            return null;
        }

        try {
            byte[] combined = Base64.getDecoder().decode(encryptedData);

            // Extract salt, IV, and ciphertext
            byte[] salt = new byte[SALT_LENGTH];
            byte[] iv = new byte[GCM_IV_LENGTH];
            byte[] ciphertext = new byte[combined.length - SALT_LENGTH - GCM_IV_LENGTH];

            System.arraycopy(combined, 0, salt, 0, SALT_LENGTH);
            System.arraycopy(combined, SALT_LENGTH, iv, 0, GCM_IV_LENGTH);
            System.arraycopy(combined, SALT_LENGTH + GCM_IV_LENGTH, ciphertext, 0, ciphertext.length);

            // Derive key from password and salt
            SecretKey key = deriveKey(getEncryptionKey(), salt);

            // Initialize cipher
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            GCMParameterSpec parameterSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.DECRYPT_MODE, key, parameterSpec);

            // Decrypt
            byte[] plaintext = cipher.doFinal(ciphertext);
            return new String(plaintext, "UTF-8");
        } catch (Exception e) {
            throw new RuntimeException("Failed to decrypt data", e);
        }
    }

    /**
     * Derives an AES key from a password and salt using PBKDF2.
     */
    private SecretKey deriveKey(String password, byte[] salt) throws Exception {
        SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        KeySpec spec = new PBEKeySpec(password.toCharArray(), salt, ITERATION_COUNT, KEY_LENGTH);
        SecretKey tmp = factory.generateSecret(spec);
        return new SecretKeySpec(tmp.getEncoded(), "AES");
    }

    /**
     * Gets the encryption key, generating a default one if not configured.
     * In production, this should be provided via environment variable or key management service.
     */
    private String getEncryptionKey() {
        if (encryptionKey == null || encryptionKey.isEmpty()) {
            throw new IllegalStateException("Encryption key (erp.security.encryption.key) must be configured");
        }
        return encryptionKey;
    }

    /**
     * Checks if a string is encrypted (Base64 encoded and has correct format).
     */
    public boolean isEncrypted(String data) {
        if (data == null || data.isEmpty()) {
            return false;
        }

        try {
            byte[] decoded = Base64.getDecoder().decode(data);
            // Check if length is at least salt + iv + 1 byte of ciphertext
            return decoded.length > SALT_LENGTH + GCM_IV_LENGTH;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
