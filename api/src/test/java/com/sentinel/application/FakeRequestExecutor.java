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

	/** 요청별 상태 코드로 응답. 판정 실패·통과를 경로마다 바꾸는 검증용 */
	public void respondStatus(Function<ResolvedRequest, Integer> statusCode) {
		behavior = request -> new HttpExchange(request, statusCode.apply(request), Map.of(), "", Duration.ofMillis(12));
	}

	/** 200 응답을 요청별 소요 시간으로 반환. 응답시간 집계 검증용 */
	public void respondIn(Function<ResolvedRequest, Duration> elapsed) {
		behavior = request -> new HttpExchange(request, 200, Map.of(), "", elapsed.apply(request));
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
