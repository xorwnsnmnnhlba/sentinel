package com.sentinel.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.util.stream.Collectors;

import com.sentinel.domain.ChangeLog;
import com.sentinel.domain.ChangeLog.Action;
import com.sentinel.domain.ChangeLog.TargetType;
import com.sentinel.domain.User;
import com.sentinel.infra.persistence.ChangeLogRepository;
import com.sentinel.infra.persistence.UserRepository;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
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

	private static final Sort NEWEST_FIRST = Sort.by(Sort.Order.desc("id"));

	private final ChangeLogRepository changeLogs;

	private final UserRepository users;

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
		changeLogs.save(new ChangeLog(actorId, target.type(), target.id(), target.name(), target.projectId(), action,
				fields));
	}

	/**
	 * 최신순 목록
	 * @param page 0부터 시작, 음수는 0으로 보정
	 * @param size 1~{@link #MAX_PAGE_SIZE}로 보정
	 */
	@Transactional(readOnly = true)
	public PageResponse<ChangeLogItem> list(Filter filter, int page, int size) {
		PageRequest pageable = PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, MAX_PAGE_SIZE), NEWEST_FIRST);
		Page<ChangeLog> found = changeLogs.findAll(filter.toSpecification(), pageable);
		Map<Long, IdName> actors = actors(found.getContent());
		return PageResponse.of(found, found.getContent()
			.stream()
			.map(log -> ChangeLogItem.of(log, log.getActorId() == null ? null : actors.get(log.getActorId())))
			.toList());
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
	 */
	public record Target(TargetType type, long id, String name, Long projectId) {
	}

	/**
	 * 목록 조건. null은 조건 없음
	 * @param targetType targetId와 함께 쓰면 항목 1건의 이력
	 * @param projectId 프로젝트와 그 안의 환경·스위트·케이스·스케줄 이력
	 */
	public record Filter(TargetType targetType, Long targetId, Long projectId) {

		public static Filter none() {
			return new Filter(null, null, null);
		}

		Specification<ChangeLog> toSpecification() {
			return (root, query, builder) -> {
				List<Predicate> conditions = new ArrayList<>();
				if (targetType != null) {
					conditions.add(builder.equal(root.get("targetType"), targetType));
				}
				if (targetId != null) {
					conditions.add(builder.equal(root.get("targetId"), targetId));
				}
				if (projectId != null) {
					conditions.add(builder.equal(root.get("projectId"), projectId));
				}
				return builder.and(conditions.toArray(Predicate[]::new));
			};
		}

	}

	/**
	 * @param actor 변경한 사용자 {id, name(이메일)}. 이전 기록 등 알 수 없으면 null
	 * @param fields 바뀐 항목 이름. 등록·삭제는 빈 목록
	 */
	public record ChangeLogItem(long id, Instant occurredAt, IdName actor, TargetType targetType, long targetId,
			String targetName, Long projectId, Action action, List<String> fields) {

		static ChangeLogItem of(ChangeLog log, IdName actor) {
			return new ChangeLogItem(log.getId(), log.getCreatedAt(), actor, log.getTargetType(), log.getTargetId(),
					log.getTargetName(), log.getProjectId(), log.getAction(), log.getFields());
		}

	}

}
