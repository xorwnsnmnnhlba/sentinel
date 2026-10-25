package com.sentinel.domain;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

class BaseEntityTest {

	@Test
	void comparesByInstanceBeforeSavingAndByIdAfter() {
		Project a = new Project("a", null);
		Project b = new Project("b", null);

		assertThat(BaseEntity.same(a, a)).isTrue();
		assertThat(BaseEntity.same(null, null)).isTrue();
		assertThat(BaseEntity.same(a, null)).isFalse();
		assertThat(BaseEntity.same(null, a)).isFalse();
		// 저장 전(ID 없음)은 다른 인스턴스면 다름
		assertThat(BaseEntity.same(a, b)).isFalse();

		ReflectionTestUtils.setField(a, "id", 7L);
		assertThat(BaseEntity.same(a, b)).isFalse();
		ReflectionTestUtils.setField(b, "id", 7L);
		assertThat(BaseEntity.same(a, b)).isTrue();
		ReflectionTestUtils.setField(b, "id", 8L);
		assertThat(BaseEntity.same(a, b)).isFalse();
	}

}
