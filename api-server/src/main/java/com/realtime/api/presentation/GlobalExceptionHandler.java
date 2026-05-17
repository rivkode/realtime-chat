package com.realtime.api.presentation;

import com.realtime.api.presentation.dto.ApiErrorResponse;
import com.realtime.common.domain.session.SessionAlreadyEndedException;
import com.realtime.common.domain.session.SessionNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class GlobalExceptionHandler {

	@ExceptionHandler(SessionNotFoundException.class)
	public ResponseEntity<ApiErrorResponse> handleNotFound(SessionNotFoundException ex) {
		return ResponseEntity.status(HttpStatus.NOT_FOUND)
				.body(ApiErrorResponse.of("SESSION_NOT_FOUND", ex.getMessage()));
	}

	@ExceptionHandler(SessionAlreadyEndedException.class)
	public ResponseEntity<ApiErrorResponse> handleEnded(SessionAlreadyEndedException ex) {
		return ResponseEntity.status(HttpStatus.CONFLICT)
				.body(ApiErrorResponse.of("SESSION_ALREADY_ENDED", ex.getMessage()));
	}

	@ExceptionHandler({
			MethodArgumentNotValidException.class,
			HttpMessageNotReadableException.class,
			MethodArgumentTypeMismatchException.class,
			IllegalArgumentException.class
	})
	public ResponseEntity<ApiErrorResponse> handleBadRequest(Exception ex) {
		return ResponseEntity.status(HttpStatus.BAD_REQUEST)
				.body(ApiErrorResponse.of("BAD_REQUEST", ex.getMessage()));
	}
}
