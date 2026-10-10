package com.sentinel.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.types.Predicate;
import com.sentinel.domain.ChangeLog;
import com.sentinel.domain.ChangeLog.Action;
import com.sentinel.domain.ChangeLog.TargetType;
import com.sentinel.domain.QChangeLog;
import com.sentinel.domain.User;
import com.sentinel.infra.persistence.ChangeLogRepository;
import com.sentinel.infra.persistence.ProjectRepository;
import com.sentinel.infra.persistence.TestSuiteRepository;
import com.sentinel.infra.persistence.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 설정 변경 이력 기록·조회 (docs/design.md "변경 이력").
 * <p>
 * 기록은 변경과 같은 트랜잭션에서 하여 변경이 실패하면 이력도 남지 않음. 값은 남기지 않고 바뀐 항목 이름만.
 * 삭제(보관)는 대상 항목 1건만 기록 (하위 항목의 연쇄 삭제는 따로 기록하지 않음)
 */
@Service
@RequiredArgsConstructor
public class ChangeLogService {

	public static final int MAX_PAGE_SIZE = 100;

	private final ChangeLogRepository changeLogs;

	private final UserRepository users;

	private final ProjectRepository projects;

	private final TestSuiteRepository suites;

	/** 등록·삭제 기록 */
	@Transactional(propagation = Propagation.MANDATORY)
	public void record(Long actorId, Target target, Action action) {
		record(actorId, target, action, List.of());
	}

