package com.sentinel.runner;

import java.util.Set;

/**
 * 환경 변수셋에 없는 {@code {{변수}}} 참조. 전송 전 단계의 실패.
 */
public class UnresolvedVariableException extends RuntimeException {

	private final Set<String> names;

	public UnresolvedVariableException(Set<String> names) {
		super("Unresolved variables: " + String.join(", ", names));
		this.names = Set.copyOf(names);
	}

	public Set<String> names() {
		return names;
	}

}
