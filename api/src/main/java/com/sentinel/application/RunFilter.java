package com.sentinel.application;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import com.sentinel.domain.TestResult;
import com.sentinel.domain.TestRun;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.jpa.domain.Specification;

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
	 * 지정한 조건만 SQL에 포함. null 파라미터를 넘기면 PostgreSQL이 타입을 추론하지 못하므로 조건 자체를 만들지 않음
	 *
	 * @param zone 기간 조건 해석 시간대
	 */
	Specification<TestRun> toSpecification(ZoneId zone) {
		return (root, query, builder) -> {
			List<Predicate> conditions = new ArrayList<>();
			if (projectId != null) {
				conditions.add(builder.equal(root.get("suite").get("project").get("id"), projectId));
			}
			if (suiteId != null) {
				conditions.add(builder.equal(root.get("suite").get("id"), suiteId));
			}
			if (environmentId != null) {
				conditions.add(builder.equal(root.get("environment").get("id"), environmentId));
			}
			if (status != null) {
				conditions.add(builder.equal(root.get("status"), status));
			}
			if (from != null) {
				conditions.add(builder.greaterThanOrEqualTo(root.get("startedAt"), fromInstant(zone)));
			}
			if (to != null) {
				// 종료일을 포함하도록 다음 날 00:00을 상한(미포함)으로 사용
				conditions.add(builder.lessThan(root.get("startedAt"), toInstant(zone)));
			}
			if (failing) {
				// 결과 행 수만큼 실행이 중복되지 않도록 조인 대신 EXISTS
				Subquery<Long> failed = query.subquery(Long.class);
				Root<TestResult> result = failed.from(TestResult.class);
				failed.select(result.get("id"))
					.where(builder.equal(result.get("run"), root),
							result.get("verdict").in(TestResult.Verdict.FAILED, TestResult.Verdict.ERROR));
				conditions.add(builder.exists(failed));
			}
			return builder.and(conditions.toArray(Predicate[]::new));
		};
	}

	Instant fromInstant(ZoneId zone) {
		return from.atStartOfDay(zone).toInstant();
	}

	Instant toInstant(ZoneId zone) {
		return to.plusDays(1).atStartOfDay(zone).toInstant();
	}

}
