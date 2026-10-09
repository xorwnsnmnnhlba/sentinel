package com.sentinel.application;

import com.sentinel.domain.Environment;
import com.sentinel.domain.Project;
import com.sentinel.domain.TestCase;
import com.sentinel.domain.TestSuite;

/**
 * 응답의 관계 표기. 다른 리소스를 가리킬 때는 ID만이 아니라 이름을 함께 (docs/design.md "REST API 설계")
 */
public record IdName(long id, String name) {

	static IdName of(Project project) {
		return new IdName(project.getId(), project.getName());
	}

	static IdName of(Environment environment) {
		return new IdName(environment.getId(), environment.getName());
	}

	static IdName of(TestSuite suite) {
		return new IdName(suite.getId(), suite.getName());
	}

	static IdName of(TestCase testCase) {
		return new IdName(testCase.getId(), testCase.getName());
	}

}
