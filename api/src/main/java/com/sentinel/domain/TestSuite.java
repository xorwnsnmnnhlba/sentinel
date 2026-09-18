package com.sentinel.domain;

import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(name = "test_suite",
		uniqueConstraints = @UniqueConstraint(name = "uk_test_suite_project_name", columnNames = { "project_id", "name" }))
public class TestSuite extends BaseEntity {

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "project_id", nullable = false, foreignKey = @ForeignKey(name = "fk_test_suite_project"))
	private Project project;

	@Column(nullable = false, length = 100)
	private String name;

	@Column(length = 500)
	private String description;

	protected TestSuite() {
	}

	public TestSuite(Project project, String name, String description) {
		this.project = Objects.requireNonNull(project, "project");
		this.name = Preconditions.requireText(name, "name", 100);
		this.description = description;
	}

	public void rename(String name) {
		this.name = Preconditions.requireText(name, "name", 100);
	}

	public void describe(String description) {
		this.description = description;
	}

	public Project getProject() {
		return project;
	}

	public String getName() {
		return name;
	}

	public String getDescription() {
		return description;
	}

}
