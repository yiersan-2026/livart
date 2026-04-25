package com.artisanlab.common;

public record ApiResponse<T>(
        boolean success,
        T data,
        ApiError error
) {
    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, data, null);
    }

    public static <T> ApiResponse<T> fail(String message, String code) {
        return new ApiResponse<>(false, null, new ApiError(message, code));
    }

    public record ApiError(String message, String code) {
    }
}
