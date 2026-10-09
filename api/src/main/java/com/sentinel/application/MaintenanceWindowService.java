package com.sentinel.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import com.sentinel.application.ChangeLogService.Changes;
import com.sentinel.application.ChangeLogService.Target;
import com.sentinel.domain.ChangeLog.Action;
import com.sentinel.domain.ChangeLog.TargetType;
import com.sentinel.domain.Environment;
import com.sentinel.domain.MaintenanceWindow;
import com.sentinel.domain.User;
import com.sentinel.infra.persistence.EnvironmentRepository;
import com.sentinel.infra.persistence.MaintenanceWindowRepository;
import com.sentinel.infra.persistence.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 환경 점검 시간 등록·변경·삭제·조회. 점검 중에는 그 환경의 스케줄 실행을 건너뜀 ({@link SchedulePoller}).
 * 등록·변경·삭제는 같은 트랜잭션에서 변경 이력을 남김 (누가 알림을 멈췄는지 확인용)
 */
@Service
@RequiredArgsConstructor
public class MaintenanceWindowService {

	private final MaintenanceWindowRepository windows;

	private final EnvironmentRepository environments;

	private final UserRepository users;

	private final ChangeLogService changeLog;

	private final Clock clock;

	/**
	 * @param reason 점검 사유. 비어 있으면 없음
	 * @throws NotFoundException 환경 미존재
	 * @throws InvalidRequestException 끝이 시작 이전, 이미 끝난 기간, 최대 기간(7일) 초과, 사유 200자 초과
	 */
	@Transactional
	public MaintenanceWindowItem create(long environmentId, Instant startsAt, Instant endsAt, String reason,
			long actorId) {
		Environment environment = environments.findByIdAndArchivedAtIsNull(environmentId)
			.orElseThrow(() -> new NotFoundException("environment", environmentId));
		Instant now = clock.instant();
		MaintenanceWindow window;
		try {
			window = windows.save(new MaintenanceWindow(environment, startsAt, endsAt, reason, actorId, now));
		}
		catch (IllegalArgumentException ex) {
			throw new InvalidRequestException(ex.getMessage());
		}
		changeLog.record(actorId, target(window), Action.CREATED);
		return itemOf(window, now);
	}

	/**
	 * 부분 변경 (연장·단축·사유). null 필드는 유지, 빈 사유는 사유 삭제. 검증은 등록과 같음
	 * @throws NotFoundException 미존재 또는 삭제됨
	 * @throws ConflictException 이미 끝난 점검
	 * @throws InvalidRequestException 등록과 같음
	 */
	@Transactional
	public MaintenanceWindowItem update(long windowId, Instant startsAt, Instant endsAt, String reason, long actorId) {
		MaintenanceWindow window = find(windowId);
		Instant now = clock.instant();
		Instant beforeStart = window.getStartsAt();
		Instant beforeEnd = window.getEndsAt();
		String beforeReason = window.getReason();
		try {
			window.change(startsAt == null ? beforeStart : startsAt, endsAt == null ? beforeEnd : endsAt,
					reason == null ? beforeReason : reason, now);
		}
		catch (IllegalStateException ex) {
			throw new ConflictException(ex.getMessage());
		}
		catch (IllegalArgumentException ex) {
			throw new InvalidRequestException(ex.getMessage());
		}
		changeLog.record(actorId, target(window), Action.UPDATED,
				new Changes().compare("startsAt", beforeStart, window.getStartsAt())
					.compare("endsAt", beforeEnd, window.getEndsAt())
					.compare("reason", beforeReason, window.getReason())
					.fields());
		return itemOf(window, now);
	}

	/**
	 * 삭제(보관). 예정된 점검은 취소, 진행 중인 점검은 바로 끝나 다음 스케줄부터 실행
	 * @throws NotFoundException 미존재 또는 이미 삭제
	 */
	@Transactional
	public void delete(long windowId, long actorId) {
		MaintenanceWindow window = find(windowId);
		window.archive(clock.instant());
		changeLog.record(actorId, target(window), Action.DELETED);
	}

	/**
	 * 단건. 끝난 점검도 조회 (목록에는 없음)
	 * @throws NotFoundException 미존재·삭제, 삭제된 환경의 점검 (목록과 같은 기준)
	 */
	@Transactional(readOnly = true)
	public MaintenanceWindowItem get(long windowId) {
		MaintenanceWindow window = find(windowId);
		if (window.getEnvironment().isArchived()) {
			throw new NotFoundException("maintenance window", windowId);
		}
		return itemOf(window, clock.instant());
	}

	/**
	 * 끝나지 않은 점검 (진행 중·예정), 시작 순
	 * @param environmentId null이면 전체
	 */
	@Transactional(readOnly = true)
	public List<MaintenanceWindowItem> listOpen(Long environmentId) {
		Instant now = clock.instant();
		List<MaintenanceWindow> found = windows.findOpen(environmentId, now);
		Map<Long, IdName> creators = creators(found);
		return found.stream()
			.map(window -> MaintenanceWindowItem.of(window,
					window.getCreatedBy() == null ? null : creators.get(window.getCreatedBy()), now))
			.toList();
	}

	private MaintenanceWindow find(long windowId) {
		return windows.findByIdAndArchivedAtIsNull(windowId)
			.orElseThrow(() -> new NotFoundException("maintenance window", windowId));
	}

	private MaintenanceWindowItem itemOf(MaintenanceWindow window, Instant now) {
		return MaintenanceWindowItem.of(window, creators(List.of(window)).get(window.getCreatedBy()), now);
	}

	/** 등록한 사용자 이름(이메일). 삭제(보관)된 사용자도 조회 */
	private Map<Long, IdName> creators(List<MaintenanceWindow> found) {
		List<Long> ids = found.stream().map(MaintenanceWindow::getCreatedBy).filter(Objects::nonNull).distinct().toList();
		if (ids.isEmpty()) {
			return Map.of();
		}
		return users.findAllById(ids)
			.stream()
			.collect(Collectors.toMap(User::getId, user -> new IdName(user.getId(), user.getEmail())));
	}

	/** 점검 시간은 이름이 없으므로 환경 이름으로 표시 */
	private static Target target(MaintenanceWindow window) {
		Environment environment = window.getEnvironment();
		return new Target(TargetType.MAINTENANCE_WINDOW, window.getId(), environment.getName(),
				environment.getProject().getId());
	}

}
