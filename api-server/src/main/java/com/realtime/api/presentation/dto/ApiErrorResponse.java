package com.realtime.api.presentation.dto;

public record ApiErrorResponse(Body error) {

	public static ApiErrorResponse of(String code, String message) {
		return new ApiErrorResponse(new Body(code, message));
	}

	public record Body(String code, String message) {
	}
}
