package com.bigbrightpaints.erp.modules.auth.exception;

/**
 * Signals that the supplied MFA verifier (TOTP or recovery code) was invalid.
 */
public class InvalidMfaException extends RuntimeException {

    public InvalidMfaException(String message) {
        super(message);
    }
}
