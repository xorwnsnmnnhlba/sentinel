package com.sentinel.api;

import java.io.UnsupportedEncodingException;
import java.util.List;
import java.util.Map;

import com.sentinel.TestAuthConfiguration;
import com.sentinel.TestcontainersConfiguration;
import com.sentinel.application.FakeRequestExecutorConfiguration;
import com.sentinel.application.TestFixtures;
import com.sentinel.domain.Project;
import com.sentinel.domain.TestCase;
import com.sentinel.domain.TestSuite;
import com.sentinel.infra.persistence.TestCaseRepository;
import com.sentinel.runner.HttpMethod;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OpenAPI 문서 미리보기와 케이스 가져오기. 변환 규칙 자체는 {@code OpenApiConverterTest}에서 검증
 */
@SpringBootTest(properties = "sentinel.schedule.enabled=false")
@AutoConfigureMockMvc
@Import({ TestcontainersConfiguration.class, FakeRequestExecutorConfiguration.class, TestFixtures.class,
		TestAuthConfiguration.class })
class CaseImportTest {

	private static final String DOCUMENT = """
			openapi: 3.0.3
			info: { title: Orders, version: "2.0" }
			servers: [ { url: "https://orders.example.com/api" } ]
			paths:
			  /orders:
			    get: { summary: List orders, responses: { "200": { description: ok } } }
			    post:
			      summary: Create order
			      requestBody:
			        content:
			          application/json: { example: { item: 1 } }
			      responses: { "201": { description: created } }
			  /orders/{orderId}:
			    get: { summary: Get order, responses: { "200": { description: ok } } }
			""";

	@Autowired
	private MockMvcTester mvc;

	@Autowired
	private TestFixtures fixtures;

	@Autowired
	private TestCaseRepository testCases;

	private final JsonMapper json = JsonMapper.builder().build();

	private TestSuite suite;

	@BeforeEach
	void setUp() {
		Project project = fixtures.project();
		suite = fixtures.suite(project);
	}

	@AfterEach
	void cleanUp() {
		fixtures.deleteAll();
	}

	@Test
	void previewsCandidatesWithoutSavingAndFlagsNameConflicts() {
		TestCase existing = fixtures.testCase(suite, HttpMethod.GET, "/orders", null, null);
		rename(existing, "List orders");

		MvcTestResult preview = preview(suite.getId(), DOCUMENT);

		assertThat(preview).hasStatusOk();
		var body = assertThat(preview).bodyJson();
		body.extractingPath("$.title").isEqualTo("Orders");
		body.extractingPath("$.version").isEqualTo("2.0");
		body.extractingPath("$.servers").isEqualTo(List.of("https://orders.example.com/api"));
		body.extractingPath("$.cases[*].source").isEqualTo(List.of("GET /orders", "POST /orders", "GET /orders/{orderId}"));
		body.extractingPath("$.cases[*].conflict").isEqualTo(List.of(true, false, false));
		body.extractingPath("$.cases[1].request.method").isEqualTo("POST");
		body.extractingPath("$.cases[1].request.headers.Content-Type").isEqualTo(List.of("application/json"));
		body.extractingPath("$.cases[1].assertions[0].type").isEqualTo("statusCode");
		body.extractingPath("$.cases[1].assertions[0].anyOf").isEqualTo(List.of("201"));
		body.extractingPath("$.cases[2].request.path").isEqualTo("/orders/{{orderId}}");
		// 미리보기는 저장하지 않음
		assertThat(testCases.findBySuiteIdAndArchivedAtIsNullOrderByRunOrderAscIdAsc(suite.getId())).hasSize(1);
	}

	@Test
	void importsTheChosenCandidatesAsTheyWereEdited() {
		ArrayNode candidates = candidates(preview(suite.getId(), DOCUMENT));
		// 미리보기 결과를 그대로 보내되 첫 케이스는 제외하고 이름 하나를 바꿈 (source·warnings·conflict는 무시됨)
		candidates.remove(0);
		((ObjectNode) candidates.get(1)).put("name", "Get one order");
		String chosen = json.writeValueAsString(candidates);

		MvcTestResult imported = post("/api/case-imports", """
				{"suiteId": %d, "cases": %s}""".formatted(suite.getId(), chosen));

		assertThat(imported).hasStatus(HttpStatus.CREATED);
		assertThat(imported).bodyJson().extractingPath("$.suiteId").isEqualTo(suite.getId().intValue());
		assertThat(imported).bodyJson().extractingPath("$.cases[*].name").isEqualTo(List.of("Create order", "Get one order"));
		List<TestCase> saved = testCases.findBySuiteIdAndArchivedAtIsNullOrderByRunOrderAscIdAsc(suite.getId());
		assertThat(saved).extracting(TestCase::getName).containsExactly("Create order", "Get one order");
		assertThat(saved.getFirst().getRequest().getBody()).contains("\"item\": 1");
		assertThat(saved.get(1).getRequest().getPath()).isEqualTo("/orders/{{orderId}}");
	}

