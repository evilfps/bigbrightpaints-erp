package com.bigbrightpaints.erp.modules.auth.exception;

/**
 * Signals that the current login attempt needs an MFA verifier before it can be completed.
 */
public class MfaRequiredException extends RuntimeException {

    public MfaRequiredException(String message) {
        super(message);
    }
}