	/**
	 * 변경 기록. 수정인데 바뀐 항목이 없으면 기록하지 않음 (같은 값으로 저장)
	 * @param fields 바뀐 항목 이름
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public void record(Long actorId, Target target, Action action, List<String> fields) {
		if (action == Action.UPDATED && fields.isEmpty()) {
			return;
		}
		changeLogs.save(new ChangeLog(actorId, target.type(), target.id(), target.name(), target.projectId(),
				target.suiteId(), action, fields));
	}

	/**
	 * 최신순 목록
	 * @param page 0부터 시작, 음수는 0으로 보정
	 * @param size 1~{@link #MAX_PAGE_SIZE}로 보정
	 */
	@Transactional(readOnly = true)
	public PageResponse<ChangeLogItem> list(Filter filter, int page, int size) {
		PageRequest pageable = PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, MAX_PAGE_SIZE));
		Page<ChangeLog> found = changeLogs.findNewestFirst(filter.toPredicate(), pageable);
		Map<Long, IdName> actors = actors(found.getContent());
		// 소속 프로젝트·스위트는 지금 이름 (삭제된 것도). 대상 이름은 변경 당시 이름
		Map<Long, IdName> projectNames = names(projects,
				found.getContent().stream().map(ChangeLog::getProjectId), project -> IdName.of(project));
		Map<Long, IdName> suiteNames = names(suites, found.getContent().stream().map(ChangeLog::getSuiteId),
				suite -> IdName.of(suite));
		return PageResponse.of(found, found.getContent()
			.stream()
			.map(log -> ChangeLogItem.of(log, log.getActorId() == null ? null : actors.get(log.getActorId()),
					reference(projectNames, log.getProjectId()), reference(suiteNames, log.getSuiteId())))
			.toList());
	}

	private static <T> Map<Long, IdName> names(JpaRepository<T, Long> repository, Stream<Long> ids,
			Function<T, IdName> name) {
		List<Long> distinct = ids.filter(Objects::nonNull).distinct().toList();
		if (distinct.isEmpty()) {
			return Map.of();
		}
		return repository.findAllById(distinct)
			.stream()
			.map(name)
			.collect(Collectors.toMap(IdName::id, Function.identity()));
	}

	/** 기록에는 ID만 있으므로 이름을 붙임. 행이 없어진 경우(정리된 데이터)는 이름 없이 */
	private static IdName reference(Map<Long, IdName> names, Long id) {
		return id == null ? null : names.getOrDefault(id, new IdName(id, null));
	}

	/** 변경한 사용자 이름(이메일). 삭제(보관)된 사용자도 조회 */
	private Map<Long, IdName> actors(List<ChangeLog> found) {
		List<Long> actorIds = found.stream().map(ChangeLog::getActorId).filter(Objects::nonNull).distinct().toList();
		if (actorIds.isEmpty()) {
			return Map.of();
		}
		return users.findAllById(actorIds)
			.stream()
			.collect(Collectors.toMap(User::getId, user -> new IdName(user.getId(), user.getEmail())));
	}

	/**
	 * 이름을 붙인 두 값의 차이. 값이 다른 항목 이름만 모음
	 */
	public static final class Changes {

		private final List<String> fields = new ArrayList<>();

		public Changes compare(String field, Object before, Object after) {
			if (!Objects.equals(before, after)) {
				fields.add(field);
			}
			return this;
		}

		/** 키별 비교. 추가·삭제·값이 바뀐 키를 {@code prefix.키}로 (키 이름순) */
		public Changes compareEntries(String prefix, Map<String, ?> before, Map<String, ?> after) {
			TreeSet<String> keys = new TreeSet<>(before.keySet());
			keys.addAll(after.keySet());
			keys.forEach(key -> compare(prefix + "." + key, before.get(key), after.get(key)));
			return this;
		}

		public Changes add(String field) {
			fields.add(field);
			return this;
		}

		public List<String> fields() {
			return List.copyOf(fields);
		}

	}

	/**
	 * 변경 대상
	 * @param name 변경 당시 이름
	 * @param projectId 소속 프로젝트. 사용자·초대는 null
	 * @param suiteId 소속 스위트. 스위트는 자신, 케이스·스케줄은 소속 스위트, 그 외는 null
	 */
	public record Target(TargetType type, long id, String name, Long projectId, Long suiteId) {

		/** 스위트에 속하지 않는 대상 (프로젝트·환경·사용자·초대) */
		public Target(TargetType type, long id, String name, Long projectId) {
			this(type, id, name, projectId, null);
		}

	}

	/**
	 * 목록 조건. null은 조건 없음
	 * @param targetType targetId와 함께 쓰면 항목 1건의 이력
	 * @param projectId 프로젝트와 그 안의 환경·스위트·케이스·스케줄 이력
	 * @param suiteId 스위트와 그 안의 케이스·스케줄 이력
	 */
	public record Filter(TargetType targetType, Long targetId, Long projectId, Long suiteId) {

		public static Filter none() {
			return new Filter(null, null, null, null);
		}

		Predicate toPredicate() {
			QChangeLog log = QChangeLog.changeLog;
			BooleanBuilder conditions = new BooleanBuilder();
			if (targetType != null) {
				conditions.and(log.targetType.eq(targetType));
			}
			if (targetId != null) {
				conditions.and(log.targetId.eq(targetId));
			}
			if (projectId != null) {
				conditions.and(log.projectId.eq(projectId));
			}
			if (suiteId != null) {
				conditions.and(log.suiteId.eq(suiteId));
			}
			return conditions;
		}

	}

	/**
	 * @param actor 변경한 사용자 {id, name(이메일)}. 이전 기록 등 알 수 없으면 null
	 * @param project 소속 프로젝트 {id, name(지금 이름)}. 사용자·초대·휴일은 null
	 * @param suite 소속 스위트. 스위트는 자신, 케이스·스케줄은 소속 스위트, 그 외는 null
	 * @param fields 바뀐 항목 이름. 등록·삭제는 빈 목록
	 */
	public record ChangeLogItem(long id, Instant occurredAt, IdName actor, ChangeTarget target, IdName project,
			IdName suite, Action action, List<String> fields) {

		static ChangeLogItem of(ChangeLog log, IdName actor, IdName project, IdName suite) {
			return new ChangeLogItem(log.getId(), log.getCreatedAt(), actor,
					new ChangeTarget(log.getTargetType(), log.getTargetId(), log.getTargetName()), project, suite,
					log.getAction(), log.getFields());
		}

	}

	/**
	 * 바뀐 항목
	 * @param name 변경 당시 이름. 삭제된 항목도 표시 (스케줄은 스위트 이름, 점검 시간은 환경 이름, 사용자·초대는 이메일)
	 */
	public record ChangeTarget(TargetType type, long id, String name) {
	}

}
