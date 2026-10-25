package com.sentinel.api;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.sentinel.application.ConflictException;
import com.sentinel.application.auth.ForbiddenException;
import com.sentinel.application.auth.InvalidCredentialsException;
import com.sentinel.application.auth.TooManyLoginAttemptsException;
import com.sentinel.application.auth.UnauthenticatedException;
import com.sentinel.application.InvalidRequestException;
import com.sentinel.application.NotFoundException;
import com.sentinel.application.StaleVersionException;
import com.sentinel.application.TooManyRunsException;
import com.sentinel.runner.InvalidValueException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DatabindException;

/**
 * Spring Boot의 ProblemDetail 처리(spring.mvc.problemdetails, 순서 0)보다 먼저. 읽을 수 없는 요청 본문·요청 항목 검증 실패를 이쪽에서 처리하기 위함
 * (그 밖의 Spring 오류는 여기서 다루지 않으므로 그대로 Spring 처리)
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
class ApiExceptionHandler {

	/** 동시 수정 충돌 문구. 화면은 code CONCURRENT_MODIFICATION으로 구분 (lib/actions) */
	static final String CONCURRENT_MODIFICATION = "modified by another request; reload and try again";

	/** 요청 항목 검증 실패 시 Spring 기본 문구 */
	private static final String INVALID_CONTENT = "Invalid request content.";


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
		return problem(HttpStatus.CONFLICT, ex.getMessage(), ex.code() == null ? null : ex.code().name(), ex.params());
	}

	/**
	 * 낙관적 잠금 충돌. 같은 항목을 다른 요청이 먼저 고침 (화면은 다시 불러온 뒤 재시도 안내).
	 * 동시에 겹친 요청(저장 시점 확인)과, 수정 화면을 연 뒤 다른 곳에서 고친 경우(요청의 version 확인) 모두 같은 응답
	 */
	@ExceptionHandler({ ObjectOptimisticLockingFailureException.class, StaleVersionException.class })
	ProblemDetail handleOptimisticLock(RuntimeException ex) {
		return problem(HttpStatus.CONFLICT, CONCURRENT_MODIFICATION, "CONCURRENT_MODIFICATION", Map.of());
	}

	/**
	 * 외래키가 없으므로 유니크 제약 위반. 이름 중복 사전 검사를 통과한 동시 등록 경합
	 */
	@ExceptionHandler(DataIntegrityViolationException.class)
	ProblemDetail handleDataIntegrityViolation(DataIntegrityViolationException ex) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "conflicts with an existing item");
	}

	/** 종류가 붙은 오류는 화면이 한국어 문장을 만들 code·params를 함께 (web/lib/input-error.ts, web/lib/import-message.ts) */
	@ExceptionHandler(InvalidRequestException.class)
	ProblemDetail handleInvalidRequest(InvalidRequestException ex) {
		return badRequest(ex.getMessage(), ex.code(), ex.params());
	}

	/**
	 * 읽을 수 없는 요청 본문. 판정 규칙·값 추출은 JSON을 읽으면서 검증하므로, 종류가 붙은 검증 오류({@link InvalidValueException})면
	 * 그 메시지·code와 위치(예: assertions의 1번째 → list {@code assertions}, index {@code 0})를 알림.
	 * 그 밖의 오류(JSON 문법 등)는 Spring 기본 응답과 같은 문구
	 */
	@ExceptionHandler(HttpMessageNotReadableException.class)
	ProblemDetail handleNotReadable(HttpMessageNotReadableException ex) {
		InvalidValueException invalid = cause(ex, InvalidValueException.class);
		if (invalid == null) {
			return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Failed to read request");
		}
		Map<String, String> params = new HashMap<>(invalid.params());
		DatabindException databind = cause(ex, DatabindException.class);
		List<JacksonException.Reference> path = databind == null ? List.of() : databind.getPath();
		if (path.size() >= 2 && path.get(0).getPropertyName() != null && path.get(1).getIndex() >= 0) {
			params.put("list", path.get(0).getPropertyName());
			params.put("index", String.valueOf(path.get(1).getIndex()));
		}
		return badRequest(invalid.getMessage(), invalid.code().name(), params);
	}

	/**
	 * 요청 항목 검증(@NotBlank 등) 실패. 첫 항목의 빈 값·이메일 형식은 도메인 검증과 같은 code로 (화면 문장 공유).
	 * 그 밖은 Spring 기본 응답과 같은 문구만
	 */
	@ExceptionHandler(MethodArgumentNotValidException.class)
	ProblemDetail handleInvalidArgument(MethodArgumentNotValidException ex) {
		FieldError error = ex.getBindingResult().getFieldError();
		String code = error == null ? null : String.valueOf(error.getCode());
		return switch (code) {
			case "NotBlank", "NotNull", "NotEmpty" ->
				badRequest(INVALID_CONTENT, InvalidValueException.Code.REQUIRED.name(), Map.of("target", error.getField()));
			case "Email" -> badRequest(INVALID_CONTENT, InvalidValueException.Code.EMAIL_INVALID.name(),
					Map.of("email", String.valueOf(error.getRejectedValue())));
			case null, default -> ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, INVALID_CONTENT);
		};
	}

	private static ProblemDetail badRequest(String detail, String code, Map<String, String> params) {
		return problem(HttpStatus.BAD_REQUEST, detail, code, params);
	}

	/** 종류(code)가 있으면 화면이 문장·처리를 고를 수 있도록 code·params를 함께 */
	private static ProblemDetail problem(HttpStatus status, String detail, String code, Map<String, String> params) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
		if (code != null) {
			problem.setProperty("code", code);
			problem.setProperty("params", params);
		}
		return problem;
	}

	private static <T extends Throwable> T cause(Throwable error, Class<T> type) {
		for (Throwable current = error; current != null; current = current.getCause()) {
			if (type.isInstance(current)) {
				return type.cast(current);
			}
		}
		return null;
	}

}
