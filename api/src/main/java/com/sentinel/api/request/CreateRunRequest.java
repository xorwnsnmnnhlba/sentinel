package com.sentinel.api.request;

import java.util.Objects;
import java.util.stream.Stream;

import jakarta.validation.constraints.AssertTrue;

/**
 * 실행 생성 요청. suiteId(스위트 전체), caseId(케이스 1건), throughCaseId(스위트 처음부터 이 케이스까지),
 * rerunOf(이 실행에서 실패한 케이스 다시 실행) 중 정확히 하나 지정.
 *
 * @param environmentId rerunOf 외에는 필수. rerunOf는 원래 실행의 환경을 쓰므로 지정하지 않음
 */
public record CreateRunRequest(Long suiteId, Long caseId, Long throughCaseId, Long rerunOf, Long environmentId) {

	@AssertTrue(message = "exactly one of suiteId, caseId, throughCaseId or rerunOf is required")
	boolean isExactlyOneTarget() {
		return Stream.of(suiteId, caseId, throughCaseId, rerunOf).filter(Objects::nonNull).count() == 1;
	}

	@AssertTrue(message = "environmentId is required, except with rerunOf which uses the environment of the original run")
	boolean isEnvironmentGivenOnlyWhenNeeded() {
		return (rerunOf == null) == (environmentId != null);
	}

}
