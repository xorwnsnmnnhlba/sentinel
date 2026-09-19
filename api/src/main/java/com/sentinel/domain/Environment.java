package com.sentinel.domain;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * GW 단위 실행 환경. 모듈은 {@link RequestSpec#getPath()}의 경로 프리픽스로 구분.
 * <p>
 * 시크릿(토큰·API 키)은 평문 저장 금지 원칙에 따라 암호화 구현 시 별도 추가.
 */
@Entity
@Table(name = "environment",
		uniqueConstraints = @UniqueConstraint(name = "uk_environment_project_name", columnNames = { "project_id", "name", "archive_key" }))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Environment extends ArchivableEntity {

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "project_id", nullable = false, foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT))
	private Project project;

	@Column(nullable = false, length = 50)
	private String name;

	@Column(nullable = false, length = 500)
	private String baseUrl;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(nullable = false, columnDefinition = "json")
	private Map<String, String> variables = new LinkedHashMap<>();

	public Environment(Project project, String name, String baseUrl) {
		this.project = Objects.requireNonNull(project, "project");
		this.name = Preconditions.requireText(name, "name", 50);
		this.baseUrl = normalizeBaseUrl(baseUrl);
	}

	public void rename(String name) {
		this.name = Preconditions.requireText(name, "name", 50);
	}

	public void changeBaseUrl(String baseUrl) {
		this.baseUrl = normalizeBaseUrl(baseUrl);
	}

	public void putVariable(String key, String value) {
		String validKey = Preconditions.requireText(key, "variable key", 100);
		if (value == null) {
			throw new IllegalArgumentException("variable '" + validKey + "' must not be null");
		}
		variables.put(validKey, value);
	}

	/**
	 * 변수 전체 교체. 검증 실패 시 기존 변수 유지
	 */
	public void replaceVariables(Map<String, String> variables) {
		Map<String, String> previous = new LinkedHashMap<>(this.variables);
		this.variables.clear();
		try {
			if (variables != null) {
				variables.forEach(this::putVariable);
			}
		}
		catch (IllegalArgumentException ex) {
			this.variables.clear();
			this.variables.putAll(previous);
			throw ex;
		}
	}

	public void removeVariable(String key) {
		variables.remove(key);
	}

	/**
	 * http(s) 절대 URL만 허용, 끝의 {@code /} 제거. 경로 결합 시 {@code baseUrl + path} 형태로 사용.
	 */
	static String normalizeBaseUrl(String baseUrl) {
		String value = Preconditions.requireText(baseUrl, "baseUrl", 500);
		URI uri;
		try {
			uri = new URI(value);
		}
		catch (URISyntaxException ex) {
			throw new IllegalArgumentException("baseUrl is not a valid URI: " + value, ex);
		}
		if (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme())) {
			throw new IllegalArgumentException("baseUrl must use http or https: " + value);
		}
		if (uri.getHost() == null) {
			throw new IllegalArgumentException("baseUrl must have a host: " + value);
		}
		if (uri.getQuery() != null || uri.getFragment() != null) {
			throw new IllegalArgumentException("baseUrl must not have a query or fragment: " + value);
		}
		return value.replaceAll("/+$", "");
	}

	public Map<String, String> getVariables() {
		return Collections.unmodifiableMap(variables);
	}

}
