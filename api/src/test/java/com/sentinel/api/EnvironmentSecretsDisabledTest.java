package com.sentinel.api;

import com.sentinel.TestAuthConfiguration;
import com.sentinel.TestcontainersConfiguration;
import com.sentinel.application.FakeRequestExecutor;
import com.sentinel.application.FakeRequestExecutorConfiguration;
import com.sentinel.application.TestFixtures;
import com.sentinel.domain.Project;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 암호화 키 미설정(기본값) 상태. 평문 저장을 막기 위해 시크릿 등록 자체를 거부
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({ TestcontainersConfiguration.class, FakeRequestExecutorConfiguration.class, TestFixtures.class,
		TestAuthConfiguration.class })
class EnvironmentSecretsDisabledTest {

	@Autowired
	private MockMvcTester mvc;

	@Autowired
	private FakeRequestExecutor executor;

	@Autowired
	private TestFixtures fixtures;

	@AfterEach
	void cleanUp() {
		fixtures.deleteAll();
		executor.reset();
	}

	@Test
	void refusesSecretsAndKeepsAcceptingVariables() {
		Project project = fixtures.project();

		assertThat(mvc.post()
			.uri("/api/environments")
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
					{"projectId": %d, "name": "dev", "baseUrl": "https://gw", "secrets": {"token": "value"}}"""
				.formatted(project.getId()))).hasStatus(HttpStatus.BAD_REQUEST)
			.bodyJson()
			.extractingPath("$.detail")
			.asString()
			.contains("SENTINEL_SECRET_KEY");

		assertThat(mvc.post()
			.uri("/api/environments")
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
					{"projectId": %d, "name": "dev", "baseUrl": "https://gw", "variables": {"userId": "42"}}"""
				.formatted(project.getId()))).hasStatus(HttpStatus.CREATED)
			.bodyJson()
			.extractingPath("$.secretNames")
			.asArray()
			.isEmpty();
	}

	@Test
	void showsSecretsAsDisabledInTheSystemStatus() {
		var json = assertThat(mvc.get().uri("/api/system-status")).hasStatusOk().bodyJson();
		json.extractingPath("$.secrets.state").isEqualTo("DISABLED");
		json.extractingPath("$.secrets.undecryptable").asArray().isEmpty();
		json.extractingPath("$.defaultChannel").isEqualTo(false);
		json.extractingPath("$.resultLinks").isEqualTo(false);
	}

}
