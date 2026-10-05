package com.sentinel.domain;

import java.time.Instant;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 알림 발송 1건. 시각·채널·제목·결과만 기록하고 Webhook 주소(자격증명)와 본문은 남기지 않음.
 * 보관기간이 지나면 삭제 (실행 이력과 달리 운영 확인용 기록)
 */
@Entity
@Table(name = "notification_delivery",
		indexes = @Index(name = "idx_notification_delivery_sent_at", columnList = "sent_at"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class NotificationDelivery extends BaseEntity {

	static final int MAX_TITLE_LENGTH = 300;

	static final int MAX_ERROR_LENGTH = 40;

	@Column(nullable = false)
	private Instant sentAt;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 10)
	private Channel channel;

	/** 프로젝트 Webhook으로 보낸 경우의 프로젝트 */
	private Long projectId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 10)
	private Format format;

	/** 알림 제목. 길면 잘라 저장 */
	@Column(nullable = false, length = MAX_TITLE_LENGTH)
	private String title;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 10)
	private Tone tone;

	/** 2xx 응답을 받음. 로그 기록(LOG)은 true */
	@Column(nullable = false)
	private boolean delivered;

	/** 받은 응답의 상태 코드. 응답을 받지 못했거나 로그 기록이면 null */
	private Integer statusCode;

	/** 응답을 받지 못한 사유 (예: TIMEOUT, CONNECTION_FAILED) */
	@Column(length = MAX_ERROR_LENGTH)
	private String error;

	/**
	 * @throws IllegalArgumentException 프로젝트 채널인데 프로젝트 없음, 기본 채널인데 프로젝트 있음
	 */
	public NotificationDelivery(Instant sentAt, Channel channel, Long projectId, Format format, String title, Tone tone,
			boolean delivered, Integer statusCode, String error) {
		this.sentAt = Objects.requireNonNull(sentAt, "sentAt");
		this.channel = Objects.requireNonNull(channel, "channel");
		if ((channel == Channel.PROJECT) != (projectId != null)) {
			throw new IllegalArgumentException("projectId is required only for the PROJECT channel");
		}
		this.projectId = projectId;
		this.format = Objects.requireNonNull(format, "format");
		this.title = cut(Objects.requireNonNull(title, "title"), MAX_TITLE_LENGTH);
		this.tone = Objects.requireNonNull(tone, "tone");
		this.delivered = delivered;
		this.statusCode = statusCode;
		this.error = error == null ? null : cut(error, MAX_ERROR_LENGTH);
	}

	private static String cut(String value, int max) {
		return value.length() <= max ? value : value.substring(0, max - 1) + "…";
	}

	/** DEFAULT: 서버 기본 채널 ({@code sentinel.notification.webhook-url}), PROJECT: 프로젝트 Webhook */
	public enum Channel {
		DEFAULT, PROJECT
	}

	/** 보낸 형식. LOG는 기본 채널 주소가 없어 서버 로그에만 기록 */
	public enum Format {
		SLACK, TEAMS, TEXT, LOG
	}

	/** 알림 성격. INFO: 요약·확인, SUCCESS: 복구·회복, WARNING: 악화·공백, FAILURE: 실패·장애 */
	public enum Tone {
		INFO, SUCCESS, WARNING, FAILURE
	}

}
