package com.bigbrightpaints.erp.modules.hr.dto;

import java.time.LocalDate;
import java.util.UUID;

public record EmployeeDto(Long id,
                          UUID publicId,
                          String firstName,
                          String lastName,
                          String email,
                          String role,
                          String status,
                          LocalDate hiredDate) {}
