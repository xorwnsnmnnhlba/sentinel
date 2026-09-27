package com.sentinel.api;

import java.util.Objects;
import java.util.stream.Stream;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;

/**
 * 실행 생성 요청. suiteId(스위트 전체), caseId(케이스 1건), throughCaseId(스위트 처음부터 이 케이스까지) 중 정확히 하나 지정.
 */
record CreateRunRequest(Long suiteId, Long caseId, Long throughCaseId, @NotNull Long environmentId) {

	@AssertTrue(message = "exactly one of suiteId, caseId or throughCaseId is required")
	boolean isExactlyOneTarget() {
		return Stream.of(suiteId, caseId, throughCaseId).filter(Objects::nonNull).count() == 1;
	}

}
