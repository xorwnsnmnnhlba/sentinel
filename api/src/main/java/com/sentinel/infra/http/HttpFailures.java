package com.sentinel.infra.http;

import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;

import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLPeerUnverifiedException;

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

	/** 예: HTTP 404, timeout, connection failed, TLS error */
	public static String describe(RestClientException ex) {
		if (ex instanceof RestClientResponseException response) {
			return "HTTP " + response.getStatusCode().value();
		}
		if (ex instanceof ResourceAccessException) {
			if (isTls(ex)) {
				return "TLS error";
			}
			Throwable cause = ex.getCause();
			return cause instanceof HttpTimeoutException || cause instanceof SocketTimeoutException ? "timeout"
					: "connection failed";
		}
		return ex.getClass().getSimpleName();
	}

	/**
	 * 응답을 받지 못한 사유. 실행기({@code RequestExecutionException.Reason})와 같은 이름
	 * @return TIMEOUT, CONNECTION_FAILED, TLS_ERROR, IO_ERROR
	 */
	public static String reason(RestClientException ex) {
		if (ex instanceof ResourceAccessException) {
			if (isTls(ex)) {
				return "TLS_ERROR";
			}
			Throwable cause = ex.getCause();
			return cause instanceof HttpTimeoutException || cause instanceof SocketTimeoutException ? "TIMEOUT"
					: "CONNECTION_FAILED";
		}
		return "IO_ERROR";
	}

	/** 인증서 검증·이름 확인 실패와 TLS 협상 실패 (실행기와 같은 기준) */
	private static boolean isTls(Throwable ex) {
		for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
			if (cause instanceof SSLHandshakeException || cause instanceof SSLPeerUnverifiedException) {
				return true;
			}
		}
		return false;
	}

}
