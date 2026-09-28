package com.sentinel.infra.persistence;

import com.querydsl.core.types.Predicate;
import com.sentinel.domain.ChangeLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * 변경 이력 조회 (QueryDSL)
 */
public interface ChangeLogQueries {

	/**
	 * 최신순(ID 역순) 목록. 결과가 한 페이지에 모두 들어가면 전체 건수 쿼리 생략
	 * @param condition 조건 ({@code ChangeLogService.Filter}). null이면 전체
	 * @param pageable 페이지 번호·크기 (정렬은 무시)
	 */
	Page<ChangeLog> findNewestFirst(Predicate condition, Pageable pageable);

}
