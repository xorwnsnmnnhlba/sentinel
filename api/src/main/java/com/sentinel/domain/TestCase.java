package com.sentinel.domain;

import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * 검증 대상 요청 1건. Assertion·Extractor는 3단계에서 추가.
 */
@Entity
@Table(name = "test_case",
		uniqueConstraints = @UniqueConstraint(name = "uk_test_case_suite_name", columnNames = { "suite_id", "name" }))
public class TestCase extends BaseEntity {

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "suite_id", nullable = false, foreignKey = @ForeignKey(name = "fk_test_case_suite"))
	private TestSuite suite;

	@Column(nullable = false, length = 200)
	private String name;

	@Embedded
	private RequestSpec request;

	protected TestCase() {
	}

	public TestCase(TestSuite suite, String name, RequestSpec request) {
		this.suite = Objects.requireNonNull(suite, "suite");
		this.name = Preconditions.requireText(name, "name", 200);
		this.request = Objects.requireNonNull(request, "request");
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

}
