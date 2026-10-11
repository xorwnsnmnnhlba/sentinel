package com.sentinel.domain;

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
 * 휴일. 이 날짜({@code sentinel.schedule.zone} 기준)에는 "휴일 건너뛰기"를 켠 스케줄 실행을 건너뜀
 */
@Entity
@Table(name = "holiday",
		uniqueConstraints = @UniqueConstraint(name = "uk_holiday_date", columnNames = { "holiday_date", "archive_key" }))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Holiday extends ArchivableEntity {

	static final int MAX_NAME_LENGTH = 100;

	@Column(name = "holiday_date", nullable = false)
	private LocalDate date;

	/** 예: 추석, 임시공휴일 */
	@Column(nullable = false, length = MAX_NAME_LENGTH)
	private String name;

	/** 등록한 사용자 */
	private Long createdBy;

	/**
	 * @throws IllegalArgumentException 이름 없음·100자 초과
	 */
	public Holiday(LocalDate date, String name, Long createdBy) {
		this.date = Objects.requireNonNull(date, "date");
		this.name = Preconditions.requireText(name, "name", MAX_NAME_LENGTH);
		this.createdBy = createdBy;
	}

}
