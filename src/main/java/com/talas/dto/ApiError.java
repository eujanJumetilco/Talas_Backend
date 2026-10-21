package com.talas.dto;

/** Body of every error response: { "message": "..." }. */
public record ApiError(String message) {
}
