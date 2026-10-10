package com.sentinel.api;

import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.jayway.jsonpath.JsonPath;
import com.sentinel.TestAuthConfiguration;
import com.sentinel.TestcontainersConfiguration;
import com.sentinel.application.FakeRequestExecutorConfiguration;
import com.sentinel.application.TestFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import static org.assertj.core.api.Assertions.assertThat;

/** API 문서 ({@code GET /api/openapi}). 다른 API와 같이 로그인 필요 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({ TestcontainersConfiguration.class, FakeRequestExecutorConfiguration.class, TestFixtures.class,
		TestAuthConfiguration.class })
class OpenApiDocsTest {

	@Autowired
	private MockMvcTester mvc;

	@Test
	void describesTheApiWithKoreanGroupsAndDescriptions() {
		var docs = assertThat(mvc.get().uri("/api/openapi")).hasStatusOk().bodyJson();
		docs.extractingPath("$.info.title").isEqualTo("Sentinel API");
		docs.extractingPath("$.components.securitySchemes.bearer.scheme").isEqualTo("bearer");
		docs.extractingPath("$.tags[*].name")
			.isEqualTo(OpenApiConfig.TAGS.stream().map(io.swagger.v3.oas.models.tags.Tag::getName).toList());
		// 설명은 컨트롤러 Javadoc에서
		docs.extractingPath("$.paths['/api/holidays'].get.summary").isEqualTo("그해 휴일, 날짜 순.");
		docs.extractingPath("$.paths['/api/holidays'].get.tags").isEqualTo(List.of("휴일"));
		// 응답 필드 설명은 레코드 Javadoc(@param)에서
		docs.extractingPath("$.components.schemas.ChannelItem.properties.configured.description")
			.asString()
			.startsWith("주소가 있음");
	}

	@Test
	void everyOperationHasAKnownGroupAndADescription() {
		String body = body(mvc.get().uri("/api/openapi").exchange());
		List<String> tags = OpenApiConfig.TAGS.stream().map(io.swagger.v3.oas.models.tags.Tag::getName).toList();
		Map<String, Map<String, Map<String, Object>>> paths = JsonPath.read(body, "$.paths");
		List<String> undocumented = new ArrayList<>();
		paths.forEach((path, operations) -> operations.forEach((method, operation) -> {
			@SuppressWarnings("unchecked")
			List<String> operationTags = (List<String>) operation.get("tags");
			if (operation.get("summary") == null || operationTags == null || !tags.containsAll(operationTags)) {
				undocumented.add(method.toUpperCase() + " " + path);
			}
		}));
		// 새 API는 컨트롤러에 @Tag와 Javadoc을 달아야 함
		assertThat(undocumented).isEmpty();
	}

	@Test
	void marksOnlyRequestsBeforeLoginAsOpen() {
		String body = body(mvc.get().uri("/api/openapi").exchange());
		List<String> open = JsonPath.read(body, "$.paths[*][*][?(@.security && @.security.length() == 0)].operationId");

		assertThat(open).hasSize(5);
		assertThat(JsonPath.<List<Object>>read(body, "$.paths['/api/sessions'].post.security")).isEmpty();
		assertThat(JsonPath.<List<Object>>read(body, "$.paths['/api/users'].post.security")).isEmpty();
		assertThat(JsonPath.<Object>read(body, "$.paths['/api/projects'].get")).asString().doesNotContain("security=[]");
	}

	@Test
	void requiresLogin() {
		assertThat(mvc.get().uri("/api/openapi").header("Authorization", "Bearer not-a-session"))
			.hasStatus(HttpStatus.UNAUTHORIZED);
	}

	private static String body(MvcTestResult result) {
		try {
			return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
		}
		catch (UnsupportedEncodingException ex) {
			throw new IllegalStateException(ex);
		}
	}

}
