package com.sentinel.domain;

import java.time.Instant;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.ConstraintMode;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 실행 1회. 결과({@link TestResult})는 단방향 {@code @ManyToOne}으로만 참조, 리포지토리로 조회.
 */
@Entity
@Table(name = "test_run", indexes = { @Index(name = "idx_run_suite_time", columnList = "suite_id, started_at DESC"),
		@Index(name = "idx_run_raw_purge", columnList = "raw_purged_at, started_at") })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TestRun extends BaseEntity {

	public enum Trigger {
		MANUAL, SCHEDULED
	}

	public enum Status {
		RUNNING, COMPLETED,
		/** 실행 중 앱 종료로 중단. 재기동 시 기록 */
		ABORTED
	}

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "suite_id", nullable = false, foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT))
	private TestSuite suite;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "environment_id", nullable = false, foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT))
	private Environment environment;

	/** TRIGGER는 SQL 표준 예약어이므로 컬럼명 trigger_type */
	@Enumerated(EnumType.STRING)
	@Column(name = "trigger_type", nullable = false, length = 20)
	private Trigger trigger;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private Status status;

	@Column(nullable = false)
	private Instant startedAt;

	private Instant finishedAt;

	/** 수동 실행을 시작한 사용자. 스케줄 실행은 null */
	private Long triggeredBy;

	/** 케이스 단건 실행의 대상. 스위트 전체 실행은 null */
	private Long testCaseId;

	/** "여기까지 실행"의 대상 (V14). 스위트의 처음부터 이 케이스까지 실행. 그 외 실행은 null */
	private Long throughCaseId;

	/**
	 * 판정별 결과 수. 결과를 저장할 때마다 {@link #countVerdict}로 올림.
	 * 실행 목록의 실패·오류 조건과 스위트 집계가 결과 테이블을 훑지 않도록 실행에 함께 보관
	 */
	@Column(nullable = false)
	private int passedCount;

	@Column(nullable = false)
	private int failedCount;

	@Column(nullable = false)
	private int errorCount;

	/** 보관기간이 지나 결과의 요청·응답 원문을 비운 시각. null이면 원문 보관 중 */
	private Instant rawPurgedAt;

	private TestRun(TestSuite suite, Environment environment, Trigger trigger, Instant startedAt, Long triggeredBy,
			Long testCaseId, Long throughCaseId) {
		this.suite = Objects.requireNonNull(suite, "suite");
		this.environment = Objects.requireNonNull(environment, "environment");
		this.trigger = Objects.requireNonNull(trigger, "trigger");
		this.startedAt = Objects.requireNonNull(startedAt, "startedAt");
		this.status = Status.RUNNING;
		this.triggeredBy = triggeredBy;
		this.testCaseId = testCaseId;
		this.throughCaseId = throughCaseId;
	}

	public static TestRun start(TestSuite suite, Environment environment, Trigger trigger, Instant startedAt) {
		return new TestRun(suite, environment, trigger, startedAt, null, null, null);
	}

	/**
	 * @param triggeredBy 수동 실행을 시작한 사용자. 스케줄 실행은 null
	 */
	public static TestRun start(TestSuite suite, Environment environment, Trigger trigger, Instant startedAt,
			Long triggeredBy) {
		return new TestRun(suite, environment, trigger, startedAt, triggeredBy, null, null);
	}

	/**
	 * 케이스 단건 실행. 케이스가 속한 스위트의 실행으로 기록하되 대상 케이스를 남겨 스위트 전체 실행과 구분
	 * @param triggeredBy 실행을 시작한 사용자
	 */
	public static TestRun startCase(TestCase testCase, Environment environment, Trigger trigger, Instant startedAt,
			Long triggeredBy) {
		return new TestRun(testCase.getSuite(), environment, trigger, startedAt, triggeredBy,
				Objects.requireNonNull(testCase.getId(), "testCase.id"), null);
	}

	/**
	 * 여기까지 실행. 케이스가 속한 스위트의 실행으로 기록하되 마지막 케이스를 남겨 스위트 전체 실행과 구분
	 * @param through 마지막으로 실행할 케이스
	 */
	public static TestRun startThrough(TestCase through, Environment environment, Trigger trigger, Instant startedAt,
			Long triggeredBy) {
		return new TestRun(through.getSuite(), environment, trigger, startedAt, triggeredBy, null,
				Objects.requireNonNull(through.getId(), "through.id"));
	}

	/** 케이스 단건 실행 여부 */
	public boolean isSingleCase() {
		return testCaseId != null;
	}

	/** 여기까지 실행 여부 */
	public boolean isThroughCase() {
		return throughCaseId != null;
	}

	/** 결과 1건의 판정 반영. 결과 저장과 같은 트랜잭션에서 호출. 판정 없는 결과(null)는 세지 않음 */
	public void countVerdict(TestResult.Verdict verdict) {
		if (verdict == null) {
			return;
		}
		switch (verdict) {
			case PASSED -> passedCount++;
			case FAILED -> failedCount++;
			case ERROR -> errorCount++;
		}
	}

	public void complete(Instant finishedAt) {
		if (status != Status.RUNNING) {
			throw new IllegalStateException("run " + getId() + " is already " + status);
		}
		this.status = Status.COMPLETED;
		this.finishedAt = Objects.requireNonNull(finishedAt, "finishedAt");
	}

	/**
	 * 결과 원문 정리 기록. 원문 자체는 {@code TestResultRepository#purgeRawByRunIds}가 일괄로 비움
	 */
	public void markRawPurged(Instant purgedAt) {
		if (status == Status.RUNNING) {
			throw new IllegalStateException("run " + getId() + " is still running");
		}
		this.rawPurgedAt = Objects.requireNonNull(purgedAt, "purgedAt");
	}

	public void abort(Instant abortedAt) {
		if (status != Status.RUNNING) {
			throw new IllegalStateException("run " + getId() + " is already " + status);
		}
		this.status = Status.ABORTED;
		this.finishedAt = Objects.requireNonNull(abortedAt, "abortedAt");
	}

}
