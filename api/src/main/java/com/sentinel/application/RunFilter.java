package com.sentinel.application;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.types.Predicate;
import com.sentinel.domain.QTestRun;
import com.sentinel.domain.TestRun;

/**
 * 실행 이력 조회 조건. null인 값은 조건에서 제외.
 *
 * @param from 시작일(포함). 지정한 시간대의 00:00부터
 * @param to 종료일(포함). 지정한 시간대의 다음 날 00:00 직전까지
 * @param failing true면 판정 실패·오류 결과가 하나 이상 있는 실행만
 */
public record RunFilter(Long projectId, Long suiteId, Long environmentId, TestRun.Status status, LocalDate from,
		LocalDate to, boolean failing) {

	public static RunFilter none() {
		return new RunFilter(null, null, null, null, null, null, false);
	}

	/**
	 * 지정한 조건만 SQL에 포함 (null인 조건은 제외)
	 *
	 * @param zone 기간 조건 해석 시간대
	 */
	Predicate toPredicate(ZoneId zone) {
		QTestRun run = QTestRun.testRun;
		BooleanBuilder conditions = new BooleanBuilder();
		if (projectId != null) {
			conditions.and(run.suite.project.id.eq(projectId));
		}
		if (suiteId != null) {
			conditions.and(run.suite.id.eq(suiteId));
		}
		if (environmentId != null) {
			conditions.and(run.environment.id.eq(environmentId));
		}
		if (status != null) {
			conditions.and(run.status.eq(status));
		}
		if (from != null) {
			conditions.and(run.startedAt.goe(fromInstant(zone)));
		}
		if (to != null) {
			// 종료일을 포함하도록 다음 날 00:00을 상한(미포함)으로 사용
			conditions.and(run.startedAt.lt(toInstant(zone)));
		}
		if (failing) {
			// 실행에 보관한 판정 수로 판단. 결과 테이블을 훑지 않음 (V10)
			conditions.and(run.failedCount.add(run.errorCount).gt(0));
		}
		return conditions;
	}

	Instant fromInstant(ZoneId zone) {
		return from.atStartOfDay(zone).toInstant();
	}

	Instant toInstant(ZoneId zone) {
		return to.plusDays(1).atStartOfDay(zone).toInstant();
	}

}
