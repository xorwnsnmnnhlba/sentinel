package com.sentinel.runner;

import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code {{변수}}} 치환.
 * <ul>
 * <li>변수명: 영문·숫자·{@code _ . -}, 괄호 안 앞뒤 공백 허용 ({@code {{ userId }}})</li>
 * <li>{@code \{{}는 치환 없이 {@code {{} 문자 그대로 출력</li>
 * <li>치환된 값은 재치환하지 않음</li>
 * <li>미해결 변수는 호출자가 넘긴 집합에 누적. 여러 문자열의 누락을 한 번에 보고하기 위함</li>
 * </ul>
 */
public final class VariableResolver {

	private static final Pattern TOKEN = Pattern.compile("\\\\\\{\\{|\\{\\{\\s*([A-Za-z0-9_.-]+)\\s*}}");

	private final Map<String, String> variables;

	VariableResolver(Map<String, String> variables) {
		this.variables = Map.copyOf(variables);
	}

	public String resolve(String template, Set<String> missing) {
		if (template == null) {
			return null;
		}
		Matcher matcher = TOKEN.matcher(template);
		return matcher.replaceAll(match -> {
			String name = match.group(1);
			if (name == null) {
				return Matcher.quoteReplacement("{{");
			}
			String value = variables.get(name);
			if (value == null) {
				missing.add(name);
				return Matcher.quoteReplacement(match.group());
			}
			return Matcher.quoteReplacement(value);
		});
	}

}
