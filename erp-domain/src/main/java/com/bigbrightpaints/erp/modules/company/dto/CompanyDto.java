package com.bigbrightpaints.erp.modules.company.dto;

import java.util.UUID;

public record CompanyDto(Long id,
                         UUID publicId,
                         String name,
                         String code,
                         String timezone) {}
