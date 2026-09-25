package com.sentinel.infra.http;

import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.sentinel.runner.HttpExchange;
import com.sentinel.runner.RequestExecutionException;
import com.sentinel.runner.RequestExecutionException.Reason;
import com.sentinel.runner.RequestExecutor;
import com.sentinel.runner.ResolvedRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * RestClient 기반 {@link RequestExecutor}.
 * <p>
 * 검증 대상 응답을 그대로 관찰하기 위해 리다이렉트 미추적, 상태 코드별 예외 미발생.
 */
public class RestClientRequestExecutor implements RequestExecutor {

	private final RestClient restClient;

	private final DestinationGuard destinationGuard;

	public RestClientRequestExecutor(Duration connectTimeout, Duration readTimeout) {
		this(connectTimeout, readTimeout, DestinationGuard.system());
	}

	RestClientRequestExecutor(Duration connectTimeout, Duration readTimeout, DestinationGuard destinationGuard) {
		this.destinationGuard = destinationGuard;
		HttpClient httpClient = HttpClient.newBuilder()
				// 기본값 HTTP/2는 http:// 요청에 h2c 업그레이드를 시도하여 서버에 따라 본문 전송 실패, 헤더명 소문자화 발생
				.version(HttpClient.Version.HTTP_1_1)
				.connectTimeout(connectTimeout)
				.followRedirects(HttpClient.Redirect.NEVER)
				.build();
		JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
		requestFactory.setReadTimeout(readTimeout);
		this.restClient = RestClient.builder().requestFactory(requestFactory).build();
	}

	@Override
	public HttpExchange execute(ResolvedRequest request) {
		// 리다이렉트는 따라가지 않으므로 요청 주소만 확인하면 됨
		destinationGuard.blockedReason(request.uri()).ifPresent(reason -> {
			throw new RequestExecutionException(Reason.BLOCKED_DESTINATION, request, reason);
		});
		long startedAt = System.nanoTime();
		try {
			RestClient.RequestBodySpec spec = restClient
					.method(org.springframework.http.HttpMethod.valueOf(request.method().name()))
					.uri(request.uri())
					.headers(headers -> request.headers().forEach(headers::addAll));
			if (request.body() != null) {
				byte[] body = request.body().getBytes(StandardCharsets.UTF_8);
				spec.body(outputStream -> outputStream.write(body));
			}
			return spec.exchange((clientRequest, response) -> new HttpExchange(
					request,
					response.getStatusCode().value(),
					toMap(response.getHeaders()),
					readBody(response.getBody(), response.getHeaders()),
					elapsedSince(startedAt)));
		}
		catch (RestClientException ex) {
			throw new RequestExecutionException(classify(ex), request, elapsedSince(startedAt), ex);
		}
	}

	private static Reason classify(Throwable ex) {
		for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
			// HttpConnectTimeoutException은 HttpTimeoutException의 하위 타입이므로 먼저 판정
			if (cause instanceof HttpConnectTimeoutException || cause instanceof ConnectException
					|| cause instanceof UnknownHostException) {
				return Reason.CONNECTION_FAILED;
			}
			if (cause instanceof HttpTimeoutException || cause instanceof SocketTimeoutException) {
				return Reason.TIMEOUT;
			}
		}
		return Reason.IO_ERROR;
	}

	private static Map<String, List<String>> toMap(HttpHeaders headers) {
		Map<String, List<String>> map = new LinkedHashMap<>();
		headers.forEach(map::put);
		return map;
	}

	private static String readBody(InputStream body, HttpHeaders headers) throws IOException {
		return new String(body.readAllBytes(), charsetOf(headers));
	}

	private static Charset charsetOf(HttpHeaders headers) {
		try {
			MediaType contentType = headers.getContentType();
			if (contentType != null && contentType.getCharset() != null) {
				return contentType.getCharset();
			}
		}
		catch (InvalidMediaTypeException ignored) {
			// 잘못된 Content-Type은 기본 문자셋으로 처리
		}
		return StandardCharsets.UTF_8;
	}

	private static Duration elapsedSince(long startedAt) {
		return Duration.ofNanos(System.nanoTime() - startedAt);
	}

}
