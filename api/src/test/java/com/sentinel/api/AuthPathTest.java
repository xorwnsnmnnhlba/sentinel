package com.sentinel.api;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import com.sentinel.TestcontainersConfiguration;
import com.sentinel.application.auth.AccessTokenService;
import com.sentinel.application.FakeRequestExecutorConfiguration;
import com.sentinel.application.TestFixtures;
import com.sentinel.domain.Project;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 로그인 없이 허용하는 요청(초대·재설정 링크 확인, 계정 만들기, 재설정 링크로 비밀번호 설정)의 경로를 이용한 인증 우회 방지.
 * <p>
 * 인증 필터는 요청 경로 문자열로 예외를 판단하므로, 점 세그먼트·세미콜론·인코딩으로 다른 API에 닿지 않는지 실제 Tomcat에 원문 그대로 요청.
 * (MockMvc는 서블릿 컨테이너의 경로 해석을 거치지 않아 확인에 쓸 수 없음)
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = "sentinel.schedule.enabled=false")
@Import({ TestcontainersConfiguration.class, FakeRequestExecutorConfiguration.class, TestFixtures.class })
class AuthPathTest {

	@LocalServerPort
	private int port;

	@Autowired
	private TestFixtures fixtures;

	@Autowired
	private AccessTokenService accessTokens;

	@AfterEach
	void cleanUp() {
		fixtures.deleteAll();
	}

	@ParameterizedTest
	@ValueSource(strings = { "/api/invites/../projects", "/api/invites/%2e%2e/projects", "/api/invites/..;/projects",
			"/api/invites;/../projects", "/api/invites/x/../../projects", "/api/password-resets/../users",
			"/api/invites/..%2fprojects", "/api//projects" })
	void doesNotReachProtectedApisThroughOpenLinkPaths(String path) throws IOException {
		Project project = fixtures.project();
		fixtures.environment(project, Map.of());

		String response = rawGet(path);

		// 인증 필터가 링크 API로 보지 않고 거부 (Tomcat이 먼저 거부하는 표기는 400)
		assertThat(response).matches("(?s)HTTP/1\\.1 (401|400) .*").doesNotContain(project.getName());
	}

	@ParameterizedTest
	@CsvSource({ "GET, /api/users", "POST, /api/invites", "POST, /api/invites/some-token",
			"POST, /api/password-resets", "POST, /api/password-resets/some-token", "GET, /api/users/1/password",
			"PUT, /api/users/me/password", "PUT, /api/users/abc/password", "DELETE, /api/sessions/current",
			"PUT, /api/users/1", "POST, /api/users/1", "GET, /api/invites/token/extra",
			"GET, /api/password-resets/token/extra" })
	void opensOnlyTheExactMethodAndPathOfEachOpenRequest(String method, String path) throws IOException {
		// 인증 필터가 거부 (처리기까지 가면 400·404)
		assertThat(raw(method, path, "{}")).startsWith("HTTP/1.1 401 ");
	}

	@ParameterizedTest
	@ValueSource(strings = { "/api/invites/%2e%2e", "/api/invites/..;", "/api/password-resets/%2e%2e",
			"/api/users/1/password/../../../projects", "/api/invites/%61bc", "/api/password-resets/%7e" })
	void treatsAmbiguousOpenPathsAsProtected(String path) throws IOException {
		// 인증 필터가 거부하거나 Tomcat이 먼저 거부 (400). 처리기·다른 API에는 닿지 않음
		assertThat(raw(path.contains("password") ? "PUT" : "GET", path, "{}"))
			.matches("(?s)HTTP/1\\.1 (401|400) .*");
	}

