package com.sentinel.application;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 실패 다시 실행 대상 선정. 원래 실행에서 실패·오류가 난 케이스와, 그 케이스가 쓰는 추출 값을 만드는 앞 케이스(연쇄)
 * <p>
 * 실행 순서의 뒤에서부터 거슬러 가며 선택한 케이스가 쓰는 변수를 모으고, 그 변수를 추출하는 케이스를 만나면 함께 선택.
 * 같은 변수를 여러 케이스가 추출하면 실제 실행과 같이 바로 앞 케이스의 값을 쓰므로 가장 가까운 앞 케이스만 선택.
 * 추출하는 케이스가 없는 변수(환경 변수)는 무시
 */
final class RerunSelection {

	private RerunSelection() {
	}

	/**
	 * @param steps 스위트의 케이스 (실행 순서)
	 * @param failedCaseIds 원래 실행에서 실패·오류가 난 케이스
	 * @return 다시 실행할 케이스 ID
	 */
	static Set<Long> select(List<Step> steps, Set<Long> failedCaseIds) {
		Set<Long> selected = new HashSet<>();
		// 뒤에서 선택한 케이스가 쓰는데 아직 추출하는 케이스를 찾지 못한 변수
		Set<String> needed = new HashSet<>();
		for (Step step : steps.reversed()) {
			boolean supplies = step.extracts().stream().anyMatch(needed::contains);
			if (!failedCaseIds.contains(step.caseId()) && !supplies) {
				continue;
			}
			selected.add(step.caseId());
			needed.removeAll(step.extracts());
			needed.addAll(step.uses());
		}
		return selected;
	}

	/**
	 * @param uses 요청(경로·헤더 값·본문)이 참조하는 변수
	 * @param extracts 추출 규칙으로 만드는 변수
	 */
	record Step(long caseId, Set<String> uses, Set<String> extracts) {
	}

}
