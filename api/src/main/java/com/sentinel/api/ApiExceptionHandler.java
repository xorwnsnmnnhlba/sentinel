package com.sentinel.api;

import com.sentinel.application.ConflictException;
import com.sentinel.application.auth.ForbiddenException;
import com.sentinel.application.auth.InvalidCredentialsException;
import com.sentinel.application.auth.TooManyLoginAttemptsException;
import com.sentinel.application.auth.UnauthenticatedException;
import com.sentinel.application.InvalidRequestException;
import com.sentinel.application.NotFoundException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
class ApiExceptionHandler {

	@ExceptionHandler(NotFoundException.class)
	ProblemDetail handleNotFound(NotFoundException ex) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
	}

	/** 로그인 실패. 계정 존재 여부를 알리지 않도록 사유를 구분하지 않음 */
	@ExceptionHandler({ InvalidCredentialsException.class, UnauthenticatedException.class })
	ProblemDetail handleUnauthenticated(RuntimeException ex) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, ex.getMessage());
	}

	/** 반복된 로그인 실패. 다시 시도할 수 있는 시점을 Retry-After(초)로 안내 */
	@ExceptionHandler(TooManyLoginAttemptsException.class)
	ResponseEntity<ProblemDetail> handleTooManyLoginAttempts(TooManyLoginAttemptsException ex) {
		long seconds = Math.max(1, (ex.retryAfter().toMillis() + 999) / 1000);
		return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
			.header(HttpHeaders.RETRY_AFTER, String.valueOf(seconds))
			.body(ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS, ex.getMessage()));
	}

	@ExceptionHandler(ForbiddenException.class)
	ProblemDetail handleForbidden(ForbiddenException ex) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, ex.getMessage());
	}

	@ExceptionHandler(ConflictException.class)
	ProblemDetail handleConflict(ConflictException ex) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
	}

	/**
	 * 외래키가 없으므로 유니크 제약 위반. 이름 중복 사전 검사를 통과한 동시 등록 경합
	 */
	@ExceptionHandler(DataIntegrityViolationException.class)
	ProblemDetail handleDataIntegrityViolation(DataIntegrityViolationException ex) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "conflicts with an existing item");
	}

	@ExceptionHandler(InvalidRequestException.class)
	ProblemDetail handleInvalidRequest(InvalidRequestException ex) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
	}

}
