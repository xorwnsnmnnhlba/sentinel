package com.sentinel.infra.persistence;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import com.sentinel.domain.TestCase;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 조회 메서드는 보관되지 않은 항목(archivedAt IS NULL)만 대상. 실행 결과의 케이스 참조는 보관 여부와 무관하게 유지
 */
public interface TestCaseRepository extends JpaRepository<TestCase, Long> {

	/** 스위트의 케이스를 실행 순서로 (순서가 같으면 ID 순) */
	List<TestCase> findBySuiteIdAndArchivedAtIsNullOrderByRunOrderAscIdAsc(Long suiteId);

	/** 모든 케이스를 스위트별 실행 순서로 */
	List<TestCase> findByArchivedAtIsNullOrderBySuiteIdAscRunOrderAscIdAsc();

	/**
	 * 이름·경로 부분 일치 검색 (대소문자 무시). 스위트별 실행 순서
	 * @param pattern 소문자 LIKE 패턴. {@code %}·{@code _}·{@code \}는 {@code \}로 이스케이프
	 * @param suiteId null이면 모든 스위트
	 */
	@Query("""
			select c from TestCase c
			where c.archivedAt is null
			  and (:suiteId is null or c.suite.id = :suiteId)
			  and (lower(c.name) like :pattern escape '\\' or lower(c.request.path) like :pattern escape '\\')
			order by c.suite.id, c.runOrder, c.id
			""")
	List<TestCase> search(@Param("pattern") String pattern, @Param("suiteId") Long suiteId, Limit limit);

	/** 스위트의 가장 큰 실행 순서. 케이스가 없으면 0. 보관된 케이스도 포함하여 새 케이스가 항상 마지막 */
	@Query("select coalesce(max(c.runOrder), 0) from TestCase c where c.suite.id = :suiteId")
	int findMaxRunOrder(@Param("suiteId") Long suiteId);

	Optional<TestCase> findByIdAndArchivedAtIsNull(Long id);

	/** 보관되지 않은 케이스를 스위트와 함께 로딩 */
	@Query("select c from TestCase c join fetch c.suite where c.id in :ids and c.archivedAt is null")
	List<TestCase> findActiveWithSuiteByIdIn(@Param("ids") Collection<Long> ids);

	Optional<TestCase> findBySuiteIdAndNameAndArchivedAtIsNull(Long suiteId, String name);

	long countBySuiteIdAndArchivedAtIsNull(Long suiteId);

}
