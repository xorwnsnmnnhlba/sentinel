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

	/** 실패 알림을 보낼 Webhook 주소의 암호문 (V21). 없으면 서버 기본 채널. 평문은 보관하지 않음 */
	@Column(length = 4000)
	private String webhookUrlCipher;

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

	/** @param cipherText 암호화한 Webhook 주소 */
	public void changeWebhook(String cipherText) {
		this.webhookUrlCipher = Preconditions.requireText(cipherText, "webhookUrlCipher", 4000);
	}

	public void removeWebhook() {
		this.webhookUrlCipher = null;
	}

	public boolean hasWebhook() {
		return webhookUrlCipher != null;
	}

}
