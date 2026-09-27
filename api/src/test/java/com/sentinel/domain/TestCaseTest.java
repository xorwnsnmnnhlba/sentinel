package com.sentinel.domain;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;

import com.sentinel.runner.HttpMethod;
import com.sentinel.runner.assertion.Assertion;
import com.sentinel.runner.assertion.BodyContainsAssertion;
import com.sentinel.runner.assertion.StatusCodeAssertion;
import com.sentinel.runner.extract.Extractor;
import com.sentinel.runner.extract.Extractor.Source;
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

	@Test
	void keepsExtractorsWithUniqueNames() {
		Extractor token = new Extractor("token", Source.BODY, "$.token", true);
		TestCase testCase = new TestCase(suite, "login", request, List.of(), List.of(token));

		assertThat(testCase.getExtractors()).containsExactly(token);
		assertThatThrownBy(() -> testCase.getExtractors().add(token)).isInstanceOf(UnsupportedOperationException.class);

		testCase.changeExtractors(null);
		assertThat(testCase.getExtractors()).isEmpty();
		assertThat(new TestCase(suite, "plain", request).getExtractors()).isEmpty();
	}

	@Test
	void rejectsDuplicateNamesNullsAndTooManyExtractors() {
		TestCase testCase = new TestCase(suite, "login", request);
		List<Extractor> duplicated = List.of(new Extractor("id", Source.BODY, "$.id"),
				new Extractor("id", Source.HEADER, "X-Id"));
		List<Extractor> tooMany = IntStream.rangeClosed(0, TestCase.MAX_EXTRACTORS)
			.mapToObj(i -> new Extractor("v" + i, Source.BODY, "$.v"))
			.toList();

		assertThatThrownBy(() -> testCase.changeExtractors(duplicated)).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("'id' is duplicated");
		assertThatThrownBy(() -> testCase.changeExtractors(Arrays.asList((Extractor) null)))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> testCase.changeExtractors(tooMany)).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("at most 20");
		// 한도까지는 허용
		testCase.changeExtractors(tooMany.subList(0, TestCase.MAX_EXTRACTORS));
		assertThat(testCase.getExtractors()).hasSize(TestCase.MAX_EXTRACTORS);
	}

	@Test
	void startsFirstAndRejectsNegativeRunOrders() {
		TestCase testCase = new TestCase(suite, "login", request);

		assertThat(testCase.getRunOrder()).isZero();
		testCase.placeAt(3);
		assertThat(testCase.getRunOrder()).isEqualTo(3);
		assertThatThrownBy(() -> testCase.placeAt(-1)).isInstanceOf(IllegalArgumentException.class);
	}

}
