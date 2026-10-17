package com.sentinel.runner;

import java.time.Duration;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * 응답을 받지 못한 전송의 재시도. GW·네트워크가 잠깐 흔들린 것만으로 실패가 기록·알림되지 않도록 잠시 기다렸다가 다시 보냄.
 * <ul>
 * <li>연결 실패({@link RequestExecutionException.Reason#CONNECTION_FAILED}): 요청이 서버에 닿지 않았으므로 모든 메서드</li>
 * <li>타임아웃·연결 끊김({@code TIMEOUT}·{@code IO_ERROR}): 서버가 이미 처리했을 수 있어 조회 메서드(GET·HEAD·OPTIONS)만.
 * 등록·삭제 요청이 두 번 처리되지 않도록</li>
 * <li>전송 거부({@code BLOCKED_DESTINATION}): 다시 보내도 같으므로 재시도하지 않음</li>
 * </ul>
 * 응답을 받은 뒤의 판정 실패는 실제 문제일 가능성이 커서 대상이 아님
 */
public final class TransportRetry {

	/** 설정 가능한 최대 재시도 횟수. 실행 시간이 케이스마다 (재시도 수 + 1)배로 늘어날 수 있어 제한 */
	public static final int MAX_RETRIES = 3;

	private static final Set<HttpMethod> SAFE_METHODS = EnumSet.of(HttpMethod.GET, HttpMethod.HEAD, HttpMethod.OPTIONS);

	private final int maxRetries;

	private final Duration delay;

	private final Sleeper sleeper;

	/**
	 * @param maxRetries 0이면 재시도하지 않음
	 * @param delay 다시 보내기 전 대기
	 */
	public TransportRetry(int maxRetries, Duration delay, Sleeper sleeper) {
		if (maxRetries < 0 || maxRetries > MAX_RETRIES) {
			throw new IllegalArgumentException("maxRetries must be between 0 and " + MAX_RETRIES + ": " + maxRetries);
		}
		if (delay.isNegative()) {
			throw new IllegalArgumentException("delay must not be negative: " + delay);
		}
		this.maxRetries = maxRetries;
		this.delay = delay;
		this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
	}

	static boolean retryable(RequestExecutionException.Reason reason, HttpMethod method) {
		return switch (reason) {
			case CONNECTION_FAILED -> true;
			case TIMEOUT, IO_ERROR -> SAFE_METHODS.contains(method);
			// 인증서·TLS 설정 문제는 다시 보내도 같은 결과
			case BLOCKED_DESTINATION, TLS_ERROR -> false;
		};
	}

	/**
	 * 전송하고, 재시도할 수 있는 전송 실패면 대기 후 다시 전송. 대기 중 인터럽트되면 재시도를 멈추고 마지막 실패 반환
	 * (인터럽트 상태 유지). 전송 실패 외의 예외는 그대로 전파
	 */
	public Sent send(ResolvedRequest request, RequestExecutor executor) {
		int retries = 0;
		while (true) {
			try {
				return new Sent(executor.execute(request), null, retries);
			}
			catch (RequestExecutionException ex) {
				if (retries >= maxRetries || !retryable(ex.reason(), request.method())) {
					return new Sent(null, ex, retries);
				}
				try {
					sleeper.sleep(delay);
				}
				catch (InterruptedException interrupted) {
					Thread.currentThread().interrupt();
					return new Sent(null, ex, retries);
				}
				retries++;
			}
		}
	}

	/**
	 * 전송 결과. {@code exchange}와 {@code failure} 중 하나만 있음
	 * @param retries 다시 보낸 횟수
	 */
	public record Sent(HttpExchange exchange, RequestExecutionException failure, int retries) {
	}

	@FunctionalInterface
	public interface Sleeper {

		void sleep(Duration duration) throws InterruptedException;

	}

}
