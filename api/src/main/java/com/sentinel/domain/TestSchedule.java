package com.sentinel.domain;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.sentinel.runner.InvalidValueException;
import com.sentinel.runner.InvalidValueException.Code;
import jakarta.persistence.Column;
import jakarta.persistence.ConstraintMode;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.scheduling.support.CronExpression;

/**
 * 스위트 정기 실행. {@code nextRunAt} 컬럼 하나로 재시작 복구 처리.
 * <p>
 * cron은 5자리 표준 형식({@code 분 시 일 월 요일}). 놓친 실행은 몰아서 실행하지 않고 1회만 실행 후 현재 시각 기준 다음 시각으로 이동.
 */
@Entity
@Table(name = "test_schedule", indexes = @Index(name = "idx_schedule_due", columnList = "enabled, next_run_at"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TestSchedule extends BaseEntity {

	/** cron 칸 이름 (InvalidValueException CRON_INVALID_FIELD의 field) */
	private static final List<String> CRON_FIELDS = List.of("minute", "hour", "day", "month", "weekday");

	/** 낙관적 잠금 (V30). 동시에 고친 나중 요청은 실패하여 앞 요청의 변경을 덮어쓰지 않음 */
	@Version
	@Getter(AccessLevel.NONE)
	@Column(nullable = false)
	private long version;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "suite_id", nullable = false, foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT))
	private TestSuite suite;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "environment_id", nullable = false,
			foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT))
	private Environment environment;

	@Column(nullable = false, length = 100)
	private String cron;

	@Column(nullable = false)
	private boolean enabled;

	/** 비활성 시 null */
	private Instant nextRunAt;

	private Instant lastTriggeredAt;

	/** 휴일({@link Holiday})에는 실행을 건너뜀. 기본 꺼짐 */
	@Column(nullable = false)
	private boolean skipHolidays;

	/** 삭제(보관) 시각. 보관 시 비활성화하여 폴링 대상에서 제외 */
	private Instant archivedAt;

	public TestSchedule(TestSuite suite, Environment environment, String cron, Instant now, ZoneId zone) {
		this.suite = Objects.requireNonNull(suite, "suite");
		this.environment = Objects.requireNonNull(environment, "environment");
		if (!same(suite.getProject(), environment.getProject())) {
			throw new IllegalArgumentException("suite and environment must belong to the same project");
		}
		this.cron = normalizeCron(cron);
		this.enabled = true;
		this.nextRunAt = nextAfter(now, zone);
	}

	/**
	 * 실행 시각 도달 여부.
	 */
	public boolean isDue(Instant now) {
		return enabled && nextRunAt != null && !nextRunAt.isAfter(now);
	}

	/**
	 * 실행 개시 기록 및 다음 실행 시각 갱신. 실행 전에 호출하여 실행 중 장애 시 재실행 반복 방지.
	 */
	public void markTriggered(Instant now, ZoneId zone) {
		if (!isDue(now)) {
			throw new IllegalStateException("schedule " + getId() + " is not due at " + now);
		}
		this.lastTriggeredAt = now;
		this.nextRunAt = nextAfter(now, zone);
	}

	/**
	 * 이번 실행 건너뜀 (환경 점검 중). 다음 실행 시각만 옮기고 마지막 실행 시각은 그대로
	 * @throws IllegalStateException 실행 시각 미도달
	 */
	public void skip(Instant now, ZoneId zone) {
		if (!isDue(now)) {
			throw new IllegalStateException("schedule " + getId() + " is not due at " + now);
		}
		this.nextRunAt = nextAfter(now, zone);
	}

	/**
	 * cron 변경. 활성 상태면 현재 시각 기준 다음 실행 시각 재계산. 비활성 상태에서도 실행 시각이 없는 cron은 거부
	 */
	public void changeCron(String cron, Instant now, ZoneId zone) {
		if (isArchived()) {
			throw new IllegalStateException("schedule " + getId() + " is archived");
		}
		String previous = this.cron;
		this.cron = normalizeCron(cron);
		Instant next;
		try {
			next = nextAfter(now, zone);
		}
		catch (IllegalArgumentException ex) {
			this.cron = previous;
			throw ex;
		}
		if (enabled) {
			this.nextRunAt = next;
		}
	}

	/**
	 * 실행 환경 변경. 지난 실행 이력은 실행 시점 환경을 따로 기록하므로 영향 없음
	 * @throws IllegalArgumentException 스위트와 다른 프로젝트의 환경
	 */
	public void changeEnvironment(Environment environment) {
		Objects.requireNonNull(environment, "environment");
		if (!same(suite.getProject(), environment.getProject())) {
			throw new IllegalArgumentException("suite and environment must belong to the same project");
		}
		this.environment = environment;
	}

	public void enable(Instant now, ZoneId zone) {
		if (isArchived()) {
			throw new IllegalStateException("schedule " + getId() + " is archived");
		}
		this.enabled = true;
		this.nextRunAt = nextAfter(now, zone);
	}

	/** 휴일 건너뛰기 켜기·끄기 */
	public void changeSkipHolidays(boolean skipHolidays) {
		this.skipHolidays = skipHolidays;
	}

	public void disable() {
		this.enabled = false;
		this.nextRunAt = null;
	}

	public void archive(Instant archivedAt) {
		if (isArchived()) {
			throw new IllegalStateException("schedule " + getId() + " is already archived");
		}
		disable();
		this.archivedAt = Objects.requireNonNull(archivedAt, "archivedAt");
	}

	public boolean isArchived() {
		return archivedAt != null;
	}

	private Instant nextAfter(Instant instant, ZoneId zone) {
		ZonedDateTime next = toSpringCron(cron).next(instant.atZone(zone));
		if (next == null) {
			throw new InvalidValueException(Code.CRON_NEVER_FIRES, Map.of("cron", cron), "cron never fires: " + cron, null);
		}
		return next.toInstant();
	}

	static String normalizeCron(String cron) {
		String value = Preconditions.requireText(cron, "cron", 100).replaceAll("\\s+", " ");
		if (value.split(" ").length != 5) {
			throw new InvalidValueException(Code.CRON_FIELD_COUNT, Map.of("cron", value),
					"cron must have 5 fields (minute hour day month weekday): " + value, null);
		}
		toSpringCron(value);
		return value;
	}

	/** Spring CronExpression은 초 필드 포함 6자리이므로 0초 추가 */
	private static CronExpression toSpringCron(String cron) {
		try {
			return CronExpression.parse("0 " + cron);
		}
		catch (IllegalArgumentException ex) {
			String message = "invalid cron: " + cron + " (" + ex.getMessage() + ")";
			String[] fields = cron.split(" ");
			for (int index = 0; index < fields.length; index++) {
				if (!parses(fields, index)) {
					throw new InvalidValueException(Code.CRON_INVALID_FIELD,
							Map.of("field", CRON_FIELDS.get(index), "value", fields[index]), message, ex);
				}
			}
			throw new InvalidValueException(Code.CRON_INVALID, Map.of("cron", cron), message, ex);
		}
	}

	/** 다른 칸을 모두 *로 두고 이 칸만 넣어 해석. 잘못된 칸을 찾아 화면에 알리기 위함 */
	private static boolean parses(String[] fields, int index) {
		String[] probe = { "*", "*", "*", "*", "*" };
		probe[index] = fields[index];
		try {
			CronExpression.parse("0 " + String.join(" ", probe));
			return true;
		}
		catch (IllegalArgumentException ex) {
			return false;
		}
	}

}
