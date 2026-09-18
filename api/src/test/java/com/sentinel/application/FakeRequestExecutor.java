package com.sentinel.application;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import com.sentinel.runner.HttpExchange;
import com.sentinel.runner.RequestExecutionException;
import com.sentinel.runner.RequestExecutor;
import com.sentinel.runner.ResolvedRequest;

/**
 * 네트워크 없이 실행 흐름을 검증하기 위한 가짜 실행기. 받은 요청을 기록하고 지정한 응답·실패를 반환.
 */
public class FakeRequestExecutor implements RequestExecutor {

	private final List<ResolvedRequest> requests = new CopyOnWriteArrayList<>();

	private volatile Function<ResolvedRequest, HttpExchange> behavior;

	public FakeRequestExecutor() {
		reset();
	}

	@Override
	public HttpExchange execute(ResolvedRequest request) {
		requests.add(request);
		return behavior.apply(request);
	}

	public void respond(int statusCode, Map<String, List<String>> headers, String body) {
		behavior = request -> new HttpExchange(request, statusCode, headers, body, Duration.ofMillis(12));
	}

	public void fail(RequestExecutionException.Reason reason) {
		behavior = request -> {
			throw new RequestExecutionException(reason, request, Duration.ofMillis(1000), null);
		};
	}

	public List<ResolvedRequest> requests() {
		return List.copyOf(requests);
	}

	public void reset() {
		requests.clear();
		respond(200, Map.of(), "");
	}

}
