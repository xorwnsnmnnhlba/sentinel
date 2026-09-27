package com.sentinel.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 설정 변경 1건. 값은 남기지 않고 바뀐 항목 이름만 기록 (시크릿은 이름만). 추가만 하고 수정·삭제하지 않음.
 * 발생 시각은 {@link #getCreatedAt()}
 */
@Entity
@Table(name = "change_log")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChangeLog extends BaseEntity {

	public enum TargetType {

		PROJECT, ENVIRONMENT, SUITE, CASE, SCHEDULE, USER, INVITE

	}

	public enum Action {

		CREATED, UPDATED, DELETED

	}

	static final int MAX_NAME_LENGTH = 320;

	/** 변경한 사용자. 사용자를 삭제해도 ID는 유지 */
	private Long actorId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private TargetType targetType;

	@Column(nullable = false)
	private Long targetId;

	/** 변경 당시 이름 (케이스·스위트 이름, 사용자·초대는 이메일). 항목을 삭제해도 이력 표시용 */
	@Column(nullable = false, length = MAX_NAME_LENGTH)
	private String targetName;

	/** 소속 프로젝트. 사용자·초대는 null */
	private Long projectId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private Action action;

	/** 바뀐 항목 이름. 예: name, request, variables.userId, secrets.token, caseOrder */
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(columnDefinition = "json", nullable = false)
	private List<String> fields = new ArrayList<>();

	public ChangeLog(Long actorId, TargetType targetType, long targetId, String targetName, Long projectId,
			Action action, List<String> fields) {
		this.actorId = actorId;
		this.targetType = Objects.requireNonNull(targetType, "targetType");
		this.targetId = targetId;
		String name = Objects.requireNonNull(targetName, "targetName");
		this.targetName = name.length() <= MAX_NAME_LENGTH ? name : name.substring(0, MAX_NAME_LENGTH);
		this.projectId = projectId;
		this.action = Objects.requireNonNull(action, "action");
		this.fields = new ArrayList<>(fields);
	}

	public List<String> getFields() {
		return List.copyOf(fields);
	}

}
