package com.sentinel.api;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.sentinel.TestAuthConfiguration;
import com.sentinel.TestcontainersConfiguration;
import com.sentinel.application.FakeRequestExecutorConfiguration;
import com.sentinel.application.TestFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * REST 설계 규칙 (docs/design.md "REST API 설계") 중 코드로 확인할 수 있는 것. 새 API가 규칙을 어기면 실패
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({ TestcontainersConfiguration.class, FakeRequestExecutorConfiguration.class, TestFixtures.class,
		TestAuthConfiguration.class })
class RestConventionTest {

	@Autowired
	@Qualifier("requestMappingHandlerMapping")
	private RequestMappingHandlerMapping mappings;

	@Test
	void everyResourceThatCanBeChangedByIdCanAlsoBeRead() {
		Map<String, Set<RequestMethod>> methods = routes();

		// 생성 응답의 Location과 수정·삭제 대상이 실제로 조회되는 주소여야 함
		List<String> unreadable = methods.entrySet()
			.stream()
			.filter(route -> route.getKey().matches(".*/\\{[^/]+}$"))
			.filter(route -> route.getValue().contains(RequestMethod.PATCH)
					|| route.getValue().contains(RequestMethod.PUT) || route.getValue().contains(RequestMethod.DELETE))
			.filter(route -> !route.getValue().contains(RequestMethod.GET))
			.map(Map.Entry::getKey)
			.sorted()
			.toList();
		assertThat(unreadable).isEmpty();
	}

	@Test
	void everyApiPathIsUnderApiAndUsesLowerCaseWordsJoinedByHyphens() {
		assertThat(routes().keySet()).allSatisfy(path -> assertThat(path)
			.matches("/api(/([a-z]+(-[a-z]+)*|\\{[a-zA-Z]+}))+"));
	}

	/** 경로별 메서드. API 경로만 */
	private Map<String, Set<RequestMethod>> routes() {
		Map<String, Set<RequestMethod>> routes = new HashMap<>();
		mappings.getHandlerMethods().keySet().forEach(info -> info.getPatternValues()
			.stream()
			.filter(path -> path.startsWith("/api/"))
			.forEach(path -> routes.computeIfAbsent(path, key -> new HashSet<>())
				.addAll(info.getMethodsCondition().getMethods())));
		return routes;
	}

}
