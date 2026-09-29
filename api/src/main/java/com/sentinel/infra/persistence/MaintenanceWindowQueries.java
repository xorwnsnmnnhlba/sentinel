package com.sentinel.infra.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.sentinel.domain.MaintenanceWindow;

/**
 * 점검 시간 조회 (QueryDSL). 삭제되지 않은 점검만 대상
 */
public interface MaintenanceWindowQueries {

	/** 환경의 진행 중인 점검 중 가장 늦게 끝나는 것 ({@code startsAt <= now < endsAt}). 스케줄 실행 전 확인 */
	Optional<MaintenanceWindow> findActive(long environmentId, Instant now);

	/**
	 * 끝나지 않은 점검 (진행 중·예정), 시작 순. 환경을 함께 로딩. 삭제된 환경의 점검은 제외 (그 환경의 스케줄도 삭제됨)
	 * @param environmentId null이면 전체
	 */
	List<MaintenanceWindow> findOpen(Long environmentId, Instant now);

}
