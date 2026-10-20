package com.sentinel.runner.assertion;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ValuesTest {

	@Test
	void usesADecimalAsIsAndComparesItWithOtherNumbersByValue() {
		BigDecimal decimal = new BigDecimal("1.50");

		assertThat(Values.toDecimal(decimal)).isSameAs(decimal);
		assertThat(Values.jsonEquals(decimal, 1.5)).isTrue();
		assertThat(Values.jsonEquals(new BigDecimal("1.0"), 1)).isTrue();
		assertThat(Values.jsonEquals(decimal, 2)).isFalse();
	}

	@Test
	void treatsObjectsWithDifferentKeysAsDifferentEvenWhenTheValuesAreNull() {
		// 응답 {"memo": null}과 기대값 {"note": null}. 없는 키의 값도 null이라 키 확인 없이는 같다고 봄
		Map<String, Object> actual = new HashMap<>();
		actual.put("memo", null);
		Map<String, Object> expected = new HashMap<>();
		expected.put("note", null);

		assertThat(Values.jsonEquals(actual, expected)).isFalse();
		assertThat(Values.jsonEquals(actual, new HashMap<>(actual))).isTrue();
	}

	@Test
	void namesValuesOutsideJsonByTheirClass() {
		assertThat(Values.typeOf(new StringBuilder("x"))).isEqualTo("StringBuilder");
	}

}
