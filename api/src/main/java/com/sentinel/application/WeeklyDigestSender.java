package com.sentinel.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;

import com.sentinel.application.notification.Notification;
import com.sentinel.application.notification.Notifier;
import com.sentinel.domain.DigestDelivery;
import com.sentinel.infra.persistence.DigestDeliveryRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionException;

/**
 * 주간 요약 알림 발송. 스케줄 폴링 주기마다 가장 최근 예정 시각이 지났는지 확인하고, 그 예정 시각에 보낸 기록이 없으면 발송.
 * <p>
 * 예정 시각에 꺼져 있었으면(노트북 절전 등) 켜진 뒤 1회 발송. 7일이 지난 예정 시각은 보내지 않음 (다음 요약과 겹침).
 * 발송 기록을 먼저 남기고 보내므로 발송 실패 시 다시 보내지 않음 (같은 요약이 반복 발송되지 않도록). DB 접속 실패는 다음 주기에 다시 확인
 */
@Component
@RequiredArgsConstructor
class WeeklyDigestSender {

	private static final Logger log = LoggerFactory.getLogger(WeeklyDigestSender.class);

	/** 늦은 발송 허용 기간 */
	static final Duration LOOKBACK = Duration.ofDays(7);

	/** 요약 기간 일수. 발송일 전날까지 */
	static final int DAYS = 7;

	private final DigestProperties properties;

	private final ScheduleProperties scheduleProperties;

	private final WeeklyDigestService digests;

	private final DigestDeliveryRepository deliveries;

	private final Notifier notifier;

	private final Transactions transactions;

	private final Clock clock;

	/** 처리를 마친 가장 최근 예정 시각. 같은 예정 시각은 DB를 다시 확인하지 않음. 스케줄러 단일 스레드에서만 갱신 */
	private volatile Instant handled;

	/**
	 * @return 발송했으면 true
	 */
	public boolean sendIfDue() {
		Instant now = clock.instant();
		ZonedDateTime scheduled = latestOccurrence(properties.cronExpression(), now);
		if (scheduled == null || scheduled.toInstant().equals(handled)) {
			return false;
		}
		Instant scheduledAt = scheduled.toInstant();
		LocalDate to = scheduled.toLocalDate().minusDays(1);
		LocalDate from = to.minusDays(DAYS - 1L);
		try {
			if (transactions.readOnly(status -> deliveries.existsByScheduledAt(scheduledAt))) {
				handled = scheduledAt;
				return false;
			}
			Notification digest = digests.build(from, to);
			transactions.writeWithoutResult(status -> deliveries.save(new DigestDelivery(scheduledAt, from, to)));
			handled = scheduledAt;
			send(digest);
			log.info("Sent weekly digest for {} ~ {} (scheduled at {})", from, to, scheduledAt);
			return true;
		}
		catch (DataIntegrityViolationException ex) {
			// 이미 기록됨 (동시 발송 방지)
			handled = scheduledAt;
			return false;
		}
		catch (DataAccessException | TransactionException ex) {
			log.warn("Skipping weekly digest check: database unavailable ({})", ex.getMostSpecificCause().getMessage());
			return false;
		}
	}

	/** {@code now} 이전(포함) {@link #LOOKBACK} 안의 가장 최근 예정 시각. 없으면 null */
	ZonedDateTime latestOccurrence(CronExpression cron, Instant now) {
		ZonedDateTime end = now.atZone(scheduleProperties.zone());
		ZonedDateTime latest = null;
		ZonedDateTime next = cron.next(end.minus(LOOKBACK));
		while (next != null && !next.isAfter(end)) {
			latest = next;
			next = cron.next(next);
		}
		return latest;
	}

	private void send(Notification notification) {
		try {
			notifier.send(notification);
		}
		catch (RuntimeException ex) {
			log.error("Failed to send weekly digest: {}", notification.title(), ex);
		}
	}

}
