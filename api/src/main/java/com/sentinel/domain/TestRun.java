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
@Table(name = "test_run", indexes = @Index(name = "idx_run_suite_time", columnList = "suite_id, started_at DESC"))
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

	private TestRun(TestSuite suite, Environment environment, Trigger trigger, Instant startedAt, Long triggeredBy) {
		this.suite = Objects.requireNonNull(suite, "suite");
		this.environment = Objects.requireNonNull(environment, "environment");
		this.trigger = Objects.requireNonNull(trigger, "trigger");
		this.startedAt = Objects.requireNonNull(startedAt, "startedAt");
		this.status = Status.RUNNING;
		this.triggeredBy = triggeredBy;
	}

	public static TestRun start(TestSuite suite, Environment environment, Trigger trigger, Instant startedAt) {
		return new TestRun(suite, environment, trigger, startedAt, null);
	}

	/**
	 * @param triggeredBy 수동 실행을 시작한 사용자. 스케줄 실행은 null
	 */
	public static TestRun start(TestSuite suite, Environment environment, Trigger trigger, Instant startedAt,
			Long triggeredBy) {
		return new TestRun(suite, environment, trigger, startedAt, triggeredBy);
	}

	public void complete(Instant finishedAt) {
		if (status != Status.RUNNING) {
			throw new IllegalStateException("run " + getId() + " is already " + status);
		}
		this.status = Status.COMPLETED;
		this.finishedAt = Objects.requireNonNull(finishedAt, "finishedAt");
	}

	public void abort(Instant abortedAt) {
		if (status != Status.RUNNING) {
			throw new IllegalStateException("run " + getId() + " is already " + status);
		}
		this.status = Status.ABORTED;
		this.finishedAt = Objects.requireNonNull(abortedAt, "abortedAt");
	}

}
