package com.sentinel.application.importing;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import com.sentinel.application.InvalidRequestException;
import com.sentinel.runner.HttpMethod;
import com.sentinel.runner.assertion.StatusCodeAssertion;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenApiConverterTest {

	private final OpenApiConverter converter = new OpenApiConverter();

	@Test
	void convertsEveryOperationOfTheDocument() {
		ConvertedDocument document = converter.convert(resource("openapi/petstore.yaml"));

		assertThat(document.title()).isEqualTo("Petstore");
		assertThat(document.version()).isEqualTo("1.2.0");
		assertThat(document.servers()).containsExactly("https://petstore.example.com/v1",
				"https://staging.petstore.example.com/v1");
		// 문서 순서 유지, TRACE는 제외
		assertThat(document.cases()).extracting(CaseDraft::source)
			.containsExactly("GET /pets", "POST /pets", "GET /pets/{petId}", "PUT /pets/{petId}",
					"DELETE /pets/{petId}", "POST /pets/{petId}/photo", "PATCH /stores/{store[id]}", "POST /orders",
					"GET /external");
		assertThat(document.warnings()).containsExactly("TRACE /pets: TRACE is not supported and was skipped");
	}

	@Test
	void fillsRequiredQueryParametersOnly() {
		CaseDraft list = draft("GET /pets");

		assertThat(list.name()).isEqualTo("List pets");
		assertThat(list.request().method()).isEqualTo(HttpMethod.GET);
		assertThat(list.request().path()).isEqualTo("/pets?status={{status}}");
		assertThat(list.request().headers()).isEmpty();
		assertThat(list.request().body()).isNull();
		assertThat(list.assertions()).containsExactly(new StatusCodeAssertion(List.of("200")));
		assertThat(list.warnings()).isEmpty();
	}

	@Test
	void turnsPathParametersAndRequiredHeadersIntoVariables() {
		CaseDraft find = draft("GET /pets/{petId}");

		// 경로 공통 파라미터(참조 포함)도 적용
		assertThat(find.request().path()).isEqualTo("/pets/{{petId}}");
		assertThat(find.request().headers()).containsExactly(Map.entry("X-Request-Id", List.of("{{X-Request-Id}}")));
		// 2xx 응답만 판정에 사용
		assertThat(find.assertions()).containsExactly(new StatusCodeAssertion(List.of("200")));
	}

	@Test
	void letsOperationParametersOverrideSharedOnes() {
		CaseDraft update = draft("PUT /pets/{petId}");

		// 작업에서 선택으로 재정의한 헤더는 생략
		assertThat(update.request().headers()).containsOnlyKeys("Content-Type");
		assertThat(update.assertions()).containsExactly(new StatusCodeAssertion(List.of("2xx")));
	}

	@Test
	void takesTheRequestBodyFromExamples() {
		// 참조한 requestBody의 example
		CaseDraft create = draft("POST /pets");
		assertThat(create.name()).isEqualTo("createPet");
		assertThat(create.request().headers()).containsEntry("Content-Type", List.of("application/json"));
		assertThat(create.request().body()).isEqualTo("""
				{
				  "name": "Kitty",
				  "tags": [
				    "cat"
				  ]
				}""");
		assertThat(create.assertions()).containsExactly(new StatusCodeAssertion(List.of("201")));

		// examples의 참조
		assertThat(draft("PUT /pets/{petId}").request().body()).isEqualTo("""
				{
				  "name": "Tom"
				}""");

		// 스키마의 example, +json 미디어 타입
		CaseDraft store = draft("PATCH /stores/{store[id]}");
		assertThat(store.request().headers()).containsEntry("Content-Type", List.of("application/merge-patch+json"));
		assertThat(store.request().body()).contains("\"name\": \"Main\"");
	}

	@Test
	void warnsAboutWhatCouldNotBeConverted() {
		assertThat(draft("DELETE /pets/{petId}")).satisfies(delete -> {
			// 이름 없는 작업은 메서드와 경로
			assertThat(delete.name()).isEqualTo("DELETE /pets/{petId}");
			assertThat(delete.assertions()).containsExactly(new StatusCodeAssertion(List.of("2xx")));
			assertThat(delete.warnings()).containsExactly("operation is deprecated",
					"no 2xx response is declared; expecting any 2xx");
		});
		assertThat(draft("POST /pets/{petId}/photo")).satisfies(upload -> {
			assertThat(upload.request().body()).isNull();
			assertThat(upload.request().headers()).isEmpty();
			assertThat(upload.warnings())
				.containsExactly("request body media type [multipart/form-data] is not supported; body left empty");
		});
		// 예시가 없으면 스키마로 뼈대 생성 (속성 없는 객체는 빈 객체)
		assertThat(draft("POST /orders")).satisfies(order -> {
			assertThat(order.request().body()).isEqualTo("{}");
			assertThat(order.request().headers()).containsEntry("Content-Type", List.of("application/json"));
			assertThat(order.warnings())
				.containsExactly("request body was generated from the schema; replace the placeholder values");
		});
		assertThat(draft("GET /external").warnings())
			.containsExactly("parameter: external reference 'common.yaml#/components/parameters/Tenant' is not supported");
		assertThat(draft("PATCH /stores/{store[id]}")).satisfies(store -> {
			assertThat(store.request().path()).isEqualTo("/stores/{{store_id_}}");
			assertThat(store.warnings()).containsExactly("parameter 'store[id]' is used as variable {{store_id_}}");
		});
	}

	@Test
	void sendsCredentialsOfTheSecuritySchemeAsVariables() {
		ConvertedDocument document = converter.convert(resource("openapi/secured.yaml"));

		// 문서 공통 인증
		assertThat(secured("GET /me").request().headers())
			.containsExactly(Map.entry("Authorization", List.of("Bearer {{bearerAuth}}")));
		// security: []는 인증 없음
		assertThat(secured("GET /health").request().headers()).isEmpty();
		// 첫 번째 대안의 스킴 모두 적용. 규칙상 무시하는 헤더 파라미터(Authorization·Accept)는 생략
		assertThat(secured("GET /reports")).satisfies(reports -> {
			assertThat(reports.request().path()).isEqualTo("/reports?from={{from}}&tenant={{tenantKey}}");
			assertThat(reports.request().headers())
				.containsExactly(Map.entry("X-API-Key", List.of("{{apiKeyHeader}}")));
			assertThat(reports.warnings()).isEmpty();
		});
		assertThat(secured("GET /legacy").request().headers())
			.containsExactly(Map.entry("Authorization", List.of("Basic {{basicAuth}}")));
		assertThat(secured("GET /oauth").request().headers())
			.containsExactly(Map.entry("Authorization", List.of("Bearer {{oauth}}")));
		// 어떤 변수를 시크릿으로 등록해야 하는지 스킴마다 한 번 안내
		assertThat(document.warnings()).containsExactly(
				"security scheme 'bearerAuth' is sent as 'Authorization: Bearer {{bearerAuth}}'; add {{bearerAuth}} as a secret of the environment",
				"security scheme 'apiKeyHeader' is sent as 'X-API-Key: {{apiKeyHeader}}'; add {{apiKeyHeader}} as a secret of the environment",
				"security scheme 'tenantKey' is sent as query tenant={{tenantKey}}; add {{tenantKey}} as a secret of the environment",
				"security scheme 'basicAuth' is sent as 'Authorization: Basic {{basicAuth}}'; add {{basicAuth}} as a secret of the environment",
				"security scheme 'oauth' is sent as 'Authorization: Bearer {{oauth}}'; add {{oauth}} as a secret of the environment");
	}

	@Test
	void warnsAboutSecuritySchemesItCannotSend() {
		CaseDraft unsupported = secured("GET /unsupported");

		assertThat(unsupported.request().headers()).isEmpty();
		assertThat(unsupported.warnings()).containsExactly(
				"security scheme 'sessionCookie' (apiKey cookie) is not supported",
				"security scheme 'digestAuth' (http digest) is not supported",
				"security scheme 'ghost' is not defined");
	}

	@Test
	void buildsTheRequestBodyFromTheSchema() {
		CaseDraft user = secured("POST /users");

		// readOnly(id·createdAt) 제외, example·default·enum·const·format 반영, allOf 병합, type 생략 객체 추정
		assertThat(user.request().body()).isEqualTo("""
				{
				  "name": "Kim",
				  "email": "user@example.com",
				  "role": "ADMIN",
				  "age": 0,
				  "active": true,
				  "nickname": "string",
				  "tags": [
				    "string"
				  ],
				  "address": {
				    "city": "string",
				    "zip": "string"
				  },
				  "birthday": "2026-01-01",
				  "kind": "person"
				}""");
		assertThat(user.request().headers()).containsExactly(Map.entry("Content-Type", List.of("application/json")));
		assertThat(user.warnings())
			.containsExactly("request body was generated from the schema; replace the placeholder values");

		// 자기 참조 스키마는 되풀이하지 않음
		assertThat(secured("POST /nodes").request().body()).isEqualTo("""
				{
				  "name": "string",
				  "children": []
				}""");
	}

	@Test
	void readsJsonDocumentsToo() {
		ConvertedDocument document = converter.convert("""
				{"openapi": "3.1.0", "info": {"title": "Mini", "version": "1"},
				 "paths": {"/health": {"get": {"responses": {"200": {"description": "ok"}}}}}}""");

		assertThat(document.cases()).singleElement().satisfies(health -> {
			assertThat(health.name()).isEqualTo("GET /health");
			assertThat(health.request().path()).isEqualTo("/health");
		});
		assertThat(document.servers()).isEmpty();
	}

	@Test
	void expandsYamlAliases() {
		// 공통 응답을 앵커로 재사용하는 문서. 별칭을 앵커 이름 문자열로 읽으면 판정이 2xx로 바뀜
		ConvertedDocument document = converter.convert("""
				openapi: 3.0.0
				info: { title: Anchors, version: "1" }
				x-common:
				  created: &created { "201": { description: created } }
				paths:
				  /a:
				    post: { summary: A, responses: *created }
				  /b:
				    post: { summary: B, responses: *created }
				""");

		assertThat(document.cases()).allSatisfy(draft -> {
			assertThat(draft.assertions()).containsExactly(new StatusCodeAssertion(List.of("201")));
			assertThat(draft.warnings()).isEmpty();
		});
	}

	@Test
	void numbersRepeatedNames() {
		ConvertedDocument document = converter.convert("""
				openapi: 3.0.0
				info: { title: Dup, version: "1" }
				paths:
				  /a:
				    get: { summary: Check, responses: { "200": { description: ok } } }
				  /b:
				    get: { summary: Check, responses: { "200": { description: ok } } }
				  /c:
				    get: { summary: "Check (2)", responses: { "200": { description: ok } } }
				""");

		assertThat(document.cases()).extracting(CaseDraft::name).containsExactly("Check", "Check (2)", "Check (2) (2)");
	}

	@Test
	void stopsFollowingCircularReferences() {
		ConvertedDocument document = converter.convert("""
				openapi: 3.0.0
				info: { title: Loop, version: "1" }
				paths:
				  /loop:
				    post:
				      requestBody: { $ref: "#/components/requestBodies/A" }
				      responses: { "200": { description: ok } }
				components:
				  requestBodies:
				    A: { $ref: "#/components/requestBodies/B" }
				    B: { $ref: "#/components/requestBodies/A" }
				""");

		assertThat(document.cases().getFirst().warnings()).singleElement()
			.asString()
			.startsWith("request body: reference '#/components/requestBodies/");
	}

	@Test
	void rejectsDocumentsThatAreNotOpenApi3() {
		assertThatThrownBy(() -> converter.convert("""
				swagger: "2.0"
				info: { title: Old, version: "1" }
				paths: {}
				""")).isInstanceOf(InvalidRequestException.class)
			.hasMessage("only OpenAPI 3 documents are supported (found swagger 2.0)");
		assertThatThrownBy(() -> converter.convert("info: { title: None }"))
			.isInstanceOf(InvalidRequestException.class)
			.hasMessage("only OpenAPI 3 documents are supported (found openapi none)");
		assertThatThrownBy(() -> converter.convert("- just\n- a list"))
			.isInstanceOf(InvalidRequestException.class)
			.hasMessage("document must be a JSON or YAML object");
		assertThatThrownBy(() -> converter.convert("openapi: 3.0.0\npaths: {}"))
			.isInstanceOf(InvalidRequestException.class)
			.hasMessage("document defines no paths");
		assertThatThrownBy(() -> converter.convert("""
				openapi: 3.0.0
				paths:
				  /only-trace:
				    trace: { responses: { "200": { description: ok } } }
				""")).isInstanceOf(InvalidRequestException.class)
			.hasMessage("document defines no supported operations");
	}

	@Test
	void rejectsUnreadableOrOversizedDocuments() {
		assertThatThrownBy(() -> converter.convert("   ")).isInstanceOf(InvalidRequestException.class)
			.hasMessage("document must not be empty");
		assertThatThrownBy(() -> converter.convert("openapi: [3.0.0")).isInstanceOf(InvalidRequestException.class)
			.hasMessageStartingWith("document is not valid JSON or YAML: ");
		assertThatThrownBy(() -> converter.convert("x".repeat(ImportDocuments.MAX_CONTENT_LENGTH + 1)))
			.isInstanceOf(InvalidRequestException.class)
			.hasMessage("document must be at most " + ImportDocuments.MAX_CONTENT_LENGTH + " characters");
	}

	@Test
	void refusesYamlAliasBombs() {
		// 별칭이 기하급수적으로 펼쳐지는 문서 (billion laughs). 파서 한도로 확장 전에 거부
		StringBuilder bomb = new StringBuilder("a: &a [\"lol\",\"lol\",\"lol\",\"lol\",\"lol\",\"lol\",\"lol\",\"lol\",\"lol\"]\n");
		char previous = 'a';
		for (char name = 'b'; name <= 'i'; name++) {
			bomb.append(name).append(": &").append(name).append(" [");
			for (int i = 0; i < 9; i++) {
				bomb.append(i == 0 ? "" : ",").append('*').append(previous);
			}
			bomb.append("]\n");
			previous = name;
		}

		assertThatThrownBy(() -> converter.convert(bomb.toString())).isInstanceOf(InvalidRequestException.class)
			.hasMessageStartingWith("document is not valid JSON or YAML: ");
	}

	private CaseDraft secured(String source) {
		return converter.convert(resource("openapi/secured.yaml"))
			.cases()
			.stream()
			.filter(draft -> draft.source().equals(source))
			.findFirst()
			.orElseThrow();
	}

	private CaseDraft draft(String source) {
		return converter.convert(resource("openapi/petstore.yaml"))
			.cases()
			.stream()
			.filter(draft -> draft.source().equals(source))
			.findFirst()
			.orElseThrow();
	}

	private static String resource(String name) {
		try (InputStream in = OpenApiConverterTest.class.getClassLoader().getResourceAsStream(name)) {
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			throw new IllegalStateException(ex);
		}
	}

}
