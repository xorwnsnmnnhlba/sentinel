package com.sentinel.runner.schema;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 기준선 대비 구조 변화 판정 (docs/design.md "스키마 드리프트 감지")
 * <ul>
 * <li>BREAKING: 필수 필드 없음(REMOVED), null 외 타입 추가·변경(TYPE_CHANGED)</li>
 * <li>WARNING: null이 새로 나타남(NULLABLE)</li>
 * <li>INFO: 새 필드(ADDED)</li>
 * </ul>
 * 소음 억제: 비어도 정상인 배열 요소·동적 키는 없음으로 보지 않음. 상위가 없거나 상위 타입이 컨테이너가 아니면 하위의 없음은 보고하지 않음.
 * 타입이 바뀐 경로와 새 필드의 하위 경로는 따로 보고하지 않음. 일부만 기록한 지문은 없음을 판정하지 않음
 */
public final class SchemaDrift {

	public enum Level {
		BREAKING, WARNING, INFO
	}

	public enum Kind {
		/** 필수 필드가 없음 */
		REMOVED,
		/** null 외 타입이 새로 나타남 (예: number → string) */
		TYPE_CHANGED,
		/** null이 새로 나타남 */
		NULLABLE,
		/** 기준선에 없는 필드 */
		ADDED
	}

	/**
	 * @param expected 기준선의 타입. 새 필드는 빈 목록
	 * @param actual 이번 응답의 타입. 없어진 필드는 빈 목록
	 */
	public record Change(Level level, Kind kind, String path, List<String> expected, List<String> actual) {

		public Change {
			expected = List.copyOf(expected);
			actual = List.copyOf(actual);
		}

	}

	private SchemaDrift() {
	}

	/** @return 변화 목록. 등급(심한 순) → 경로 순 */
	public static List<Change> compare(SchemaBaseline baseline, ResponseSchema current) {
		Map<String, SchemaBaseline.Field> expected = baseline.fields();
		Map<String, List<String>> actual = current.fields();
		List<Change> changes = new ArrayList<>();
		Set<String> retyped = new HashSet<>();

		actual.forEach((path, types) -> {
			SchemaBaseline.Field field = expected.get(path);
			if (field == null) {
				return;
			}
			Set<String> added = new TreeSet<>(types);
			added.removeAll(field.types());
			if (added.stream().anyMatch(type -> !type.equals("null"))) {
				changes.add(new Change(Level.BREAKING, Kind.TYPE_CHANGED, path, field.types(), types));
				retyped.add(path);
			}
			else if (added.contains("null")) {
				changes.add(new Change(Level.WARNING, Kind.NULLABLE, path, field.types(), types));
			}
		});

		if (current.complete()) {
			expected.forEach((path, field) -> {
				if (!field.required() || actual.containsKey(path) || SchemaPaths.mayBeEmpty(path)
						|| underRetyped(path, retyped)) {
					return;
				}
				String parent = SchemaPaths.parent(path);
				// 상위가 있고 하위를 가질 수 있는 타입일 때만 없어진 것으로 판정 (상위가 null이면 NULLABLE로 이미 보고)
				List<String> parentTypes = parent == null ? List.of() : actual.get(parent);
				if (parent != null && (parentTypes == null || !parentTypes.contains(SchemaPaths.containerOf(path)))) {
					return;
				}
				changes.add(new Change(Level.BREAKING, Kind.REMOVED, path, field.types(), List.of()));
			});
		}

		actual.forEach((path, types) -> {
			if (expected.containsKey(path) || underRetyped(path, retyped)) {
				return;
			}
			String parent = SchemaPaths.parent(path);
			// 새 필드의 하위는 상위 한 건으로 보고
			if (parent != null && !expected.containsKey(parent)) {
				return;
			}
			changes.add(new Change(Level.INFO, Kind.ADDED, path, List.of(), types));
		});

		changes.sort(Comparator.comparing(Change::level).thenComparing(Change::path));
		return List.copyOf(changes);
	}

	private static boolean underRetyped(String path, Set<String> retyped) {
		return retyped.stream().anyMatch(ancestor -> SchemaPaths.isAncestor(ancestor, path));
	}

}
