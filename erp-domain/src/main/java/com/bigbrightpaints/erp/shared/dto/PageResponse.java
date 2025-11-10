package com.bigbrightpaints.erp.shared.dto;

import java.util.List;

public record PageResponse<T>(
        List<T> content,
        long totalElements,
        int totalPages,
        int page,
        int size
) {
    public static <T> PageResponse<T> of(List<T> content, long totalElements, int page, int size) {
        int totalPages = (int) Math.ceil(totalElements / (double) size);
        return new PageResponse<>(content, totalElements, totalPages, page, size);
    }
}
