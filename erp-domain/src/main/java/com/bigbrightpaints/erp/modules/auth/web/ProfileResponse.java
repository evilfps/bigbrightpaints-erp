package com.bigbrightpaints.erp.modules.auth.web;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ProfileResponse(
    String email,
    String displayName,
    String preferredName,
    String jobTitle,
    String profilePictureUrl,
    String phoneSecondary,
    String secondaryEmail,
    boolean mfaEnabled,
    String companyCode,
    Instant createdAt,
    UUID publicId) {}
