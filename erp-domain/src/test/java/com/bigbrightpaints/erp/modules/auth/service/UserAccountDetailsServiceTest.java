package com.bigbrightpaints.erp.modules.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import com.bigbrightpaints.erp.modules.auth.domain.UserAccount;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccountRepository;
import com.bigbrightpaints.erp.modules.auth.domain.UserPrincipal;

@ExtendWith(MockitoExtension.class)
class UserAccountDetailsServiceTest {

  @Mock private UserAccountRepository repository;

  private UserAccountDetailsService service;

  @BeforeEach
  void setUp() {
    service = new UserAccountDetailsService(repository);
  }

  @Test
  void loadUserByUsername_returnsPrincipalForKnownPublicId() {
    UserAccount user = new UserAccount("user@example.com", "MOCK", "hash", "User");
    UUID publicId = user.getPublicId();
    when(repository.findByPublicId(publicId)).thenReturn(Optional.of(user));

    UserPrincipal principal = (UserPrincipal) service.loadUserByUsername(publicId.toString());

    assertThat(principal.getUser()).isSameAs(user);
  }

  @Test
  void loadUserByUsername_rejectsInvalidUuid() {
    UsernameNotFoundException exception =
        assertThrows(
            UsernameNotFoundException.class, () -> service.loadUserByUsername("not-a-uuid"));

    assertThat(exception).hasMessageContaining("User not found: not-a-uuid");
    assertThat(exception.getCause()).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void loadUserByUsername_rejectsUnknownPublicId() {
    UUID publicId = UUID.randomUUID();
    when(repository.findByPublicId(publicId)).thenReturn(Optional.empty());

    UsernameNotFoundException exception =
        assertThrows(
            UsernameNotFoundException.class, () -> service.loadUserByUsername(publicId.toString()));

    assertThat(exception).hasMessageContaining(publicId.toString());
  }
}
