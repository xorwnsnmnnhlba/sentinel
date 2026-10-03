package com.sentinel.infra.http;

import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;

import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * 로그용 HTTP 실패 사유. RestClient 예외 메시지에는 요청 주소가 들어 있어(예: "I/O error on POST request for \"https://...\"")
 * 자격증명인 주소(Webhook·생존 신호)가 로그에 남으므로, 상태 코드나 실패 종류만 남김
 */
public final class HttpFailures {

	private HttpFailures() {
	}

	/** 예: HTTP 404, timeout, connection failed */
	public static String describe(RestClientException ex) {
		if (ex instanceof RestClientResponseException response) {
			return "HTTP " + response.getStatusCode().value();
		}
		if (ex instanceof ResourceAccessException) {
			Throwable cause = ex.getCause();
			return cause instanceof HttpTimeoutException || cause instanceof SocketTimeoutException ? "timeout"
					: "connection failed";
		}
		return ex.getClass().getSimpleName();
	}

}
