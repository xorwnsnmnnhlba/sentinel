package com.sentinel.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.ConstraintMode;
import jakarta.persistence.Entity;
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
 * 환경의 점검 시간. {@code startsAt} 이상 {@code endsAt} 미만 동안 그 환경의 스케줄 실행을 건너뜀.
 * <p>
 * 끝나면 따로 조치 없이 스케줄이 다시 실행되도록 기간을 최대 {@link #MAX_DURATION}으로 제한 (끄고 잊는 일 방지).
 * 취소·조기 종료는 삭제(보관)
 */
@Entity
@Table(name = "maintenance_window",
		indexes = @Index(name = "idx_maintenance_environment", columnList = "environment_id, ends_at"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MaintenanceWindow extends BaseEntity {

	public static final Duration MAX_DURATION = Duration.ofDays(7);

	public static final int MAX_REASON_LENGTH = 200;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "environment_id", nullable = false, foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT))
	private Environment environment;

	@Column(nullable = false)
	private Instant startsAt;

	@Column(nullable = false)
	private Instant endsAt;

	/** 점검 사유 (예: GW 배포). 없으면 null */
	@Column(length = MAX_REASON_LENGTH)
	private String reason;

	/** 등록한 사용자 */
	private Long createdBy;

	/** 삭제(보관) 시각. 취소·조기 종료 */
	private Instant archivedAt;

	/**
	 * @param now 이미 끝난 기간은 등록 불가
	 * @throws IllegalArgumentException 끝이 시작 이전, 이미 끝난 기간, 최대 기간 초과, 사유가 너무 긺
	 */
	public MaintenanceWindow(Environment environment, Instant startsAt, Instant endsAt, String reason, Long createdBy,
			Instant now) {
		this.environment = Objects.requireNonNull(environment, "environment");
		this.createdBy = createdBy;
		assign(startsAt, endsAt, reason, now);
	}

	/**
	 * 기간·사유 변경 (연장·단축 등). 검증은 등록과 같음
	 * @throws IllegalStateException 이미 끝났거나 삭제된 점검
	 */
	public void change(Instant startsAt, Instant endsAt, String reason, Instant now) {
		if (!isOpen(now)) {
			throw new IllegalStateException("maintenance window " + getId() + " has already ended");
		}
		assign(startsAt, endsAt, reason, now);
	}

	private void assign(Instant startsAt, Instant endsAt, String reason, Instant now) {
		Objects.requireNonNull(startsAt, "startsAt");
		Objects.requireNonNull(endsAt, "endsAt");
		if (!endsAt.isAfter(startsAt)) {
			throw new IllegalArgumentException("endsAt must be after startsAt");
		}
		if (!endsAt.isAfter(now)) {
			throw new IllegalArgumentException("endsAt must be in the future");
		}
		if (Duration.between(startsAt, endsAt).compareTo(MAX_DURATION) > 0) {
			throw new IllegalArgumentException("a maintenance window can last at most " + MAX_DURATION.toDays() + " days");
		}
		String trimmed = reason == null || reason.isBlank() ? null : reason.strip();
		if (trimmed != null && trimmed.length() > MAX_REASON_LENGTH) {
			throw new IllegalArgumentException("reason must be at most " + MAX_REASON_LENGTH + " characters");
		}
		this.startsAt = startsAt;
		this.endsAt = endsAt;
		this.reason = trimmed;
	}

	/** 지금 점검 중 (스케줄 실행을 건너뜀) */
	public boolean isActive(Instant now) {
		return isOpen(now) && !now.isBefore(startsAt);
	}

	/** 삭제되지 않았고 아직 끝나지 않음 (진행 중 또는 예정) */
	public boolean isOpen(Instant now) {
		return !isArchived() && now.isBefore(endsAt);
	}

	/**
	 * 삭제(보관). 예정된 점검은 취소, 진행 중인 점검은 바로 끝남
	 * @throws IllegalStateException 이미 삭제됨
	 */
	public void archive(Instant archivedAt) {
		if (isArchived()) {
			throw new IllegalStateException("maintenance window " + getId() + " is already archived");
		}
		this.archivedAt = Objects.requireNonNull(archivedAt, "archivedAt");
	}

	public boolean isArchived() {
		return archivedAt != null;
	}

}
