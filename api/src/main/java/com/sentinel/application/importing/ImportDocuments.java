package com.sentinel.application.importing;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import com.sentinel.application.InvalidRequestException;
import org.snakeyaml.engine.v2.api.Load;
import org.snakeyaml.engine.v2.api.LoadSettings;
import org.snakeyaml.engine.v2.exceptions.YamlEngineException;
import tools.jackson.core.JacksonException;
import tools.jackson.core.util.DefaultIndenter;
import tools.jackson.core.util.DefaultPrettyPrinter;
import tools.jackson.core.util.Separators;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * 가져오기 문서 공통 처리. 문서 해석(크기·별칭 한도), 이름·변수 규칙, 본문 서식
 */
final class ImportDocuments {

	/** 문서 최대 길이(문자 수). 대형 문서도 수용하되 메모리 사용 제한 */
	static final int MAX_CONTENT_LENGTH = 5 * 1024 * 1024;

	/** 케이스 이름 최대 길이. {@code TestCase} 컬럼 길이와 동일 */
	static final int MAX_NAME_LENGTH = 200;

	/** YAML 별칭 확장 공격(billion laughs) 차단 */
	private static final int MAX_YAML_ALIASES = 50;

	/** 변수 이름에 쓸 수 없는 문자. {@code VariableResolver} 규칙과 동일 */
	private static final Pattern INVALID_VARIABLE_CHARACTER = Pattern.compile("[^A-Za-z0-9_.-]");

	/** 별칭 수를 제한하여 기하급수적 확장을 막고, 문서 길이 한도까지 읽도록 설정. 로더는 스레드 안전하지 않아 호출마다 생성 */
	private static final LoadSettings YAML_SETTINGS = LoadSettings.builder()
		.setMaxAliasesForCollections(MAX_YAML_ALIASES)
		.setCodePointLimit(MAX_CONTENT_LENGTH)
		.build();

	private static final ObjectMapper JSON = JsonMapper.builder().build();

	/** 본문 서식. 웹 편집기의 {@code JSON.stringify(value, null, 2)}와 같은 모양 */
	private static final DefaultPrettyPrinter BODY_FORMAT = new DefaultPrettyPrinter(Separators.createDefaultInstance()
		.withObjectNameValueSpacing(Separators.Spacing.AFTER)
		.withObjectEmptySeparator("")
		.withArrayEmptySeparator(""))
		.withObjectIndenter(new DefaultIndenter("  ", "\n"))
		.withArrayIndenter(new DefaultIndenter("  ", "\n"));

	private ImportDocuments() {
	}

	/**
	 * JSON·YAML 문서 해석. YAML 1.2는 JSON을 포함하므로 두 형식 모두 YAML 파서로 읽고, 별칭(*ref)은 펼침
	 * @throws InvalidRequestException 빈 문서, 길이 초과, 해석 실패
	 */
	static JsonNode parse(String content) {
		if (content == null || content.isBlank()) {
			throw new InvalidRequestException("document must not be empty");
		}
		if (content.length() > MAX_CONTENT_LENGTH) {
			throw new InvalidRequestException("document must be at most " + MAX_CONTENT_LENGTH + " characters");
		}
		Object loaded;
		try {
			loaded = new Load(YAML_SETTINGS).loadFromString(content);
		}
		catch (YamlEngineException ex) {
			throw new InvalidRequestException("document is not valid JSON or YAML: " + abbreviate(ex.getMessage()));
		}
		try {
			return JSON.valueToTree(loaded);
		}
		catch (JacksonException ex) {
			throw new InvalidRequestException("document could not be read: " + abbreviate(ex.getOriginalMessage()));
		}
	}

	/** 본문 서식으로 출력. 문자열 값은 그대로 */
	static String format(JsonNode value) {
		return value.isString() ? value.stringValue() : JSON.writer().with(BODY_FORMAT).writeValueAsString(value);
	}

	/** 앞뒤 공백을 뺀 문자열. 없거나 비었거나 객체·배열이면 null */
	static String text(JsonNode node) {
		if (node == null || node.isNull() || node.isContainer()) {
			return null;
		}
		String value = node.asString().strip();
		return value.isEmpty() ? null : value;
	}

	/**
	 * 이름을 변수로. 변수 이름에 쓸 수 없는 문자는 {@code _}로 바꾸고 경고
	 */
	static String variable(String name, List<String> warnings) {
		String sanitized = INVALID_VARIABLE_CHARACTER.matcher(name.strip()).replaceAll("_");
		if (!sanitized.equals(name)) {
			warnings.add("parameter '" + name + "' is used as variable {{" + sanitized + "}}");
		}
		return "{{" + sanitized + "}}";
	}

	static String truncateName(String name) {
		return name.length() <= MAX_NAME_LENGTH ? name : name.substring(0, MAX_NAME_LENGTH);
	}

	/**
	 * 문서 안에서 같은 이름이 반복되면 {@code (2)}, {@code (3)}을 붙임
	 */
	static String uniqueName(String name, Set<String> used) {
		String candidate = name;
		for (int number = 2; !used.add(candidate); number++) {
			String suffix = " (" + number + ")";
			candidate = name.substring(0, Math.min(name.length(), MAX_NAME_LENGTH - suffix.length())) + suffix;
		}
		return candidate;
	}

	private static String abbreviate(String message) {
		if (message == null) {
			return "unknown error";
		}
		String line = message.lines().findFirst().orElse(message);
		return line.length() <= 200 ? line : line.substring(0, 200) + "...";
	}

}
