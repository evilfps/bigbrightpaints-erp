package com.bigbrightpaints.erp.modules.auth.service;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.bigbrightpaints.erp.modules.auth.domain.UserAccount;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccountRepository;
import com.bigbrightpaints.erp.modules.auth.web.ProfileResponse;
import com.bigbrightpaints.erp.modules.auth.web.UpdateProfileRequest;

import jakarta.transaction.Transactional;

@Service
public class UserProfileService {

  private final UserAccountRepository userAccountRepository;

  public UserProfileService(UserAccountRepository userAccountRepository) {
    this.userAccountRepository = userAccountRepository;
  }

  public ProfileResponse view(UserAccount user) {
    return toResponse(user);
  }

  @Transactional
  public ProfileResponse update(UserAccount user, UpdateProfileRequest request) {
    if (request.displayName() != null && StringUtils.hasText(request.displayName())) {
      user.setDisplayName(request.displayName().trim());
    }
    if (request.preferredName() != null) {
      user.setPreferredName(request.preferredName().trim());
    }
    if (request.jobTitle() != null) {
      user.setJobTitle(request.jobTitle().trim());
    }
    if (request.profilePictureUrl() != null) {
      user.setProfilePictureUrl(request.profilePictureUrl().trim());
    }
    if (request.phoneSecondary() != null) {
      user.setPhoneSecondary(request.phoneSecondary().trim());
    }
    if (request.secondaryEmail() != null) {
      user.setSecondaryEmail(request.secondaryEmail().trim().toLowerCase());
    }
    UserAccount saved = userAccountRepository.save(user);
    return toResponse(saved);
  }

  private ProfileResponse toResponse(UserAccount user) {
    return new ProfileResponse(
        user.getEmail(),
        user.getDisplayName(),
        user.getPreferredName(),
        user.getJobTitle(),
        user.getProfilePictureUrl(),
        user.getPhoneSecondary(),
        user.getSecondaryEmail(),
        user.isMfaEnabled(),
        resolveCompanyCode(user),
        user.getCreatedAt(),
        user.getPublicId());
  }

  private String resolveCompanyCode(UserAccount user) {
    if (user == null) {
      return null;
    }
    if (StringUtils.hasText(user.getAuthScopeCode())) {
      return user.getAuthScopeCode();
    }
    if (user.getCompany() == null || !StringUtils.hasText(user.getCompany().getCode())) {
      return null;
    }
    return user.getCompany().getCode();
  }
}
