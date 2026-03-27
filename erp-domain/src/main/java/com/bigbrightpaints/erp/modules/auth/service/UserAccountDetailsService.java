package com.bigbrightpaints.erp.modules.auth.service;

import java.util.UUID;

import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import com.bigbrightpaints.erp.modules.auth.domain.UserAccount;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccountRepository;
import com.bigbrightpaints.erp.modules.auth.domain.UserPrincipal;

@Service
public class UserAccountDetailsService implements UserDetailsService {

  private final UserAccountRepository repository;

  public UserAccountDetailsService(UserAccountRepository repository) {
    this.repository = repository;
  }

  @Override
  public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
    UUID publicId;
    try {
      publicId = UUID.fromString(username);
    } catch (IllegalArgumentException ex) {
      throw new UsernameNotFoundException("User not found: " + username, ex);
    }
    UserAccount user =
        repository
            .findByPublicId(publicId)
            .orElseThrow(() -> new UsernameNotFoundException("User not found: " + username));
    return new UserPrincipal(user);
  }
}