	/**
	 * 경로 문자를 인코딩해도 보호 API에 닿지 않음. Tomcat·Spring은 풀어서 /api/로 처리하지만 필터가 원문을 보면 다른 경로로 착각
	 * (2026-10-12 독립 리뷰에서 발견: 원문이 /api/로 시작하지 않으면 인증 없이 통과)
	 */
	@ParameterizedTest
	@ValueSource(strings = { "/%61pi/projects", "/a%70i/projects", "/%61%70%69/environments", "/%61pi/runs",
			"/api/%70rojects", "/%61pi/change-logs", "/%61pi/sessions/current" })
	void requiresAuthenticationForPercentEncodedPaths(String path) throws IOException {
		Project project = fixtures.project();

		String response = rawGet(path, null);

		assertThat(response).matches("(?s)HTTP/1\\.1 (401|400) .*").doesNotContain(project.getName());
	}

	@ParameterizedTest
	@ValueSource(strings = { "/%61pi/runs", "/%61pi/runs/1", "/%61pi/projects" })
	void doesNotLetAnAccessTokenThroughPercentEncodedPaths(String path) throws IOException {
		// HTTPS 앞단은 API 토큰 요청(Authorization: Bearer snt_...)을 web을 거치지 않고 Spring으로 바로 전달
		Project project = fixtures.project();
		String token = accessTokens.issue(fixtures.user().getId(), "ci", 30).token();

		assertThat(rawGet(path, "Bearer snt_not-a-real-token")).matches("(?s)HTTP/1\\.1 (401|400) .*")
			.doesNotContain(project.getName());
		// 유효한 토큰도 허용 목록(POST /api/runs, GET /api/runs/{id})의 원문 경로가 아니면 거부
		assertThat(rawGet(path, "Bearer " + token)).matches("(?s)HTTP/1\\.1 (403|400) .*")
			.doesNotContain(project.getName());
	}

	@ParameterizedTest
	@CsvSource({ "POST, /%61pi/sessions", "POST, /a%70i/users", "GET, /%61pi/invites/some-token" })
	void doesNotOpenLoginRequestsThroughPercentEncodedPaths(String method, String path) throws IOException {
		// 로그인 전 요청도 원문이 정확히 같을 때만 열림 (인코딩한 표기는 인증 요구)
		assertThat(raw(method, path, "{}")).startsWith("HTTP/1.1 401 ");
	}

	@ParameterizedTest
	@CsvSource({ "GET, /api/invites/unknown-token, 404", "GET, /api/password-resets/unknown-token, 404",
			"POST, /api/sessions, 400", "POST, /api/users, 400", "PUT, /api/users/1/password, 400" })
	void letsOpenRequestsThroughToTheirHandler(String method, String path, int status) throws IOException {
		// 인증 없이 처리기까지 닿음 (토큰·입력값 확인은 처리기에서)
		assertThat(raw(method, path, "{}")).startsWith("HTTP/1.1 " + status + " ");
	}

	/** 클라이언트 라이브러리의 경로 정리를 피하려고 요청 줄을 그대로 전송 */
	private String raw(String method, String path, String body) throws IOException {
		try (Socket socket = new Socket("127.0.0.1", port)) {
			socket.setSoTimeout(5000);
			OutputStream out = socket.getOutputStream();
			byte[] content = body.getBytes(StandardCharsets.UTF_8);
			out.write((method + " " + path + " HTTP/1.1\r\nHost: localhost\r\nContent-Type: application/json\r\n"
					+ "Content-Length: " + content.length + "\r\nConnection: close\r\n\r\n")
				.getBytes(StandardCharsets.US_ASCII));
			out.write(content);
			out.flush();
			InputStream in = socket.getInputStream();
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	private String rawGet(String path) throws IOException {
		return rawGet(path, null);
	}

	/** 클라이언트 라이브러리의 경로 정리를 피하려고 요청 줄을 그대로 전송 */
	private String rawGet(String path, String authorization) throws IOException {
		try (Socket socket = new Socket("127.0.0.1", port)) {
			socket.setSoTimeout(5000);
			OutputStream out = socket.getOutputStream();
			String header = authorization == null ? "" : "Authorization: " + authorization + "\r\n";
			out.write(("GET " + path + " HTTP/1.1\r\nHost: localhost\r\n" + header + "Connection: close\r\n\r\n")
				.getBytes(StandardCharsets.US_ASCII));
			out.flush();
			InputStream in = socket.getInputStream();
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

}
