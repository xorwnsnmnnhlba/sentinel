package com.sentinel.application.importing;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import com.sentinel.domain.RequestSpec;
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
		assertThat(messages(document.warnings())).containsExactly(
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
		assertThat(messages(list.warnings())).isEmpty();

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
		assertThat(messages(get.warnings())).containsExactly("test script is not supported; add assertions instead");
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
		assertThat(messages(reindex.warnings())).containsExactly("literal API key was replaced with {{apiKey}}",
				"literal 'Cookie' header value was replaced with {{cookie}}");

		// noauth는 상속을 끊음
		assertThat(draft("Admin / Public status").request().headers()).isEmpty();

		// Basic은 인코딩이 필요해 항상 변수. 직접 적은 Authorization 헤더보다 인증 설정 우선
		CaseDraft search = draft("Search");
		assertThat(search.request().headers()).containsEntry("Authorization", List.of("Basic {{basicAuth}}"));
		assertThat(messages(search.warnings())).isEmpty();

		// 지원하지 않는 인증은 경고, 직접 적은 평문 Authorization은 스킴을 남기고 값만 변수로
		CaseDraft upload = draft("Upload");
		assertThat(upload.request().headers()).containsExactly(Map.entry("Authorization", List.of("Token {{authorization}}")));
		assertThat(messages(upload.warnings())).containsExactly("request body mode 'formdata' is not supported; body left empty",
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
		assertThat(messages(document.warnings())).isEmpty();
	}

	@Test
	void sendsOAuth2AndQueryApiKeysAsVariables() {
		ConvertedDocument document = collection("""
				{"name": "OAuth", "request": {"method": "GET", "url": {"raw": "{{baseUrl}}/me", "host": ["{{baseUrl}}"], "path": ["me"]},
				  "auth": {"type": "oauth2", "oauth2": [{"key": "accessToken", "value": "{{accessToken}}"}]}}},
				{"name": "Key in query", "request": {"method": "GET",
				  "url": {"raw": "{{baseUrl}}/items?page=1", "host": ["{{baseUrl}}"], "path": ["items"], "query": [{"key": "page", "value": "1"}]},
				  "auth": {"type": "apikey", "apikey": [{"key": "key", "value": "tenant"}, {"key": "value", "value": "{{tenant}}"}, {"key": "in", "value": "query"}]}}},
				{"name": "Key without name", "request": {"method": "GET", "url": {"raw": "{{baseUrl}}/x", "host": ["{{baseUrl}}"], "path": ["x"]},
				  "auth": {"type": "apikey", "apikey": [{"key": "value", "value": "{{k}}"}]}}}""");

		assertThat(document.cases().get(0).request().headers())
			.containsExactly(Map.entry("Authorization", List.of("Bearer {{accessToken}}")));
		assertThat(document.cases().get(1).request().path()).isEqualTo("/items?page=1&tenant={{tenant}}");
		assertThat(document.cases().get(1).request().headers()).isEmpty();
		assertThat(document.cases().get(2).request().headers()).isEmpty();
		assertThat(messages(document.cases().get(2).warnings())).containsExactly("API key auth has no key name and was left out");
	}

	@Test
	void setsTheContentTypeOfRawBodiesByLanguage() {
		ConvertedDocument document = collection("""
				{"name": "Xml", "request": {"method": "POST", "url": "https://api.example.com/xml",
				  "body": {"mode": "raw", "raw": "<order/>", "options": {"raw": {"language": "xml"}}}}},
				{"name": "Text", "request": {"method": "POST", "url": "https://api.example.com/text",
				  "body": {"mode": "raw", "raw": "ping", "options": {"raw": {"language": "text"}}}}},
				{"name": "Html", "request": {"method": "POST", "url": "https://api.example.com/html",
				  "body": {"mode": "raw", "raw": "<p/>", "options": {"raw": {"language": "html"}}}}},
				{"name": "Empty", "request": {"method": "POST", "url": "https://api.example.com/empty",
				  "body": {"mode": "raw", "raw": ""}}},
				{"name": "Form off", "request": {"method": "POST", "url": "https://api.example.com/form",
				  "body": {"mode": "urlencoded", "urlencoded": [{"key": "a", "value": "1", "disabled": true}]}}}""");

		assertThat(document.cases()).extracting(draft -> draft.request().body())
			.containsExactly("<order/>", "ping", "<p/>", null, null);
		assertThat(document.cases()).extracting(draft -> draft.request().headers().get("Content-Type"))
			.containsExactly(List.of("application/xml"), List.of("text/plain"), null, null, null);
	}

	@Test
	void warnsAboutBodiesItCannotKeep() {
		ConvertedDocument document = collection("""
				{"name": "Bad variables", "request": {"method": "POST", "url": "https://api.example.com/graphql",
				  "body": {"mode": "graphql", "graphql": {"query": "{ me { id } }", "variables": "{not json"}}}},
				{"name": "Huge", "request": {"method": "POST", "url": "https://api.example.com/huge",
				  "body": {"mode": "raw", "raw": "%s"}}}""".formatted("x".repeat(RequestSpec.MAX_BODY_LENGTH + 1)));

		CaseDraft graphql = document.cases().get(0);
		assertThat(graphql.request().body()).contains("\"query\"").doesNotContain("variables");
		assertThat(messages(graphql.warnings())).containsExactly("GraphQL variables are not valid JSON and were left out");
		assertThat(document.cases().get(1).request().body()).isNull();
		assertThat(messages(document.cases().get(1).warnings()))
			.containsExactly("request body exceeds " + RequestSpec.MAX_BODY_LENGTH + " characters; body left empty");
	}

	@Test
	void readsUrlsWrittenInOtherShapes() {
		ConvertedDocument document = collection("""
				{"name": "Port", "request": {"method": "GET",
				  "url": {"protocol": "https", "host": "api.example.com", "port": "8443", "path": "v1/health"}}},
				{"name": "Segments", "request": {"method": "GET",
				  "url": {"host": ["api", "example", "com"], "path": ["orders", {"type": "string", "value": "recent"}]}}},
				{"name": "No url", "request": {"method": "GET"}},
				{"name": "Host only", "request": {"method": "GET", "url": {"raw": "/status", "path": ["status"]}}}""");

		assertThat(document.cases()).extracting(draft -> draft.request().path())
			.containsExactly("/v1/health", "/orders/recent", "/", "/status");
		// 프로토콜·포트가 있으면 함께, 호스트가 없으면 서버 목록에 넣지 않음
		assertThat(document.servers()).containsExactly("https://api.example.com:8443", "api.example.com");
	}

	@Test
	void fillsInMissingNamesAndMethodsAndWarnsAboutEveryScript() {
		ConvertedDocument document = collection("""
				{"item": [{"name": "Health", "request": {"url": "https://api.example.com/health"},
				  "event": [{"listen": "test", "script": {"exec": "pm.test('ok')"}},
				            {"listen": "prerequest", "script": {"exec": ["   "]}}]}]},
				{"request": {"method": "DELETE", "url": "https://api.example.com/cache"}}""");

		assertThat(document.cases()).extracting(CaseDraft::name)
			.containsExactly("(folder) / Health", "DELETE https://api.example.com/cache");
		assertThat(document.cases().get(0).request().method()).isEqualTo(HttpMethod.GET);
		// 내용이 있는 스크립트만 경고
		assertThat(messages(document.cases().get(0).warnings())).containsExactly("test script is not supported; add assertions instead");
	}

	@Test
	void keepsAContentTypeTheRequestAlreadySets() {
		ConvertedDocument document = collection("""
				{"name": "Vendor json", "request": {"method": "POST", "url": "https://api.example.com/orders",
				  "header": [{"key": "content-type", "value": "application/vnd.api+json"}],
				  "body": {"mode": "raw", "raw": "{}", "options": {"raw": {"language": "json"}}}}}""");

		assertThat(document.cases().getFirst().request().headers())
			.containsExactly(Map.entry("content-type", List.of("application/vnd.api+json")));
	}

	@Test
	void leavesOutADisabledBodyAndKeepsOneUpToTheLengthLimit() {
		String exact = "x".repeat(RequestSpec.MAX_BODY_LENGTH);
		ConvertedDocument document = collection("""
				{"name": "Off", "request": {"method": "POST", "url": "https://api.example.com/a",
				  "body": {"mode": "raw", "raw": "ping", "disabled": true}}},
				{"name": "Fits", "request": {"method": "POST", "url": "https://api.example.com/b",
				  "body": {"mode": "raw", "raw": "%s"}}}""".formatted(exact));

		assertThat(document.cases()).extracting(draft -> draft.request().body()).containsExactly(null, exact);
	}

	@Test
	void readsAQueryRightAfterTheHostAndIgnoresHeaderLinesWithoutAName() {
		ConvertedDocument document = converter.convert("""
				{
				  "info": {"name": "Old", "schema": "https://schema.getpostman.com/json/collection/v2.0.0/collection.json"},
				  "item": [{"name": "Search", "request": {"method": "GET", "header": ": no name\\nAccept: text/plain",
				            "url": "https://api.example.com?q=1"}}]
				}""");

		CaseDraft search = document.cases().getFirst();
		assertThat(search.request().path()).isEqualTo("/?q=1");
		assertThat(search.request().headers()).containsExactly(Map.entry("Accept", List.of("text/plain")));
	}

	@Test
	void showsAVariableHostWithoutAValueThatIsNotAnAddress() {
		ConvertedDocument document = converter.convert("""
				{"info": {"name": "Hosts", "schema": "https://schema.getpostman.com/json/collection/v2.1.0/collection.json"},
				 "variable": [{"key": "baseUrl", "value": "localhost:8080"}],
				 "item": [{"name": "Ping", "request": {"method": "GET",
				   "url": {"raw": "{{baseUrl}}/ping", "host": ["{{baseUrl}}"], "path": ["ping"]}}}]}""");

		assertThat(document.servers()).containsExactly("{{baseUrl}}");
	}

	@Test
	void judgesOnlyBySavedResponsesInThe2xxRange() {
		ConvertedDocument document = collection("""
				{"name": "Redirect", "request": {"method": "GET", "url": "https://api.example.com/old"},
				 "response": [{"name": "moved", "code": 300}]}""");

		assertThat(document.cases().getFirst().assertions()).containsExactly(new StatusCodeAssertion(List.of("2xx")));
	}

	@Test
	void sendsABearerTokenVariableWhenTheTokenIsMissing() {
		ConvertedDocument document = collection("""
				{"name": "No token", "request": {"method": "GET", "url": "https://api.example.com/me",
				  "auth": {"type": "bearer"}}}""");

		assertThat(document.cases().getFirst().request().headers())
			.containsExactly(Map.entry("Authorization", List.of("Bearer {{bearerToken}}")));
		// 적힌 토큰이 없으므로 "평문을 바꿨다"는 경고 없이 등록 안내만
		assertThat(messages(document.cases().getFirst().warnings())).isEmpty();
		assertThat(messages(document.warnings())).contains("add {{bearerToken}} as a secret of the environment (bearer token)"
				+ ", or if a login request issues it, extract it into {{bearerToken}} in an earlier case");
	}

	@Test
	void rejectsDocumentsThatAreNotCollectionV2() {
		assertThatThrownBy(() -> converter.convert("""
				{"id": "e1", "name": "dev", "values": [{"key": "token", "value": "x"}], "_postman_variable_scope": "environment"}"""))
			.isInstanceOf(ImportDocumentException.class)
			.hasMessage("this is a Postman environment, not a collection");
		// 예전 내보내기에는 _postman_variable_scope가 없음
		assertThatThrownBy(() -> converter.convert("""
				{"id": "e2", "name": "staging", "values": [{"key": "baseUrl", "value": "https://staging.example.com"}]}"""))
			.hasMessage("this is a Postman environment, not a collection");
		assertThatThrownBy(() -> converter.convert("""
				{"id": "c1", "name": "v1", "order": [], "requests": []}"""))
			.isInstanceOf(ImportDocumentException.class)
			.hasMessage("only Postman Collection v2.0 and v2.1 are supported (found v1); export the collection again");
		assertThatThrownBy(() -> converter.convert("openapi: 3.0.0\npaths: {}"))
			.isInstanceOf(ImportDocumentException.class)
			.hasMessage("document is not a Postman collection (info.schema is missing)");
		assertThatThrownBy(() -> converter.convert("""
				{"info": {"name": "x", "schema": "https://schema.getpostman.com/json/collection/v3.0.0/collection.json"}, "item": []}"""))
			.isInstanceOf(ImportDocumentException.class)
			.hasMessageStartingWith("only Postman Collection v2.0 and v2.1 are supported (found schema ");
		assertThatThrownBy(() -> converter.convert("""
				{"info": {"name": "x", "schema": "https://schema.getpostman.com/json/collection/v2.1.0/collection.json"},
				 "item": [{"name": "empty folder", "item": []}]}"""))
			.isInstanceOf(ImportDocumentException.class)
			.hasMessage("collection defines no supported requests");
	}

	private ConvertedDocument collection(String items) {
		return converter.convert("""
				{"info": {"name": "Inline", "schema": "https://schema.getpostman.com/json/collection/v2.1.0/collection.json"},
				 "item": [%s]}""".formatted(items));
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

	private static List<String> messages(List<ImportWarning> warnings) {
		return warnings.stream().map(ImportWarning::message).toList();
	}

}
