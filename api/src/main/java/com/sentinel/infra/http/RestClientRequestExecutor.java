package com.sentinel.infra.http;

import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
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
import org.apache.hc.client5.http.ConnectTimeoutException;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;
import org.springframework.http.HttpHeaders;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * RestClient 기반 {@link RequestExecutor}.
 * <p>
 * 검증 대상 응답을 그대로 관찰하기 위해 리다이렉트 미추적, 상태 코드별 예외 미발생.
 * <p>
 * HTTP 클라이언트는 Apache HttpClient 5. 연결할 주소를 {@link DestinationGuard}가 직접 해석·확인하도록 지정할 수 있어서 사용
 * (JDK HttpClient는 주소 해석기 지정 불가)
 */
public class RestClientRequestExecutor implements RequestExecutor, AutoCloseable {

	/**
	 * 동시 연결 한도. Tomcat 기본 요청 스레드 수(200)와 같게 두어, 여러 실행이 겹쳐도 연결을 기다리지 않음 (실행 1건은 연결 1개만 사용)
	 */
	private static final int MAX_CONNECTIONS = 200;

	private final CloseableHttpClient httpClient;

	private final RestClient restClient;

	public RestClientRequestExecutor(Duration connectTimeout, Duration readTimeout) {
		this(connectTimeout, readTimeout, DestinationGuard.system());
	}

	RestClientRequestExecutor(Duration connectTimeout, Duration readTimeout, DestinationGuard destinationGuard) {
		this.httpClient = HttpClients.custom()
			.setConnectionManager(PoolingHttpClientConnectionManagerBuilder.create()
				// 연결 직전 주소 해석을 확인 단계와 하나로 묶음. 리다이렉트는 따라가지 않으므로 요청 주소만 확인하면 됨
				.setDnsResolver(destinationGuard)
				.setDefaultConnectionConfig(ConnectionConfig.custom()
					.setConnectTimeout(Timeout.of(connectTimeout))
					.setSocketTimeout(Timeout.of(readTimeout))
					// 재시도를 끄므로 서버가 닫은 유휴 연결을 재사용하지 않도록 사용 전 확인
					.setValidateAfterInactivity(TimeValue.ofSeconds(1))
					.build())
				.setMaxConnTotal(MAX_CONNECTIONS)
				.setMaxConnPerRoute(MAX_CONNECTIONS)
				.build())
			.disableRedirectHandling()
			// 기본 재시도는 429·503 응답과 일부 전송 오류를 다시 보내므로 검증 대상 응답이 가려짐
			.disableAutomaticRetries()
			// 켜 두면 Accept-Encoding을 붙이고 본문을 풀어 응답 헤더와 본문이 서버 응답과 달라짐
			.disableContentCompression()
			.disableCookieManagement()
			.evictIdleConnections(TimeValue.ofSeconds(30))
			.build();
		this.restClient = RestClient.builder()
			.requestFactory(new HttpComponentsClientHttpRequestFactory(httpClient))
			.build();
	}

	@Override
	public HttpExchange execute(ResolvedRequest request) {
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
			for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
				// 주소 해석 단계에서 거부하여 연결·전송 없음
				if (cause instanceof BlockedDestinationException blocked) {
					throw new RequestExecutionException(Reason.BLOCKED_DESTINATION, request, blocked.getMessage());
				}
			}
			throw new RequestExecutionException(classify(ex), request, elapsedSince(startedAt), ex);
		}
	}

	@Override
	public void close() throws IOException {
		httpClient.close();
	}

	private static Reason classify(Throwable ex) {
		for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
			if (cause instanceof ConnectTimeoutException || cause instanceof ConnectException
					|| cause instanceof UnknownHostException) {
				return Reason.CONNECTION_FAILED;
			}
			if (cause instanceof SocketTimeoutException) {
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
