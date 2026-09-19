package com.sentinel.domain;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.sentinel.runner.HttpMethod;
import com.sentinel.runner.assertion.Assertion;
import com.sentinel.runner.assertion.BodyContainsAssertion;
import com.sentinel.runner.assertion.StatusCodeAssertion;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestCaseTest {

	private final TestSuite suite = new TestSuite(new Project("billing", null), "charge", null);

	private final RequestSpec request = new RequestSpec(HttpMethod.GET, "/a", null, null);

	@Test
	void copiesAssertionsDefensively() {
		List<Assertion> assertions = new ArrayList<>(List.of(new StatusCodeAssertion(List.of("200"))));
		TestCase testCase = new TestCase(suite, "case", request, assertions);
		assertions.add(new BodyContainsAssertion("ok"));

		assertThat(testCase.getAssertions()).hasSize(1);
		// 직접 작성한 방어적 getter. Lombok @Getter로 바뀌면 내부 목록이 노출되어 실패
		assertThatThrownBy(() -> testCase.getAssertions().add(new BodyContainsAssertion("ok")))
				.isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	void rejectsNullAssertion() {
		assertThatThrownBy(() -> new TestCase(suite, "case", request, Arrays.asList((Assertion) null)))
				.isInstanceOf(IllegalArgumentException.class);
	}

}
