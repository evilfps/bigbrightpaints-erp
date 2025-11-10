package com.bigbrightpaints.erp.modules.admin.dto;

import java.util.List;
import java.util.UUID;

public record UserDto(Long id,
                      UUID publicId,
                      String email,
                      String displayName,
                      boolean enabled,
                      boolean mfaEnabled,
                      List<String> roles,
                      List<String> companies) {}
