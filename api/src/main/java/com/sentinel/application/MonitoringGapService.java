package com.sentinel.application;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import com.sentinel.domain.MonitoringGap;
import com.sentinel.infra.persistence.MonitoringGapRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 모니터링 공백 기록·조회. 감지는 {@link SchedulePoller} (폴링 재개 시 늦은 스케줄)
 */
@Service
@RequiredArgsConstructor
public class MonitoringGapService {

	static final int DEFAULT_DAYS = 7;

	private final MonitoringGapRepository gaps;

	private final ScheduleProperties scheduleProperties;

	private final Clock clock;

	/**
	 * 공백 기록. 가장 최근 공백이 끝난 뒤 기준 시간 안에 시작되면 그 공백에 이어 붙임
	 * @return 새 공백이면 true, 앞 공백에 이어 붙였으면 false
	 */
	@Transactional
	public boolean record(Instant startedAt, Instant endedAt, int missedSchedules) {
		Optional<MonitoringGap> latest = gaps.findFirstByOrderByEndedAtDesc();
		if (latest.isPresent() && latest.get().isContinuedBy(startedAt, scheduleProperties.gapThreshold())) {
			latest.get().extend(startedAt, endedAt, missedSchedules);
			return false;
		}
		gaps.save(new MonitoringGap(startedAt, endedAt, missedSchedules));
		return true;
	}

	/**
	 * @param from 시작일(포함). 생략 시 종료일 포함 최근 7일
	 * @param to 종료일(포함). 생략 시 오늘
	 * @return 기간과 겹치는 공백 (최근 시작 순)
	 * @throws InvalidRequestException 잘못된 기간
	 */
	@Transactional(readOnly = true)
	public MonitoringGaps list(LocalDate from, LocalDate to) {
		DatePeriod period = DatePeriod.of(from, to, DEFAULT_DAYS, clock, scheduleProperties.zone());
		List<Gap> found = gaps
			.findByStartedAtLessThanAndEndedAtGreaterThanEqualOrderByStartedAtDesc(period.end(), period.start())
			.stream()
			.map(Gap::of)
			.toList();
		return new MonitoringGaps(period.from(), period.to(), found);
	}

	public record MonitoringGaps(LocalDate from, LocalDate to, List<Gap> gaps) {
	}

	/**
	 * @param startedAt 늦은 스케줄 중 가장 이른 예정 시각
	 * @param endedAt 폴링 재개 시각
	 * @param missedSchedules 예정 시각에 실행되지 않은 스케줄 수
	 */
	public record Gap(long id, Instant startedAt, Instant endedAt, int missedSchedules) {

		static Gap of(MonitoringGap gap) {
			return new Gap(gap.getId(), gap.getStartedAt(), gap.getEndedAt(), gap.getMissedSchedules());
		}

	}

}
