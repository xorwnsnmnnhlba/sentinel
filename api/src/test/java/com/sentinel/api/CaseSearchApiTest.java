package com.sentinel.api;

import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import com.jayway.jsonpath.JsonPath;
import com.sentinel.TestAuthConfiguration;
import com.sentinel.TestcontainersConfiguration;
import com.sentinel.application.CatalogQueryService;
import com.sentinel.application.FakeRequestExecutorConfiguration;
import com.sentinel.application.TestFixtures;
import com.sentinel.domain.Project;
import com.sentinel.domain.TestCase;
import com.sentinel.domain.TestSuite;
import com.sentinel.infra.persistence.TestCaseRepository;
import com.sentinel.runner.HttpMethod;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import static org.assertj.core.api.Assertions.assertThat;

/** 케이스 검색 ({@code GET /api/cases?q=}). 이름·경로 부분 일치, 대소문자 무시 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({ TestcontainersConfiguration.class, FakeRequestExecutorConfiguration.class, TestFixtures.class,
		TestAuthConfiguration.class })
class CaseSearchApiTest {

	@Autowired
	private MockMvcTester mvc;

	@Autowired
	private TestFixtures fixtures;

	@Autowired
	private TestCaseRepository testCases;

	@AfterEach
	void cleanUp() {
		fixtures.deleteAll();
	}

	@Test
	void findsCasesByNameOrPathIgnoringCaseAcrossSuites() {
		Project billing = fixtures.project();
		TestSuite payments = fixtures.suite(billing);
		TestSuite auth = fixtures.suite(billing);
		TestSuite reports = fixtures.suite(fixtures.project());
		TestCase charge = named(payments, "결제 승인", "/billing/v1/charge");
		TestCase history = named(payments, "결제 내역", "/billing/v1/history");
		named(auth, "Login", "/auth/login");
		TestCase report = named(reports, "Charge report", "/reports/daily");
		TestCase deleted = named(payments, "old charge", "/billing/v0/charge");
		deleted.archive(Instant.now());
		testCases.save(deleted);

		// 경로(charge)와 이름(Charge report) 모두, 대소문자 무시, 삭제된 케이스 제외. 스위트별 실행 순서
		assertThat(search("CHARGE")).containsExactlyInAnyOrder(charge.getId(), report.getId());
		assertThat(search("결제")).containsExactly(charge.getId(), history.getId());
		// 스위트 안에서만
		assertThat(search("v1", auth.getId())).isEmpty();
		assertThat(search("v1", payments.getId())).containsExactly(charge.getId(), history.getId());
		// 앞뒤 공백 제거
		assertThat(search(" login ")).hasSize(1);
	}

	@Test
	void treatsWildcardsAsPlainCharacters() {
		TestSuite suite = fixtures.suite(fixtures.project());
		TestCase percent = named(suite, "100% done", "/done");
		named(suite, "other", "/other");
		TestCase underscore = named(suite, "snake", "/a_b");
		named(suite, "any", "/axb");

		assertThat(search("%")).containsExactly(percent.getId());
		assertThat(search("a_b")).containsExactly(underscore.getId());
	}

	@Test
	void returnsAtMostTheLimit() {
		TestSuite suite = fixtures.suite(fixtures.project());
		for (int i = 0; i <= CatalogQueryService.MAX_SEARCH_RESULTS; i++) {
			named(suite, "health " + i, "/health/" + i);
		}

		assertThat(search("health")).hasSize(CatalogQueryService.MAX_SEARCH_RESULTS);
	}

	@Test
	void rejectsBlankOrLongQueries() {
		assertThat(mvc.get().uri("/api/cases?q={q}", "  ")).hasStatus(HttpStatus.BAD_REQUEST);
		assertThat(mvc.get().uri("/api/cases?q={q}", "x".repeat(201))).hasStatus(HttpStatus.BAD_REQUEST);
		assertThat(mvc.get().uri("/api/cases?q={q}", "x".repeat(200))).hasStatusOk();
	}

	private TestCase named(TestSuite suite, String name, String path) {
		TestCase testCase = fixtures.testCase(suite, HttpMethod.GET, path, null, null);
		testCase.rename(name);
		return testCases.save(testCase);
	}

	private List<Long> search(String query) {
		return ids(mvc.get().uri("/api/cases?q={q}", query).exchange());
	}

	private List<Long> search(String query, long suiteId) {
		return ids(mvc.get().uri("/api/cases?q={q}&suiteId={suiteId}", query, suiteId).exchange());
	}

	private static List<Long> ids(MvcTestResult result) {
		assertThat(result).hasStatusOk();
		try {
			List<Integer> ids = JsonPath.read(result.getResponse().getContentAsString(StandardCharsets.UTF_8), "$[*].id");
			return ids.stream().map(Integer::longValue).toList();
		}
		catch (UnsupportedEncodingException ex) {
			throw new IllegalStateException(ex);
		}
	}

}
