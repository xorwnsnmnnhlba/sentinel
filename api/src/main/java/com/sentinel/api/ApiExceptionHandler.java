package com.sentinel.api;

import com.sentinel.application.ConflictException;
import com.sentinel.application.auth.ForbiddenException;
import com.sentinel.application.auth.InvalidCredentialsException;
import com.sentinel.application.auth.TooManyLoginAttemptsException;
import com.sentinel.application.auth.UnauthenticatedException;
import com.sentinel.application.InvalidRequestException;
import com.sentinel.application.NotFoundException;
import com.sentinel.application.StaleVersionException;
import com.sentinel.application.TooManyRunsException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
class ApiExceptionHandler {

	/** web이 이 문구로 동시 수정 충돌을 구분 (lib/actions) */
	static final String CONCURRENT_MODIFICATION = "modified by another request; reload and try again";


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

	/** 동시 수동 실행 한도. 진행 중인 실행이 끝나면 다시 시도 */
	@ExceptionHandler(TooManyRunsException.class)
	ProblemDetail handleTooManyRuns(TooManyRunsException ex) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS, ex.getMessage());
	}

	@ExceptionHandler(ConflictException.class)
	ProblemDetail handleConflict(ConflictException ex) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
	}

	/**
	 * 낙관적 잠금 충돌. 같은 항목을 다른 요청이 먼저 고침 (화면은 다시 불러온 뒤 재시도 안내).
	 * 동시에 겹친 요청(저장 시점 확인)과, 수정 화면을 연 뒤 다른 곳에서 고친 경우(요청의 version 확인) 모두 같은 응답
	 */
	@ExceptionHandler({ ObjectOptimisticLockingFailureException.class, StaleVersionException.class })
	ProblemDetail handleOptimisticLock(RuntimeException ex) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, CONCURRENT_MODIFICATION);
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
