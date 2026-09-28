package com.sentinel.application;

import java.util.List;
import java.util.Set;

import com.sentinel.application.RerunSelection.Step;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 실패 다시 실행 대상: 실패한 케이스 + 그 케이스가 쓰는 추출 값을 만드는 앞 케이스 */
class RerunSelectionTest {

	@Test
	void selectsOnlyFailedCasesWhenTheyUseNoExtractedValues() {
		List<Step> steps = List.of(step(1, Set.of(), Set.of()), step(2, Set.of("baseToken"), Set.of()),
				step(3, Set.of(), Set.of()));

		// 추출하는 케이스가 없는 변수(환경 변수)는 무시
		assertThat(RerunSelection.select(steps, Set.of(2L))).containsExactly(2L);
	}

	@Test
	void addsTheEarlierCasesThatExtractTheValuesTheFailedCasesUse() {
		List<Step> steps = List.of(step(1, Set.of(), Set.of("token")), step(2, Set.of("token"), Set.of("orderId")),
				step(3, Set.of(), Set.of()), step(4, Set.of("token", "orderId"), Set.of()));

		// 조회(4)가 등록(2)의 orderId를, 등록은 로그인(1)의 token을 씀 (연쇄)
		assertThat(RerunSelection.select(steps, Set.of(4L))).containsExactlyInAnyOrder(1L, 2L, 4L);
	}

	@Test
	void usesOnlyTheNearestEarlierCaseThatExtractsTheValue() {
		List<Step> steps = List.of(step(1, Set.of(), Set.of("token")), step(2, Set.of(), Set.of("token")),
				step(3, Set.of("token"), Set.of()));

		// 실제 실행과 같이 바로 앞 케이스의 값이 쓰이므로 1은 불필요
		assertThat(RerunSelection.select(steps, Set.of(3L))).containsExactlyInAnyOrder(2L, 3L);
	}

	@Test
	void ignoresCasesThatExtractAfterTheCaseThatUsesTheValue() {
		List<Step> steps = List.of(step(1, Set.of("token"), Set.of()), step(2, Set.of(), Set.of("token")));

		assertThat(RerunSelection.select(steps, Set.of(1L))).containsExactly(1L);
	}

	@Test
	void looksFurtherBackForValuesACaseUsesToExtractTheSameName() {
		// 갱신(2)은 로그인(1)의 token을 보내고 새 token을 추출, 조회(3)는 새 token 사용
		List<Step> steps = List.of(step(1, Set.of(), Set.of("token")), step(2, Set.of("token"), Set.of("token")),
				step(3, Set.of("token"), Set.of()));

		assertThat(RerunSelection.select(steps, Set.of(3L))).containsExactlyInAnyOrder(1L, 2L, 3L);
	}

	@Test
	void selectsNothingWithoutFailedCases() {
		assertThat(RerunSelection.select(List.of(step(1, Set.of(), Set.of("token"))), Set.of())).isEmpty();
	}

	private static Step step(long caseId, Set<String> uses, Set<String> extracts) {
		return new Step(caseId, uses, extracts);
	}

}
