package com.bigbrightpaints.erp.modules.auth.service;

import com.bigbrightpaints.erp.core.security.CryptoService;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccount;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccountRepository;
import com.bigbrightpaints.erp.modules.auth.exception.MfaRequiredException;
import com.bigbrightpaints.erp.test.support.TotpTestUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class MfaServiceTest {

    private UserAccountRepository repository;
    private PasswordEncoder passwordEncoder;
    private Clock clock;
    private CryptoService cryptoService;
    private MfaService mfaService;

    @BeforeEach
    void setUp() {
        repository = mock(UserAccountRepository.class);
        passwordEncoder = mock(PasswordEncoder.class);
        cryptoService = mock(CryptoService.class);
        clock = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC);
        when(cryptoService.encrypt(anyString())).thenAnswer(invocation -> invocation.getArgument(0));
        when(cryptoService.decrypt(anyString())).thenAnswer(invocation -> invocation.getArgument(0));
        mfaService = new MfaService(repository, passwordEncoder, cryptoService, "BigBright ERP", clock);
    }

    @Test
    void verifyDuringLoginSkipsWhenMfaDisabled() {
        UserAccount user = new UserAccount("user@bbp.dev", "hash", "User");
        user.setMfaEnabled(false);

        assertDoesNotThrow(() -> mfaService.verifyDuringLogin(user, null, null));
        verifyNoInteractions(repository);
    }

    @Test
    void verifyDuringLoginRequiresCodeWhenEnabled() {
        UserAccount user = userWithSecret();
        assertThrows(MfaRequiredException.class, () -> mfaService.verifyDuringLogin(user, null, null));
    }

    @Test
    void verifyDuringLoginAcceptsValidTotp() {
        UserAccount user = userWithSecret();
        String code = TotpTestUtils.generateCode(user.getMfaSecret(), clock.instant());

        assertDoesNotThrow(() -> mfaService.verifyDuringLogin(user, code, null));
        verifyNoInteractions(repository);
    }

    private UserAccount userWithSecret() {
        UserAccount user = new UserAccount("user@bbp.dev", "hash", "User");
        user.setMfaSecret("JBSWY3DPEHPK3PXP");
        user.setMfaEnabled(true);
        return user;
    }

}
