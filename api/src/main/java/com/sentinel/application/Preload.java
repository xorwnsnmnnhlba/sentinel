package com.sentinel.application;

import java.util.List;
import java.util.stream.Stream;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 목록 응답이 가리키는 다른 리소스(프로젝트·스위트·환경)를 한 번에 읽어 둠. 같은 영속성 컨텍스트라 항목마다 이름을 읽어도
 * 추가 조회가 없음 (관계는 지연 로딩이라 미리 읽지 않으면 가리키는 리소스 수만큼 조회). 트랜잭션 안에서 호출
 */
final class Preload {

	private Preload() {
	}

	static <T> void of(JpaRepository<T, Long> repository, Stream<Long> ids) {
		List<Long> distinct = ids.distinct().toList();
		if (!distinct.isEmpty()) {
			repository.findAllById(distinct);
		}
	}

}
