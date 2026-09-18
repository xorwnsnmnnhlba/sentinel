package com.sentinel.runner;

/**
 * 요청 전송 및 응답 반환.
 * <p>
 * 4xx·5xx·3xx 포함 응답을 받으면 {@link HttpExchange} 반환. 응답 자체를 받지 못한 경우(타임아웃, 연결 실패)에만
 * {@link RequestExecutionException} 발생.
 */
public interface RequestExecutor {

	HttpExchange execute(ResolvedRequest request);

}
