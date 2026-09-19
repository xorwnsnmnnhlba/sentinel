package com.sentinel.domain;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.util.List;

import jakarta.persistence.Embeddable;
import jakarta.persistence.Entity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.util.ClassUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JPA 기본 생성자는 {@code @NoArgsConstructor(access = AccessLevel.PROTECTED)}. lombok.config로 접근 수준을 강제할 수 없어 테스트로 고정.
 * public이면 검증 없는 빈 엔티티 생성 가능
 */
class JpaDefaultConstructorTest {

	@Test
	void entitiesAndEmbeddablesHaveProtectedNoArgsConstructor() throws Exception {
		List<Class<?>> types = persistentTypes();

		assertThat(types).extracting(Class::getSimpleName).contains("Project", "TestRun", "RequestSpec");
		for (Class<?> type : types) {
			Constructor<?> constructor = type.getDeclaredConstructor();
			assertThat(Modifier.isProtected(constructor.getModifiers())).as(type.getSimpleName() + " no-args constructor")
				.isTrue();
		}
	}

	private static List<Class<?>> persistentTypes() {
		ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
		scanner.addIncludeFilter(new AnnotationTypeFilter(Entity.class));
		scanner.addIncludeFilter(new AnnotationTypeFilter(Embeddable.class));
		return scanner.findCandidateComponents("com.sentinel")
			.stream()
			.map(BeanDefinition::getBeanClassName)
			.<Class<?>>map(name -> ClassUtils.resolveClassName(name, null))
			.toList();
	}

}
