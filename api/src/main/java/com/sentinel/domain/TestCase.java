package com.sentinel.domain;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import com.sentinel.runner.assertion.Assertion;
import com.sentinel.runner.extract.Extractor;

import jakarta.persistence.Column;
import jakarta.persistence.ConstraintMode;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 검증 대상 요청 1건과 응답 판정 규칙, 응답 값 추출 규칙. 판정 규칙 미지정 시 기본 판정(상태 코드 2xx) 적용.
 */
@Entity
@Table(name = "test_case",
		uniqueConstraints = @UniqueConstraint(name = "uk_test_case_suite_name", columnNames = { "suite_id", "name", "archive_key" }))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TestCase extends ArchivableEntity {

	/** 낙관적 잠금 (V30). 동시에 고친 나중 요청은 실패하여 앞 요청의 변경을 덮어쓰지 않음 */
	@Version
	@Column(nullable = false)
	private long version;

	static final int MAX_EXTRACTORS = 20;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "suite_id", nullable = false, foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT))
	private TestSuite suite;

	@Column(nullable = false, length = 200)
	private String name;

	@Embedded
	private RequestSpec request;

	/** 판정 규칙. 기존 데이터 호환을 위해 null 허용, null은 빈 목록으로 취급 */
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(columnDefinition = "json")
	private List<Assertion> assertions = new ArrayList<>();

	/**
	 * 스위트 안 실행 순서 (V12). 작을수록 먼저, 같으면 ID 순. 새 케이스는 스위트의 마지막 순서 다음
	 */
	@Column(nullable = false)
	private int runOrder;

	/** 응답 값 추출 규칙 (V11). 이전 데이터는 null, 빈 목록으로 취급 */
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(columnDefinition = "json")
	private List<Extractor> extractors = new ArrayList<>();

	public TestCase(TestSuite suite, String name, RequestSpec request) {
		this(suite, name, request, List.of());
	}

	public TestCase(TestSuite suite, String name, RequestSpec request, List<Assertion> assertions) {
		this(suite, name, request, assertions, List.of());
	}

	public TestCase(TestSuite suite, String name, RequestSpec request, List<Assertion> assertions,
			List<Extractor> extractors) {
		this.suite = Objects.requireNonNull(suite, "suite");
		this.name = Preconditions.requireText(name, "name", 200);
		this.request = Objects.requireNonNull(request, "request");
		// 생성자에서는 덮어쓸 수 있는 change* 대신 정적 검사 (JPA 프록시가 하위 클래스라 생성 중 재정의 메서드 호출 방지)
		this.assertions = validAssertions(assertions);
		this.extractors = validExtractors(extractors);
	}

	public void changeAssertions(List<Assertion> assertions) {
		this.assertions = validAssertions(assertions);
	}

	/** 전체 교체. null은 빈 목록. 변수 이름은 케이스 안에서 중복 불가 */
	public void changeExtractors(List<Extractor> extractors) {
		this.extractors = validExtractors(extractors);
	}

	private static List<Assertion> validAssertions(List<Assertion> assertions) {
		if (assertions != null && assertions.stream().anyMatch(Objects::isNull)) {
			throw new IllegalArgumentException("assertions must not contain null");
		}
		return new ArrayList<>(assertions == null ? List.of() : assertions);
	}

	private static List<Extractor> validExtractors(List<Extractor> extractors) {
		List<Extractor> valid = extractors == null ? List.of() : extractors;
		if (valid.stream().anyMatch(Objects::isNull)) {
			throw new IllegalArgumentException("extractors must not contain null");
		}
		if (valid.size() > MAX_EXTRACTORS) {
			throw new IllegalArgumentException("a case can have at most " + MAX_EXTRACTORS + " extractors");
		}
		Set<String> names = new HashSet<>();
		for (Extractor extractor : valid) {
			if (!names.add(extractor.name())) {
				throw new IllegalArgumentException("extractor name '" + extractor.name() + "' is duplicated");
			}
		}
		return new ArrayList<>(valid);
	}

	/** 실행 순서 지정. 0 이상 */
	public void placeAt(int runOrder) {
		if (runOrder < 0) {
			throw new IllegalArgumentException("runOrder must not be negative: " + runOrder);
		}
		this.runOrder = runOrder;
	}

	public void rename(String name) {
		this.name = Preconditions.requireText(name, "name", 200);
	}

	public void changeRequest(RequestSpec request) {
		this.request = Objects.requireNonNull(request, "request");
	}

	public List<Assertion> getAssertions() {
		return assertions == null ? List.of() : List.copyOf(assertions);
	}

	public List<Extractor> getExtractors() {
		return extractors == null ? List.of() : List.copyOf(extractors);
	}

}
