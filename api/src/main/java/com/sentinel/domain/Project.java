package com.sentinel.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "project", uniqueConstraints = @UniqueConstraint(name = "uk_project_name", columnNames = { "name", "archive_key" }))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Project extends ArchivableEntity {

	@Column(nullable = false, length = 100)
	private String name;

	@Column(length = 500)
	private String description;

	public Project(String name, String description) {
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
