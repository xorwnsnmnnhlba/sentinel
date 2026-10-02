package com.sentinel.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 주간 요약 알림 발송 기록. 예정 시각마다 1건 (같은 요약을 두 번 보내지 않음)
 */
@Entity
@Table(name = "digest_delivery",
		uniqueConstraints = @UniqueConstraint(name = "uk_digest_delivery_scheduled_at", columnNames = "scheduled_at"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DigestDelivery extends BaseEntity {

	/** cron 예정 시각. 늦게 보냈으면 실제 발송 시각은 {@code createdAt} */
	@Column(nullable = false)
	private Instant scheduledAt;

	@Column(nullable = false)
	private LocalDate periodFrom;

	@Column(nullable = false)
	private LocalDate periodTo;

	/**
	 * @throws IllegalArgumentException 시작일이 종료일보다 뒤
	 */
	public DigestDelivery(Instant scheduledAt, LocalDate periodFrom, LocalDate periodTo) {
		this.scheduledAt = Objects.requireNonNull(scheduledAt, "scheduledAt");
		this.periodFrom = Objects.requireNonNull(periodFrom, "periodFrom");
		this.periodTo = Objects.requireNonNull(periodTo, "periodTo");
		if (periodFrom.isAfter(periodTo)) {
			throw new IllegalArgumentException("periodFrom must not be after periodTo");
		}
	}

}
