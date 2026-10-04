package com.sentinel.application;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.sentinel.domain.Environment;
import com.sentinel.domain.SlowdownAlert;
import com.sentinel.domain.TestCase;
import com.sentinel.infra.persistence.EnvironmentRepository;
import com.sentinel.infra.persistence.SlowdownAlertRepository;
import com.sentinel.infra.persistence.TestCaseRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 지금 느린 케이스. 응답시간 악화 알림({@link SlowdownDetector})이 열어 두고 아직 회복하지 않은 것
 */
@Service
@RequiredArgsConstructor
public class SlowdownService {

	private final SlowdownAlertRepository alerts;

	private final TestCaseRepository testCases;

	private final EnvironmentRepository environments;

	/**
	 * @param suiteId 지정하면 그 스위트의 케이스만
	 * @return 감지 당시 늘어난 비율이 큰 순. 삭제된 케이스·환경 제외 (다음 확인에서 닫힘)
	 */
	@Transactional(readOnly = true)
	public List<Slowdown> listOpen(Long suiteId) {
		List<SlowdownAlert> open = alerts.findByResolvedAtIsNull();
		if (open.isEmpty()) {
			return List.of();
		}
		Map<Long, TestCase> cases = new HashMap<>();
		testCases.findActiveWithSuiteByIdIn(open.stream().map(SlowdownAlert::getTestCaseId).distinct().toList())
			.forEach(testCase -> cases.put(testCase.getId(), testCase));
		Map<Long, Environment> environmentsById = new HashMap<>();
		environments.findAllById(open.stream().map(SlowdownAlert::getEnvironmentId).distinct().toList())
			.stream()
			.filter(environment -> !environment.isArchived())
			.forEach(environment -> environmentsById.put(environment.getId(), environment));
		return open.stream()
			.filter(alert -> cases.containsKey(alert.getTestCaseId())
					&& environmentsById.containsKey(alert.getEnvironmentId()))
			.filter(alert -> suiteId == null || cases.get(alert.getTestCaseId()).getSuite().getId().equals(suiteId))
			.map(alert -> Slowdown.of(alert, cases.get(alert.getTestCaseId()),
					environmentsById.get(alert.getEnvironmentId())))
			.sorted(Comparator.comparingDouble(Slowdown::increase)
				.reversed()
				.thenComparing(slowdown -> slowdown.testCase().name()))
			.toList();
	}

	/**
	 * @param baselineP95 감지 당시 비교 기간 p95 (ms)
	 * @param detectedP95 감지 당시 최근 기간 p95 (ms)
	 * @param increase 감지 당시 늘어난 비율. 0.91은 91% 느려짐
	 * @param detectedAt 느려진 것을 감지한 시각
	 */
	public record Slowdown(long id, IdName testCase, IdName suite, IdName project, IdName environment, long baselineP95,
			long detectedP95, double increase, Instant detectedAt) {

		static Slowdown of(SlowdownAlert alert, TestCase testCase, Environment environment) {
			return new Slowdown(alert.getId(), new IdName(testCase.getId(), testCase.getName()),
					new IdName(testCase.getSuite().getId(), testCase.getSuite().getName()),
					new IdName(testCase.getSuite().getProject().getId(), testCase.getSuite().getProject().getName()),
					new IdName(environment.getId(), environment.getName()), alert.getBaselineP95(),
					alert.getDetectedP95(),
					(double) (alert.getDetectedP95() - alert.getBaselineP95()) / alert.getBaselineP95(),
					alert.getDetectedAt());
		}

	}

}
