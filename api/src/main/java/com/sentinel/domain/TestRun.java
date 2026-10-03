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
		ABORTED,
		/** 사용자가 중지. 진행 중이던 케이스까지 기록하고 이후 케이스는 보내지 않음 */
		CANCELED
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
	 * 판정별 결과 수. 결과를 저장할 때마다 같은 트랜잭션에서 올림 ({@code TestRunRepository#incrementPassed} 등).
	 * 실행 목록의 실패·오류 조건과 스위트 집계가 결과 테이블을 훑지 않도록 실행에 함께 보관
	 */
	@Column(nullable = false)
	private int passedCount;

	@Column(nullable = false)
	private int failedCount;

	@Column(nullable = false)
	private int errorCount;

	/** 응답을 받지 못해 다시 보낸 결과 수 (V22). 판정 수와 같이 결과 저장 시 올림 */
	@Column(nullable = false)
	private int retriedCount;

	/** 실패 다시 실행의 원래 실행 (V18). 그 외 실행은 null */
	private Long rerunOfRunId;

	/** 실행을 중지한 사용자 (V16). 중지된 실행만 값이 있음 */
	private Long canceledBy;

	/** 실행할 케이스 수 (V15). 진행 중 화면의 진행률 표시용. 이전 실행은 null */
	private Integer plannedCount;

	/** 보관기간이 지나 결과의 요청·응답 원문을 비운 시각. null이면 원문 보관 중 */
	private Instant rawPurgedAt;

	/** 실패 알림 발송 시각 (V26). 계속 실패 중 재알림 간격 판단용. null이면 발송하지 않음 */
	private Instant failureNotifiedAt;

	private TestRun(TestSuite suite, Environment environment, Trigger trigger, Instant startedAt, Long triggeredBy,
			Long testCaseId, Long throughCaseId, Long rerunOfRunId) {
		this.suite = Objects.requireNonNull(suite, "suite");
		this.environment = Objects.requireNonNull(environment, "environment");
		this.trigger = Objects.requireNonNull(trigger, "trigger");
		this.startedAt = Objects.requireNonNull(startedAt, "startedAt");
		this.status = Status.RUNNING;
		this.triggeredBy = triggeredBy;
		this.testCaseId = testCaseId;
		this.throughCaseId = throughCaseId;
		this.rerunOfRunId = rerunOfRunId;
	}

	public static TestRun start(TestSuite suite, Environment environment, Trigger trigger, Instant startedAt) {
		return new TestRun(suite, environment, trigger, startedAt, null, null, null, null);
	}

	/**
	 * @param triggeredBy 수동 실행을 시작한 사용자. 스케줄 실행은 null
	 */
	public static TestRun start(TestSuite suite, Environment environment, Trigger trigger, Instant startedAt,
			Long triggeredBy) {
		return new TestRun(suite, environment, trigger, startedAt, triggeredBy, null, null, null);
	}

	/**
	 * 케이스 단건 실행. 케이스가 속한 스위트의 실행으로 기록하되 대상 케이스를 남겨 스위트 전체 실행과 구분
	 * @param triggeredBy 실행을 시작한 사용자
	 */
	public static TestRun startCase(TestCase testCase, Environment environment, Trigger trigger, Instant startedAt,
			Long triggeredBy) {
		return new TestRun(testCase.getSuite(), environment, trigger, startedAt, triggeredBy,
				Objects.requireNonNull(testCase.getId(), "testCase.id"), null, null);
	}

	/**
	 * 여기까지 실행. 케이스가 속한 스위트의 실행으로 기록하되 마지막 케이스를 남겨 스위트 전체 실행과 구분
	 * @param through 마지막으로 실행할 케이스
	 */
	public static TestRun startThrough(TestCase through, Environment environment, Trigger trigger, Instant startedAt,
			Long triggeredBy) {
		return new TestRun(through.getSuite(), environment, trigger, startedAt, triggeredBy, null,
				Objects.requireNonNull(through.getId(), "through.id"), null);
	}

	/**
	 * 실패 다시 실행. 원래 실행과 같은 스위트·환경의 수동 실행으로 기록하되 원래 실행을 남겨 스위트 전체 실행과 구분
	 * @param original 다시 실행할 실행 (끝난 실행)
	 */
	public static TestRun startRerun(TestRun original, Instant startedAt, Long triggeredBy) {
		if (original.status == Status.RUNNING) {
			throw new IllegalStateException("run " + original.getId() + " is still running");
		}
		return new TestRun(original.suite, original.environment, Trigger.MANUAL, startedAt, triggeredBy, null, null,
				Objects.requireNonNull(original.getId(), "original.id"));
	}

	/** 케이스 단건 실행 여부 */
	public boolean isSingleCase() {
		return testCaseId != null;
	}

	/** 여기까지 실행 여부 */
	public boolean isThroughCase() {
		return throughCaseId != null;
	}

	/** 실패 다시 실행 여부 */
	public boolean isRerun() {
		return rerunOfRunId != null;
	}

	/** 스위트 전체 실행 여부. 일부 케이스만 실행한 기록(단건·여기까지·실패 다시 실행)은 스위트 집계·직전 실행 비교에서 제외 */
	public boolean isWholeSuite() {
		return !isSingleCase() && !isThroughCase() && !isRerun();
	}

	/** 실행할 케이스 수 기록. 실행 시작 시 호출 */
	public void plan(int caseCount) {
		if (caseCount < 0) {
			throw new IllegalArgumentException("caseCount must not be negative: " + caseCount);
		}
		this.plannedCount = caseCount;
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

	/** 실패 알림 발송 기록 */
	public void markFailureNotified(Instant notifiedAt) {
		this.failureNotifiedAt = Objects.requireNonNull(notifiedAt, "notifiedAt");
	}

	public void abort(Instant abortedAt) {
		if (status != Status.RUNNING) {
			throw new IllegalStateException("run " + getId() + " is already " + status);
		}
		this.status = Status.ABORTED;
		this.finishedAt = Objects.requireNonNull(abortedAt, "abortedAt");
	}

}
