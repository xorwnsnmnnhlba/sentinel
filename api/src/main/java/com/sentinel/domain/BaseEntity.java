package com.sentinel.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import lombok.Getter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

@MappedSuperclass
@Getter
public abstract class BaseEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@CreationTimestamp
	@Column(nullable = false, updatable = false)
	private Instant createdAt;

	@UpdateTimestamp
	@Column(nullable = false)
	private Instant updatedAt;

	/**
	 * 동일 엔티티 여부. 저장 전(ID 없음)은 인스턴스 동일성, 저장 후는 ID로 비교. 지연 로딩 프록시에도 사용 가능.
	 */
	public static boolean same(BaseEntity a, BaseEntity b) {
		if (a == b) {
			return true;
		}
		return a != null && b != null && a.getId() != null && a.getId().equals(b.getId());
	}

}
