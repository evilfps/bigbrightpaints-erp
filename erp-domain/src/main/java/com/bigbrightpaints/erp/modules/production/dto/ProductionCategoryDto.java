package com.bigbrightpaints.erp.modules.production.dto;

import java.util.UUID;

public record ProductionCategoryDto(Long id,
                                    UUID publicId,
                                    String code,
                                    String name,
                                    String description) {}
