package com.sentinel.api;

import java.util.Map;

import com.jayway.jsonpath.JsonPath;
import com.sentinel.TestAuthConfiguration;
import com.sentinel.TestcontainersConfiguration;
import com.sentinel.application.FakeRequestExecutorConfiguration;
import com.sentinel.application.TestFixtures;
import com.sentinel.domain.Environment;
import com.sentinel.domain.Project;
import com.sentinel.domain.TestCase;
import com.sentinel.runner.HttpMethod;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 수정 화면을 연 뒤 다른 곳에서 먼저 고친 환경·케이스 저장 거부 (2026-10-16 변경분 리뷰).
 * 저장 시점의 낙관적 잠금은 동시에 겹친 요청만 막으므로, 화면이 읽은 version을 함께 보내 그사이의 변경을 덮어쓰지 않음
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({ TestcontainersConfiguration.class, FakeRequestExecutorConfiguration.class, TestFixtures.class,
		TestAuthConfiguration.class })
class StaleEditApiTest {

	private static final String CONCURRENT_MODIFICATION = "modified by another request; reload and try again";

	@Autowired
	private MockMvcTester mvc;

	@Autowired
	private TestFixtures fixtures;

	@AfterEach
	void cleanUp() {
		fixtures.deleteAll();
	}

	@Test
	void rejectsSavingAnEnvironmentFormOpenedBeforeAnotherChange() {
		Environment environment = fixtures.environment(fixtures.project(), Map.of("userId", "42"));
		String uri = "/api/environments/" + environment.getId();
		long opened = version(mvc.get().uri(uri).exchange());

		// B가 변수를 추가해 저장. 응답의 version은 저장 후 값
		MvcTestResult other = patch(uri, """
				{"variables": {"userId": "42", "locale": "ko-KR"}, "version": %d}""".formatted(opened));
		assertThat(other).hasStatusOk();
		long saved = version(other);
		assertThat(saved).isGreaterThan(opened);
		assertThat(version(mvc.get().uri(uri).exchange())).isEqualTo(saved);

		// A가 먼저 열어 둔 화면(B의 변수 없음)으로 저장하면 거부되고 B의 변수는 남음
		assertThat(patch(uri, """
				{"variables": {"userId": "7"}, "version": %d}""".formatted(opened))).hasStatus(HttpStatus.CONFLICT)
			.bodyJson()
			.extractingPath("$.detail")
			.isEqualTo(CONCURRENT_MODIFICATION);
		var current = assertThat(mvc.get().uri(uri)).hasStatusOk().bodyJson();
		current.extractingPath("$.variables.locale").isEqualTo("ko-KR");
		current.extractingPath("$.variables.userId").isEqualTo("42");

		// 다시 불러온 version으로는 저장
		assertThat(patch(uri, """
				{"variables": {"userId": "7", "locale": "ko-KR"}, "version": %d}""".formatted(saved))).hasStatusOk();
	}

	@Test
	void rejectsSavingACaseFormOpenedBeforeAnotherChange() {
		TestCase testCase = fixtures.testCase(fixtures.project(), HttpMethod.GET, "/health", Map.of(), null);
		String uri = "/api/cases/" + testCase.getId();
		long opened = version(mvc.get().uri(uri).exchange());

		MvcTestResult other = patch(uri, """
				{"name": "B가 고친 이름", "version": %d}""".formatted(opened));
		assertThat(other).hasStatusOk();
		assertThat(version(other)).isGreaterThan(opened).isEqualTo(version(mvc.get().uri(uri).exchange()));

		assertThat(patch(uri, """
				{"name": "A가 고친 이름", "version": %d}""".formatted(opened))).hasStatus(HttpStatus.CONFLICT)
			.bodyJson()
			.extractingPath("$.detail")
			.isEqualTo(CONCURRENT_MODIFICATION);
		assertThat(mvc.get().uri(uri)).bodyJson().extractingPath("$.name").isEqualTo("B가 고친 이름");
	}

	@Test
	void keepsAcceptingUpdatesWithoutAVersion() {
		// version을 모르는 기존 API 호출(스크립트 등)은 확인 없이 저장 (저장 시점의 낙관적 잠금만)
		Environment environment = fixtures.environment(fixtures.project(), Map.of());
		TestCase testCase = fixtures.testCase(fixtures.project(), HttpMethod.GET, "/health", Map.of(), null);

		assertThat(patch("/api/environments/" + environment.getId(), """
				{"baseUrl": "https://gw.example"}""")).hasStatusOk();
		assertThat(patch("/api/environments/" + environment.getId(), """
				{"baseUrl": "https://gw2.example"}""")).hasStatusOk();
		assertThat(patch("/api/cases/" + testCase.getId(), """
				{"name": "이름 변경"}""")).hasStatusOk();
	}

	private MvcTestResult patch(String uri, String body) {
		return mvc.patch().uri(uri).contentType(MediaType.APPLICATION_JSON).content(body).exchange();
	}

	private static long version(MvcTestResult result) {
		try {
			return ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.version")).longValue();
		}
		catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}

}
