package com.sentinel.runner;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.sentinel.runner.RequestExecutionException.Reason;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TransportRetryTest {

	private final List<Duration> waits = new ArrayList<>();

	private final TransportRetry retry = new TransportRetry(1, Duration.ofSeconds(1), waits::add);

	@AfterEach
	void clearInterrupt() {
		Thread.interrupted();
	}

	@ParameterizedTest
	@CsvSource({ "CONNECTION_FAILED, POST, true", "CONNECTION_FAILED, GET, true", "TIMEOUT, GET, true",
			"TIMEOUT, HEAD, true", "IO_ERROR, OPTIONS, true", "TIMEOUT, POST, false", "TIMEOUT, PUT, false",
			"IO_ERROR, PATCH, false", "IO_ERROR, DELETE, false", "BLOCKED_DESTINATION, GET, false" })
	void retriesWhenTheRequestSurelyDidNotReachOrIsSafeToRepeat(Reason reason, HttpMethod method,
			boolean retryable) {
		assertThat(TransportRetry.retryable(reason, method)).isEqualTo(retryable);
	}

	@Test
	void sendsAgainAfterWaitingAndReturnsTheResponse() {
		ResolvedRequest request = request(HttpMethod.POST);
		Script executor = new Script(request, Reason.CONNECTION_FAILED);

		TransportRetry.Sent sent = retry.send(request, executor);

		assertThat(sent.exchange().statusCode()).isEqualTo(200);
		assertThat(sent.failure()).isNull();
		assertThat(sent.retries()).isEqualTo(1);
		assertThat(executor.calls).isEqualTo(2);
		assertThat(waits).containsExactly(Duration.ofSeconds(1));
	}

	@Test
	void givesUpAfterTheMaximumAndReturnsTheLastFailure() {
		ResolvedRequest request = request(HttpMethod.GET);
		TransportRetry twice = new TransportRetry(2, Duration.ZERO, waits::add);
		Script executor = new Script(request, Reason.TIMEOUT, Reason.TIMEOUT, Reason.IO_ERROR, Reason.TIMEOUT);

		TransportRetry.Sent sent = twice.send(request, executor);

		assertThat(sent.exchange()).isNull();
		assertThat(sent.failure().reason()).isEqualTo(Reason.IO_ERROR);
		assertThat(sent.retries()).isEqualTo(2);
		assertThat(executor.calls).isEqualTo(3);
	}

	@Test
	void doesNotRepeatUnsafeRequestsOrWhenDisabled() {
		ResolvedRequest post = request(HttpMethod.POST);
		Script timedOut = new Script(post, Reason.TIMEOUT);
		assertThat(retry.send(post, timedOut).retries()).isZero();
		assertThat(timedOut.calls).isEqualTo(1);

		ResolvedRequest get = request(HttpMethod.GET);
		Script failing = new Script(get, Reason.CONNECTION_FAILED);
		TransportRetry.Sent disabled = new TransportRetry(0, Duration.ZERO, waits::add).send(get, failing);
		assertThat(disabled.failure().reason()).isEqualTo(Reason.CONNECTION_FAILED);
		assertThat(failing.calls).isEqualTo(1);
		assertThat(waits).isEmpty();
	}

	@Test
	void stopsRetryingWhenInterruptedWhileWaiting() {
		ResolvedRequest request = request(HttpMethod.GET);
		Script executor = new Script(request, Reason.TIMEOUT);
		TransportRetry interrupted = new TransportRetry(1, Duration.ofSeconds(1), duration -> {
			throw new InterruptedException();
		});

		TransportRetry.Sent sent = interrupted.send(request, executor);

		assertThat(sent.failure().reason()).isEqualTo(Reason.TIMEOUT);
		assertThat(sent.retries()).isZero();
		assertThat(executor.calls).isEqualTo(1);
		assertThat(Thread.currentThread().isInterrupted()).isTrue();
	}

	@Test
	void passesOtherErrorsThrough() {
		ResolvedRequest request = request(HttpMethod.GET);

		assertThatThrownBy(() -> retry.send(request, sent -> {
			throw new IllegalArgumentException("restricted header");
		})).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void limitsTheSettings() {
		assertThatThrownBy(() -> new TransportRetry(4, Duration.ZERO, waits::add))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new TransportRetry(-1, Duration.ZERO, waits::add))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new TransportRetry(1, Duration.ofMillis(-1), waits::add))
			.isInstanceOf(IllegalArgumentException.class);
		assertThat(new TransportRetry(3, Duration.ZERO, waits::add)).isNotNull();
	}

	private static ResolvedRequest request(HttpMethod method) {
		return new ResolvedRequest(method, URI.create("https://gw/orders"), Map.of(), null);
	}

	/** 지정한 전송 실패를 차례로 던지고 그 뒤로는 200 */
	private static final class Script implements RequestExecutor {

		private final ResolvedRequest request;

		private final List<Reason> failures;

		private int calls;

		Script(ResolvedRequest request, Reason... failures) {
			this.request = request;
			this.failures = List.of(failures);
		}

		@Override
		public HttpExchange execute(ResolvedRequest sent) {
			calls++;
			if (calls <= failures.size()) {
				throw new RequestExecutionException(failures.get(calls - 1), request, Duration.ofMillis(5), null);
			}
			return new HttpExchange(sent, 200, Map.of(), "", Duration.ofMillis(5));
		}

	}

}
