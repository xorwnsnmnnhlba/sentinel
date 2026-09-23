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
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PostmanConverterTest {

	private final PostmanConverter converter = new PostmanConverter();

	@Test
	void convertsEveryRequestWithFoldersInTheName() {
		ConvertedDocument document = converter.convert(resource("postman/shop.postman_collection.json"));

		assertThat(document.title()).isEqualTo("Shop API");
		assertThat(document.version()).isNull();
		assertThat(document.cases()).extracting(CaseDraft::name)
			.containsExactly("Orders / List orders", "Orders / Get order", "Orders / Create order", "Admin / Reindex",
					"Admin / Public status", "Search", "Upload", "List orders");
		// 호스트는 참고용. 컬렉션 변수 값이 주소면 함께 표시
		assertThat(document.servers()).containsExactly("{{baseUrl}} (https://shop.example.com/api)",
				"https://admin.example.com");
		assertThat(document.warnings()).containsExactly(
				"COPY {{baseUrl}}/legacy: method COPY is not supported and was skipped",
				"add {{apiKey}} as a secret of the environment (API key)",
				"add {{cookie}} as a secret of the environment ('Cookie' header value)",
				"basic auth is sent as 'Authorization: Basic {{basicAuth}}'; add {{basicAuth}} as a secret holding base64 of 'username:password'",
				"add {{authorization}} as a secret of the environment ('Authorization' header value)",
				"collection variables are not imported; define them in the environment: baseUrl, token");
	}

	@Test
	void keepsPathAndEnabledQueryWithoutTheHost() {
		CaseDraft list = draft("Orders / List orders");

		assertThat(list.source()).isEqualTo("GET {{baseUrl}}/orders?status=paid&page=1");
		assertThat(list.request().method()).isEqualTo(HttpMethod.GET);
		// 꺼 둔 query·헤더는 생략
		assertThat(list.request().path()).isEqualTo("/orders?status=paid");
		// 컬렉션 인증 상속. 이미 변수라 그대로
		assertThat(list.request().headers()).containsExactly(Map.entry("Accept", List.of("application/json")),
				Map.entry("Authorization", List.of("Bearer {{token}}")));
		// 저장된 예시 응답의 2xx 코드
		assertThat(list.assertions()).containsExactly(new StatusCodeAssertion(List.of("200")));
		assertThat(list.warnings()).isEmpty();

		// URL만 적은 요청은 GET
		assertThat(draft("List orders").request().path()).isEqualTo("/orders");
	}

	@Test
	void fillsPathVariablesAndWarnsAboutScripts() {
		CaseDraft get = draft("Orders / Get order");

		// 값이 있는 경로 변수는 값, 없으면 변수
		assertThat(get.request().path()).isEqualTo("/orders/42/items/{{itemId}}");
		assertThat(get.assertions()).containsExactly(new StatusCodeAssertion(List.of("2xx")));
		// 내용 없는 스크립트는 경고하지 않음
		assertThat(get.warnings()).containsExactly("test script is not supported; add assertions instead");
	}

	@Test
	void convertsRawUrlencodedAndGraphqlBodies() {
		CaseDraft create = draft("Orders / Create order");
		assertThat(create.request().body()).isEqualTo("{\n  \"item\": \"{{itemId}}\",\n  \"quantity\": 1\n}");
		assertThat(create.request().headers()).containsEntry("Content-Type", List.of("application/json"));
		assertThat(create.assertions()).containsExactly(new StatusCodeAssertion(List.of("201")));

		// 폼 값은 인코딩하되 변수는 그대로
		CaseDraft reindex = draft("Admin / Reindex");
		assertThat(reindex.request().body()).isEqualTo("scope=all+items&requestedBy={{user}}");
		assertThat(reindex.request().headers()).containsEntry("Content-Type",
				List.of("application/x-www-form-urlencoded"));
		assertThat(reindex.request().path()).isEqualTo("/reindex");

		CaseDraft search = draft("Search");
		assertThat(search.request().body()).isEqualTo("""
				{
				  "query": "query { products { id } }",
				  "variables": {
				    "first": 10
				  }
				}""");
		assertThat(search.request().headers()).containsEntry("Content-Type", List.of("application/json"));

		CaseDraft upload = draft("Upload");
		assertThat(upload.request().body()).isNull();
		assertThat(upload.request().path()).isEqualTo("/files/");
	}

	@Test
	void inheritsAuthAndNeverKeepsLiteralCredentials() {
		// 폴더 인증(apiKey) 상속, 평문 값과 직접 적은 Cookie는 변수로
		CaseDraft reindex = draft("Admin / Reindex");
		assertThat(reindex.request().headers()).containsEntry("X-Admin-Key", List.of("{{apiKey}}"))
			.containsEntry("Cookie", List.of("{{cookie}}"));
		assertThat(reindex.warnings()).containsExactly("literal API key was replaced with {{apiKey}}",
				"literal 'Cookie' header value was replaced with {{cookie}}");

		// noauth는 상속을 끊음
		assertThat(draft("Admin / Public status").request().headers()).isEmpty();

		// Basic은 인코딩이 필요해 항상 변수. 직접 적은 Authorization 헤더보다 인증 설정 우선
		CaseDraft search = draft("Search");
		assertThat(search.request().headers()).containsEntry("Authorization", List.of("Basic {{basicAuth}}"));
		assertThat(search.warnings()).isEmpty();

		// 지원하지 않는 인증은 경고, 직접 적은 평문 Authorization은 스킴을 남기고 값만 변수로
		CaseDraft upload = draft("Upload");
		assertThat(upload.request().headers()).containsExactly(Map.entry("Authorization", List.of("Token {{authorization}}")));
		assertThat(upload.warnings()).containsExactly("request body mode 'formdata' is not supported; body left empty",
				"auth type 'digest' is not supported",
				"literal 'Authorization' header value was replaced with {{authorization}}");

		// 평문 자격증명과 컬렉션 변수 값은 결과 어디에도 없음
		String everything = JsonMapper.builder().build()
			.writeValueAsString(converter.convert(resource("postman/shop.postman_collection.json")));
		assertThat(everything).doesNotContain("do-not-show-this-token", "literal-admin-key", "abc123", "p4ss-w0rd-literal",
				"plain-token-value", "should-be-overwritten");
	}

	@Test
	void readsVersion2Collections() {
		// v2.0: 인증 값은 객체, 버전은 객체, 헤더는 문자열도 허용
		ConvertedDocument document = converter.convert("""
				{
				  "info": {"name": "Old", "version": {"major": 1, "minor": 2, "patch": 3},
				           "schema": "https://schema.getpostman.com/json/collection/v2.0.0/collection.json"},
				  "item": [{
				    "name": "Ping",
				    "request": {
				      "method": "get",
				      "header": "Accept: text/plain\\nX-Trace: {{trace}}",
				      "auth": {"type": "bearer", "bearer": {"token": "{{jwt}}"}},
				      "url": "http://localhost:8080/ping?verbose"
				    }
				  }]
				}""");

		assertThat(document.version()).isEqualTo("1.2.3");
		assertThat(document.servers()).containsExactly("http://localhost:8080");
		assertThat(document.cases()).singleElement().satisfies(ping -> {
			assertThat(ping.request().method()).isEqualTo(HttpMethod.GET);
			assertThat(ping.request().path()).isEqualTo("/ping?verbose");
			assertThat(ping.request().headers()).containsExactly(Map.entry("Accept", List.of("text/plain")),
					Map.entry("X-Trace", List.of("{{trace}}")), Map.entry("Authorization", List.of("Bearer {{jwt}}")));
		});
		assertThat(document.warnings()).isEmpty();
	}

	@Test
	void rejectsDocumentsThatAreNotCollectionV2() {
		assertThatThrownBy(() -> converter.convert("""
				{"id": "e1", "name": "dev", "values": [{"key": "token", "value": "x"}], "_postman_variable_scope": "environment"}"""))
			.isInstanceOf(InvalidRequestException.class)
			.hasMessage("this is a Postman environment, not a collection");
		assertThatThrownBy(() -> converter.convert("""
				{"id": "c1", "name": "v1", "order": [], "requests": []}"""))
			.isInstanceOf(InvalidRequestException.class)
			.hasMessage("only Postman Collection v2.0 and v2.1 are supported (found v1); export the collection again");
		assertThatThrownBy(() -> converter.convert("openapi: 3.0.0\npaths: {}"))
			.isInstanceOf(InvalidRequestException.class)
			.hasMessage("document is not a Postman collection (info.schema is missing)");
		assertThatThrownBy(() -> converter.convert("""
				{"info": {"name": "x", "schema": "https://schema.getpostman.com/json/collection/v3.0.0/collection.json"}, "item": []}"""))
			.isInstanceOf(InvalidRequestException.class)
			.hasMessageStartingWith("only Postman Collection v2.0 and v2.1 are supported (found schema ");
		assertThatThrownBy(() -> converter.convert("""
				{"info": {"name": "x", "schema": "https://schema.getpostman.com/json/collection/v2.1.0/collection.json"},
				 "item": [{"name": "empty folder", "item": []}]}"""))
			.isInstanceOf(InvalidRequestException.class)
			.hasMessage("collection defines no supported requests");
	}

	private CaseDraft draft(String name) {
		return converter.convert(resource("postman/shop.postman_collection.json"))
			.cases()
			.stream()
			.filter(draft -> draft.name().equals(name))
			.findFirst()
			.orElseThrow();
	}

	private static String resource(String name) {
		try (InputStream in = PostmanConverterTest.class.getClassLoader().getResourceAsStream(name)) {
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			throw new IllegalStateException(ex);
		}
	}

}
