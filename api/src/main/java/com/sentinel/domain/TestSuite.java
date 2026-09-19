package com.sentinel.domain;

import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.ConstraintMode;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "test_suite",
		uniqueConstraints = @UniqueConstraint(name = "uk_test_suite_project_name", columnNames = { "project_id", "name", "archive_key" }))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TestSuite extends ArchivableEntity {

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "project_id", nullable = false, foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT))
	private Project project;

	@Column(nullable = false, length = 100)
	private String name;

	@Column(length = 500)
	private String description;

	public TestSuite(Project project, String name, String description) {
		this.project = Objects.requireNonNull(project, "project");
		this.name = Preconditions.requireText(name, "name", 100);
		this.description = Preconditions.optionalText(description, "description", 500);
	}

	public void rename(String name) {
		this.name = Preconditions.requireText(name, "name", 100);
	}

	public void describe(String description) {
		this.description = Preconditions.optionalText(description, "description", 500);
	}

}
