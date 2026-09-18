package com.sentinel.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(name = "project", uniqueConstraints = @UniqueConstraint(name = "uk_project_name", columnNames = "name"))
public class Project extends BaseEntity {

	@Column(nullable = false, length = 100)
	private String name;

	@Column(length = 500)
	private String description;

	protected Project() {
	}

	public Project(String name, String description) {
		this.name = Preconditions.requireText(name, "name", 100);
		this.description = description;
	}

	public void rename(String name) {
		this.name = Preconditions.requireText(name, "name", 100);
	}

	public void describe(String description) {
		this.description = description;
	}

	public String getName() {
		return name;
	}

	public String getDescription() {
		return description;
	}

}
