package com.sentinel.domain;

import java.time.Instant;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import lombok.AccessLevel;
import lombok.Getter;

/**
 * 소프트 삭제(보관) 대상 엔티티. 실행 이력이 참조하므로 행을 지우지 않고 보관 시각만 기록.
 * <p>
 * 유니크 제약에 {@code archive_key}를 포함: 사용 중이면 0, 보관 시 자기 ID. 사용 중인 행끼리의 이름 중복은 DB가 막고,
 * 보관된 행의 이름은 재사용 가능.
 */
@MappedSuperclass
@Getter
public abstract class ArchivableEntity extends BaseEntity {

	private Instant archivedAt;

	/** 유니크 제약용 내부 값. 외부 노출 불필요 */
	@Getter(AccessLevel.NONE)
	@Column(nullable = false)
	private long archiveKey;

	/**
	 * @throws IllegalStateException 저장 전이거나 이미 보관된 경우
	 */
	public void archive(Instant archivedAt) {
		if (getId() == null) {
			throw new IllegalStateException("cannot archive an unsaved entity");
		}
		if (isArchived()) {
			throw new IllegalStateException(getClass().getSimpleName() + " " + getId() + " is already archived");
		}
		this.archivedAt = Objects.requireNonNull(archivedAt, "archivedAt");
		this.archiveKey = getId();
	}

	public boolean isArchived() {
		return archivedAt != null;
	}

}
