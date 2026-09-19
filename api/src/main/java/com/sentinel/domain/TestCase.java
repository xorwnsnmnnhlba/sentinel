package com.sentinel.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.sentinel.runner.assertion.Assertion;

import jakarta.persistence.Column;
import jakarta.persistence.ConstraintMode;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 검증 대상 요청 1건과 응답 판정 규칙. 규칙 미지정 시 기본 판정(상태 코드 2xx) 적용. Extractor는 추후 추가.
 */
@Entity
@Table(name = "test_case",
		uniqueConstraints = @UniqueConstraint(name = "uk_test_case_suite_name", columnNames = { "suite_id", "name", "archive_key" }))
public class TestCase extends ArchivableEntity {

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

	protected TestCase() {
	}

	public TestCase(TestSuite suite, String name, RequestSpec request) {
		this(suite, name, request, List.of());
	}

	public TestCase(TestSuite suite, String name, RequestSpec request, List<Assertion> assertions) {
		this.suite = Objects.requireNonNull(suite, "suite");
		this.name = Preconditions.requireText(name, "name", 200);
		this.request = Objects.requireNonNull(request, "request");
		changeAssertions(assertions);
	}

	public void changeAssertions(List<Assertion> assertions) {
		this.assertions = new ArrayList<>(assertions == null ? List.of() : assertions);
	}

	public void rename(String name) {
		this.name = Preconditions.requireText(name, "name", 200);
	}

	public void changeRequest(RequestSpec request) {
		this.request = Objects.requireNonNull(request, "request");
	}

	public TestSuite getSuite() {
		return suite;
	}

	public String getName() {
		return name;
	}

	public RequestSpec getRequest() {
		return request;
	}

	public List<Assertion> getAssertions() {
		return assertions == null ? List.of() : List.copyOf(assertions);
	}

}
