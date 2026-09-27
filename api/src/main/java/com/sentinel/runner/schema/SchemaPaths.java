package com.sentinel.runner.schema;

/**
 * 지문 경로 해석. 경로는 {@code $} 뒤에 구획이 이어진 형태: {@code .name}, {@code .*}, {@code ['a.b']}, {@code []}
 */
final class SchemaPaths {

	static final String ROOT = "$";

	private SchemaPaths() {
	}

	/** 상위 경로. 루트는 null */
	static String parent(String path) {
		int last = lastSegmentStart(path);
		return last < 0 ? null : path.substring(0, last);
	}

	/** 마지막 구획 (예: {@code .id}, {@code []}, {@code ['a.b']}). 루트는 빈 문자열 */
	static String lastSegment(String path) {
		int last = lastSegmentStart(path);
		return last < 0 ? "" : path.substring(last);
	}

	/**
	 * 하위 경로가 존재하려면 상위가 가져야 하는 타입. {@code []}는 배열, 그 외(키)는 객체
	 */
	static String containerOf(String path) {
		return lastSegment(path).equals("[]") ? "array" : "object";
	}

	/** 비어 있어도 정상인 구획: 배열 요소({@code []}), 동적 키({@code .*}) */
	static boolean mayBeEmpty(String path) {
		String segment = lastSegment(path);
		return segment.equals("[]") || segment.equals(".*");
	}

	/** ancestor가 path의 상위(자기 자신 제외)인지 */
	static boolean isAncestor(String ancestor, String path) {
		for (String current = parent(path); current != null; current = parent(current)) {
			if (current.equals(ancestor)) {
				return true;
			}
		}
		return false;
	}

	private static int lastSegmentStart(String path) {
		int last = -1;
		int i = ROOT.length();
		while (i < path.length()) {
			last = i;
			char c = path.charAt(i);
			if (c == '.') {
				i++;
				while (i < path.length() && path.charAt(i) != '.' && path.charAt(i) != '[') {
					i++;
				}
			}
			else if (c == '[' && i + 1 < path.length() && path.charAt(i + 1) == ']') {
				i += 2;
			}
			else if (c == '[') {
				// ['...'] : 따옴표 안의 \' 와 \\ 는 건너뜀
				i += 2;
				while (i < path.length() && !(path.charAt(i) == '\'' && i + 1 < path.length() && path.charAt(i + 1) == ']')) {
					i += path.charAt(i) == '\\' ? 2 : 1;
				}
				i += 2;
			}
			else {
				throw new IllegalArgumentException("invalid schema path: " + path);
			}
		}
		return last;
	}

}
