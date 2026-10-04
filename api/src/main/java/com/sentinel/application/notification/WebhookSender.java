package com.sentinel.application.notification;

import java.net.URI;
import java.util.List;
import java.util.Map;

import com.sentinel.runner.HttpExchange;
import com.sentinel.runner.HttpMethod;
import com.sentinel.runner.RequestExecutionException;
import com.sentinel.runner.RequestExecutor;
import com.sentinel.runner.ResolvedRequest;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * 프로젝트 Webhook 발송. 본문은 서버 기본 채널({@link WebhookNotifier})과 같은 {@code {"text": "..."}}.
 * <p>
 * 로그인한 사용자 누구나 지정하는 주소이므로 실행기와 같은 목적지 확인(링크 로컬·클라우드 메타데이터 주소 차단)을 거치고
 * 리다이렉트를 따라가지 않음. 주소와 주소가 담긴 예외 메시지는 로그·응답에 남기지 않음 (자격증명)
 */
public class WebhookSender implements AutoCloseable {

	private static final ObjectMapper JSON = JsonMapper.builder().build();

	private final RequestExecutor executor;

	WebhookSender(RequestExecutor executor) {
		this.executor = executor;
	}

	/** 발송. 실패는 예외 대신 결과로 반환 (알림 때문에 실행이 멈추지 않도록) */
	Delivery deliver(String url, Notification notification) {
		ResolvedRequest request;
		try {
			request = new ResolvedRequest(HttpMethod.POST, URI.create(url),
					Map.of("Content-Type", List.of("application/json")),
					JSON.writeValueAsString(Map.of("text", notification.text())));
		}
		catch (IllegalArgumentException ex) {
			return new Delivery(false, null, "INVALID_URL");
		}
		try {
			HttpExchange exchange = executor.execute(request);
			int status = exchange.statusCode();
			return new Delivery(status >= 200 && status < 300, status, null);
		}
		catch (RequestExecutionException ex) {
			return new Delivery(false, null, ex.reason().name());
		}
	}

	@Override
	public void close() throws Exception {
		if (executor instanceof AutoCloseable closeable) {
			closeable.close();
		}
	}

	/**
	 * @param delivered 2xx 응답을 받음
	 * @param statusCode 받은 응답의 상태 코드. 응답이 없으면 null
	 * @param error 응답을 받지 못한 사유 (예: {@code CONNECTION_FAILED}, {@code BLOCKED_DESTINATION}). 응답을 받았으면 null
	 */
	public record Delivery(boolean delivered, Integer statusCode, String error) {

		/** 로그용 사유. 주소 없음 */
		String describe() {
			return statusCode != null ? "HTTP " + statusCode : error;
		}

	}

}
