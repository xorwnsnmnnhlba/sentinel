package com.sentinel.domain;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.sentinel.runner.HttpExchange;
import com.sentinel.runner.HttpMethod;
import com.sentinel.runner.ResolvedRequest;
import com.sentinel.runner.assertion.AssertionResult;
import com.sentinel.runner.schema.ResponseSchema;
import jakarta.persistence.Column;
import jakarta.persistence.ConstraintMode;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 케이스 1건의 실행 결과. 응답 수신 여부({@link Outcome}), 판정({@link Verdict}), 규칙별 결과, 요청·응답 원문 기록.
 * <p>
 * 요청·응답 헤더의 자격증명은 마스킹, 응답 본문은 {@link #MAX_BODY_BYTES} 초과 시 잘라서 저장.
 */
@Entity
@Table(name = "test_result", indexes = { @Index(name = "idx_result_run", columnList = "run_id"),
		@Index(name = "idx_result_case_time", columnList = "test_case_id, id DESC") })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TestResult extends BaseEntity {

	public static final int MAX_BODY_BYTES = 64 * 1024;

	public enum Outcome {
		/** 상태 코드와 무관하게 응답 수신 */
		RESPONDED,
		/** 응답 미수신 또는 전송 전 실패 */
		ERROR
	}

	public enum Verdict {
		/** 모든 판정 규칙 통과 */
		PASSED,
		/** 응답은 받았으나 판정 규칙 실패 */
		FAILED,
		/** 응답 미수신 또는 전송 전 실패 */
		ERROR
	}

	public enum ErrorReason {
		UNRESOLVED_VARIABLE, INVALID_REQUEST, TIMEOUT, CONNECTION_FAILED, IO_ERROR,
		/** 인증서·TLS 연결 수립 실패 */
		TLS_ERROR,
		/** 링크 로컬·클라우드 메타데이터 주소로의 전송 거부 */
		BLOCKED_DESTINATION, UNEXPECTED
	}

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "run_id", nullable = false, foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT))
	private TestRun run;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "test_case_id", nullable = false, foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT))
	private TestCase testCase;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private Outcome outcome;

	/** 판정 도입 전 기록과의 호환을 위해 null 허용 */
	@Enumerated(EnumType.STRING)
	@Column(length = 10)
	private Verdict verdict;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(columnDefinition = "json")
	private List<AssertionResult> assertionResults;

	@Enumerated(EnumType.STRING)
	@Column(length = 30)
	private ErrorReason errorReason;

	@Column(length = 1000)
	private String errorMessage;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 10)
	private HttpMethod requestMethod;

	/** 치환 완료 URL. 전송 전 실패 시 null */
	@Column(length = 2000)
	private String requestUrl;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(columnDefinition = "json")
	private Map<String, List<String>> requestHeaders;

	/** 변수 치환으로 길어질 수 있으므로 컬럼 길이 초과분은 잘라서 저장 */
	@Column(length = RequestSpec.MAX_BODY_LENGTH)
	private String requestBody;

	private Integer statusCode;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(columnDefinition = "json")
	private Map<String, List<String>> responseHeaders;

	/** {@link #MAX_BODY_BYTES}(UTF-8) 이하로 잘라 저장하므로 문자 수는 컬럼 길이 이내 */
	@Column(length = RequestSpec.MAX_BODY_LENGTH)
	private String responseBody;

	@Column(nullable = false)
	private boolean responseBodyTruncated;

	/**
	 * 응답 JSON의 구조 지문. 잘라서 저장하는 본문과 달리 전체 본문으로 계산하며 보관기간이 지나도 유지.
	 * JSON이 아닌 응답과 응답 없는 결과는 null
	 */
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(columnDefinition = "json")
	private ResponseSchema responseSchema;

	/**
	 * 구조 변화 승인 대기 (V31). 기록할 때 호환이 깨지는 변화·null 전환이 있어 지문을 기준선에 넣지 않음.
	 * 승인 대기 결과만 담는 부분 인덱스 {@code idx_result_schema_pending}(V33)는 {@code @Index}로 표현할 수 없어 마이그레이션에만 둠
	 */
	@Column(nullable = false)
	private boolean schemaPending;

	/** 승인 대기 지문을 기준선에 반영한 사용자 (V32). 승인한 결과만 값이 있음 */
	private Long schemaApprovedBy;

	/** 승인 대기 지문을 기준선에 반영한 시각 (V32) */
	private Instant schemaApprovedAt;

	private Long elapsedMs;

	/** 응답을 받지 못해 다시 보낸 횟수 (V20). 재시도 뒤의 응답·마지막 오류를 기록 */
	@Column(nullable = false)
	private int retryCount;

	private TestResult(TestRun run, TestCase testCase, Outcome outcome, HttpMethod requestMethod) {
		this.run = Objects.requireNonNull(run, "run");
		this.testCase = Objects.requireNonNull(testCase, "testCase");
		this.outcome = outcome;
		this.requestMethod = Objects.requireNonNull(requestMethod, "requestMethod");
	}

	public static TestResult responded(TestRun run, TestCase testCase, HttpExchange exchange,
			List<AssertionResult> assertionResults) {
		return responded(run, testCase, exchange, assertionResults, Set.of());
	}

	/**
	 * @param secretValues 이력에서 가릴 시크릿 값
	 */
	public static TestResult responded(TestRun run, TestCase testCase, HttpExchange exchange,
			List<AssertionResult> assertionResults, Set<String> secretValues) {
		TestResult result = new TestResult(run, testCase, Outcome.RESPONDED, exchange.request().method());
		// 판정 메시지의 실제 값(예: was "...")에 시크릿·가림 지정 추출 값이 나올 수 있으므로 함께 가림
		result.assertionResults = assertionResults.stream()
			.map(assertion -> new AssertionResult(assertion.description(), assertion.passed(),
					SecretValues.mask(assertion.message(), secretValues)))
			.toList();
		result.verdict = assertionResults.stream().allMatch(AssertionResult::passed) ? Verdict.PASSED : Verdict.FAILED;
		result.recordRequest(exchange.request(), secretValues);
		result.statusCode = exchange.statusCode();
		result.responseHeaders = SecretValues.mask(SensitiveHeaders.mask(exchange.headers()), secretValues);
		String body = SecretValues.mask(exchange.body(), secretValues);
		result.responseSchema = ResponseSchema.of(contentType(exchange), body).orElse(null);
		result.responseBody = truncateUtf8(body, MAX_BODY_BYTES);
		result.responseBodyTruncated = result.responseBody.length() < body.length();
		result.elapsedMs = exchange.elapsed().toMillis();
		return result;
	}

	/**
	 * @param sent 전송 시도한 요청. 전송 전 실패 시 null
	 * @param elapsed 전송 후 실패까지 소요 시간. 전송 전 실패 시 null
	 */
	public static TestResult failed(TestRun run, TestCase testCase, HttpMethod method, ErrorReason reason,
			String message, ResolvedRequest sent, Duration elapsed) {
		return failed(run, testCase, method, reason, message, sent, elapsed, Set.of());
	}

	/**
	 * @param secretValues 이력에서 가릴 시크릿 값
	 */
	public static TestResult failed(TestRun run, TestCase testCase, HttpMethod method, ErrorReason reason,
			String message, ResolvedRequest sent, Duration elapsed, Set<String> secretValues) {
		TestResult result = new TestResult(run, testCase, Outcome.ERROR, method);
		result.verdict = Verdict.ERROR;
		result.assertionResults = List.of();
		result.errorReason = Objects.requireNonNull(reason, "reason");
		result.errorMessage = message == null ? null : truncateChars(SecretValues.mask(message, secretValues), 1000);
		if (sent != null) {
			result.recordRequest(sent, secretValues);
		}
		result.elapsedMs = elapsed == null ? null : elapsed.toMillis();
		return result;
	}

	private void recordRequest(ResolvedRequest request, Set<String> secretValues) {
		this.requestUrl = truncateChars(SecretValues.mask(request.uri().toString(), secretValues), 2000);
		this.requestHeaders = SecretValues.mask(SensitiveHeaders.mask(request.headers()), secretValues);
		this.requestBody = request.body() == null ? null
				: truncateChars(SecretValues.mask(request.body(), secretValues), RequestSpec.MAX_BODY_LENGTH);
	}

	private static String contentType(HttpExchange exchange) {
		List<String> values = exchange.headers().get("Content-Type");
		return values == null || values.isEmpty() ? null : values.getFirst();
	}

	private static String truncateChars(String value, int maxLength) {
		return value.length() <= maxLength ? value : value.substring(0, maxLength);
	}

	/**
	 * UTF-8 기준 {@code maxBytes} 이하로 자르기. 경계에 걸린 멀티바이트 문자는 제외.
	 */
	static String truncateUtf8(String value, int maxBytes) {
		byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
		if (bytes.length <= maxBytes) {
			return value;
		}
		CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
				.onMalformedInput(CodingErrorAction.IGNORE)
				.onUnmappableCharacter(CodingErrorAction.IGNORE);
		try {
			CharBuffer decoded = decoder.decode(ByteBuffer.wrap(bytes, 0, maxBytes));
			return decoded.toString();
		}
		catch (CharacterCodingException ex) {
			throw new IllegalStateException(ex);
		}
	}

	public List<AssertionResult> getAssertionResults() {
		return assertionResults == null ? List.of() : List.copyOf(assertionResults);
	}

	/**
	 * 다시 보낸 횟수 기록. 저장 전에 호출
	 * @throws IllegalArgumentException 음수
	 */
	public void recordRetries(int retries) {
		if (retries < 0) {
			throw new IllegalArgumentException("retries must not be negative: " + retries);
		}
		this.retryCount = retries;
	}

	/** 지문을 기준선에 넣지 않고 승인 대기로 둠 */
	public void holdSchema() {
		if (responseSchema == null) {
			throw new IllegalStateException("result has no response schema");
		}
		this.schemaPending = true;
	}

}
