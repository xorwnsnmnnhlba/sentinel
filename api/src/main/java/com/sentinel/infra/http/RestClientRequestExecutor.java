package com.sentinel.infra.http;

import java.io.ByteArrayOutputStream;
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

import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLPeerUnverifiedException;

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
import org.apache.hc.core5.http.io.EofSensorInputStream;
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

	/** 기본 응답 본문 전체 제한 시간. 알림 Webhook 등 설정이 따로 없는 사용처 */
	static final Duration DEFAULT_TOTAL_TIMEOUT = Duration.ofMinutes(2);

	/** 기본 응답 본문 최대 크기 */
	static final int DEFAULT_MAX_RESPONSE_BYTES = 10 * 1024 * 1024;

	private final CloseableHttpClient httpClient;

	private final RestClient restClient;

	private final Duration totalTimeout;

	private final int maxResponseBytes;

	public RestClientRequestExecutor(Duration connectTimeout, Duration readTimeout) {
		this(connectTimeout, readTimeout, DEFAULT_TOTAL_TIMEOUT, DEFAULT_MAX_RESPONSE_BYTES);
	}

	/**
	 * @param totalTimeout 응답 본문을 다 받을 때까지의 전체 제한 시간 (읽을 때마다 다시 재는 readTimeout과 별개)
	 * @param maxResponseBytes 받는 응답 본문의 최대 바이트. 넘는 부분은 받지 않고 연결을 끊음
	 */
	public RestClientRequestExecutor(Duration connectTimeout, Duration readTimeout, Duration totalTimeout,
			int maxResponseBytes) {
		this(connectTimeout, readTimeout, totalTimeout, maxResponseBytes, DestinationGuard.system());
	}

	RestClientRequestExecutor(Duration connectTimeout, Duration readTimeout, DestinationGuard destinationGuard) {
		this(connectTimeout, readTimeout, DEFAULT_TOTAL_TIMEOUT, DEFAULT_MAX_RESPONSE_BYTES, destinationGuard);
	}

	RestClientRequestExecutor(Duration connectTimeout, Duration readTimeout, Duration totalTimeout,
			int maxResponseBytes, DestinationGuard destinationGuard) {
		this.totalTimeout = totalTimeout;
		this.maxResponseBytes = maxResponseBytes;
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
			// 케이스에 User-Agent가 없을 때만 붙음 (적은 값이 있으면 그 값)
			.setUserAgent(UserAgent.VALUE)
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
				// 길이를 알려 보통 클라이언트처럼 고정 길이로 전송. 모르면 조각 전송(chunked)이 되어 일부 서버·게이트웨이가
				// 거부하거나 다르게 처리함. 케이스에 직접 넣은 Content-Length도 실제 길이로 바꿈 (다르면 서버가 기다리다 시간 초과)
				spec.contentLength(body.length).body(outputStream -> outputStream.write(body));
			}
			return spec.exchange((clientRequest, response) -> new HttpExchange(
					request,
					response.getStatusCode().value(),
					toMap(response.getHeaders()),
					readBody(response.getBody(), response.getHeaders(), startedAt),
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
			// 인증서 검증·이름 확인 실패와 TLS 협상 실패. 일반 전송 오류와 나눠 "인증서 만료" 같은 흔한 장애를 바로 알아보도록
			if (cause instanceof SSLHandshakeException || cause instanceof SSLPeerUnverifiedException) {
				return Reason.TLS_ERROR;
			}
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

	/**
	 * 응답 본문을 최대 크기·전체 제한 시간 안에서 읽음. 넘으면 남은 본문을 읽지 않고 연결을 끊음
	 * (닫기만 하면 HTTP 클라이언트가 연결을 다시 쓰려고 남은 본문을 끝까지 읽음)
	 * <ul>
	 * <li>크기 초과: 앞부분만 본문으로 사용 (저장 시에는 다시 {@code TestResult.MAX_BODY_BYTES}로 자름)</li>
	 * <li>시간 초과: 타임아웃({@link SocketTimeoutException})으로 처리. 1바이트씩 늦게 보내는 응답이 실행을 붙잡지 않도록</li>
	 * </ul>
	 * 읽기 한 번은 readTimeout까지 걸릴 수 있으므로 실제 대기는 전체 제한 시간 + readTimeout 이내
	 */
	private String readBody(InputStream body, HttpHeaders headers, long startedAt) throws IOException {
		long deadline = startedAt + totalTimeout.toNanos();
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		byte[] buffer = new byte[8192];
		int read;
		while ((read = body.read(buffer)) != -1) {
			if (System.nanoTime() - deadline > 0) {
				abort(body);
				throw new SocketTimeoutException("response not completed within " + totalTimeout);
			}
			int room = maxResponseBytes - out.size();
			if (read > room) {
				out.write(buffer, 0, room);
				abort(body);
				break;
			}
			out.write(buffer, 0, read);
		}
		return out.toString(charsetOf(headers));
	}

	/** 남은 본문을 읽지 않고 연결을 버림. 연결 풀에 돌려주지 않음 */
	private static void abort(InputStream body) throws IOException {
		if (body instanceof EofSensorInputStream sensor) {
			sensor.abort();
		}
		else {
			body.close();
		}
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
