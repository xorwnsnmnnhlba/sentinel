package com.sentinel.application.notification;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 발송된 알림을 기록하는 테스트용 채널.
 */
public class RecordingNotifier implements Notifier {

	private final List<Notification> sent = new CopyOnWriteArrayList<>();

	@Override
	public void send(Notification notification) {
		sent.add(notification);
	}

	public List<Notification> sent() {
		return List.copyOf(sent);
	}

	public void reset() {
		sent.clear();
	}

}
