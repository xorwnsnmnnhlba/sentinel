package com.sentinel.api;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;

/**
 * 실행 생성 요청. suiteId(스위트 전체)와 caseId(케이스 1건) 중 정확히 하나 지정.
 */
public record CreateRunRequest(Long suiteId, Long caseId, @NotNull Long environmentId) {

	@AssertTrue(message = "exactly one of suiteId or caseId is required")
	public boolean isExactlyOneTarget() {
		return (suiteId == null) != (caseId == null);
	}

}