	@Test
	void previewsAndImportsAPostmanCollection() throws UnsupportedEncodingException {
		String collection = """
				{
				  "info": {"name": "Shop", "schema": "https://schema.getpostman.com/json/collection/v2.1.0/collection.json"},
				  "auth": {"type": "bearer", "bearer": [{"key": "token", "value": "literal-token"}]},
				  "item": [{"name": "Orders", "item": [
				    {"name": "List", "request": {"method": "GET", "url": "{{baseUrl}}/orders?page=1"}}
				  ]}]
				}""";

		MvcTestResult preview = post("/api/case-import-previews", json.writeValueAsString(
				Map.of("suiteId", suite.getId(), "format", "POSTMAN", "content", collection)));

		assertThat(preview).hasStatusOk();
		var body = assertThat(preview).bodyJson();
		body.extractingPath("$.title").isEqualTo("Shop");
		body.extractingPath("$.cases[0].name").isEqualTo("Orders / List");
		body.extractingPath("$.cases[0].request.path").isEqualTo("/orders?page=1");
		// 평문 토큰은 변수로 바뀌고 응답 어디에도 없음
		body.extractingPath("$.cases[0].request.headers.Authorization").isEqualTo(List.of("Bearer {{bearerToken}}"));
		assertThat(preview.getResponse().getContentAsString()).doesNotContain("literal-token");

		ArrayNode candidates = candidates(preview);
		assertThat(post("/api/case-imports", """
				{"suiteId": %d, "cases": %s}""".formatted(suite.getId(), json.writeValueAsString(candidates))))
			.hasStatus(HttpStatus.CREATED);
		assertThat(testCases.findBySuiteIdAndArchivedAtIsNullOrderByRunOrderAscIdAsc(suite.getId())).singleElement()
			.satisfies(saved -> assertThat(saved.getRequest().getHeaders())
				.containsEntry("Authorization", List.of("Bearer {{bearerToken}}")));
	}

	@Test
	void importsNothingWhenAnyCaseConflicts() {
		fixtures.testCase(suite, HttpMethod.GET, "/orders", null, null);
		String existingName = testCases.findBySuiteIdAndArchivedAtIsNullOrderByRunOrderAscIdAsc(suite.getId()).getFirst().getName();

		MvcTestResult conflict = post("/api/case-imports", """
				{"suiteId": %d, "cases": [
				  {"name": "New case", "request": {"method": "GET", "path": "/new"}},
				  {"name": "%s", "request": {"method": "GET", "path": "/dup"}}
				]}""".formatted(suite.getId(), existingName));

		assertThat(conflict).hasStatus(HttpStatus.CONFLICT);
		// 앞선 케이스도 등록되지 않음
		assertThat(testCases.findBySuiteIdAndArchivedAtIsNullOrderByRunOrderAscIdAsc(suite.getId())).hasSize(1);

		// 목록 안에서 겹치는 이름도 거부
		assertThat(post("/api/case-imports", """
				{"suiteId": %d, "cases": [
				  {"name": "Twice", "request": {"method": "GET", "path": "/a"}},
				  {"name": "Twice", "request": {"method": "GET", "path": "/b"}}
				]}""".formatted(suite.getId()))).hasStatus(HttpStatus.CONFLICT);
		assertThat(testCases.findBySuiteIdAndArchivedAtIsNullOrderByRunOrderAscIdAsc(suite.getId())).hasSize(1);
	}

	@Test
	void pointsAtTheInvalidCase() {
		MvcTestResult invalid = post("/api/case-imports", """
				{"suiteId": %d, "cases": [
				  {"name": "Fine", "request": {"method": "GET", "path": "/fine"}},
				  {"name": "Broken", "request": {"method": "GET", "path": "no-slash"}}
				]}""".formatted(suite.getId()));

		assertThat(invalid).hasStatus(HttpStatus.BAD_REQUEST)
			.bodyJson()
			.extractingPath("$.detail")
			.asString()
			.startsWith("cases[1] 'Broken': path must start with");
		assertThat(testCases.findBySuiteIdAndArchivedAtIsNullOrderByRunOrderAscIdAsc(suite.getId())).isEmpty();
	}

	@Test
	void rejectsUnsupportedDocumentsAndUnknownSuites() {
		assertThat(preview(suite.getId(), """
				swagger: "2.0"
				paths: {}""")).hasStatus(HttpStatus.BAD_REQUEST)
			.bodyJson()
			.extractingPath("$.detail")
			.isEqualTo("only OpenAPI 3 documents are supported (found swagger 2.0)");
		assertThat(preview(999_999L, DOCUMENT)).hasStatus(HttpStatus.NOT_FOUND);
		assertThat(post("/api/case-import-previews", """
				{"suiteId": %d, "format": "RAML", "content": "{}"}""".formatted(suite.getId())))
			.hasStatus(HttpStatus.BAD_REQUEST);
		assertThat(post("/api/case-imports", """
				{"suiteId": %d, "cases": []}""".formatted(suite.getId()))).hasStatus(HttpStatus.BAD_REQUEST);
		assertThat(post("/api/case-imports", """
				{"suiteId": 999999, "cases": [{"name": "x", "request": {"method": "GET", "path": "/x"}}]}"""))
			.hasStatus(HttpStatus.NOT_FOUND);
	}

	private MvcTestResult preview(long suiteId, String document) {
		return post("/api/case-import-previews", json.writeValueAsString(
				Map.of("suiteId", suiteId, "format", "OPENAPI_3", "content", document)));
	}

	private ArrayNode candidates(MvcTestResult preview) {
		try {
			return (ArrayNode) json.readTree(preview.getResponse().getContentAsString()).get("cases");
		}
		catch (UnsupportedEncodingException ex) {
			throw new IllegalStateException(ex);
		}
	}

	private void rename(TestCase testCase, String name) {
		testCase.rename(name);
		testCases.save(testCase);
	}

	private MvcTestResult post(String uri, String body) {
		return mvc.post().uri(uri).contentType(MediaType.APPLICATION_JSON).content(body).exchange();
	}

}
