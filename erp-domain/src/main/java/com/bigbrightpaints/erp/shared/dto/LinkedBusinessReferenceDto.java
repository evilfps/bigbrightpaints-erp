package com.bigbrightpaints.erp.shared.dto;

public record LinkedBusinessReferenceDto(String relationType, String documentType, Long documentId, String documentNumber, DocumentLifecycleDto lifecycle, Long journalEntryId) {}
