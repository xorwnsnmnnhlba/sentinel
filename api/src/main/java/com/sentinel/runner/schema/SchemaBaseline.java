package com.sentinel.runner.schema;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 여러 번 관측한 구조 지문을 합친 기준선 (docs/design.md "스키마 드리프트 감지")
 * <ul>
 * <li>타입: 관측한 모든 타입의 합. null이 한 번이라도 보이면 null 허용</li>
 * <li>필수: 관측 가능했던 모든 응답에 존재. 관측 가능 = 상위 경로가 있었던 응답 (루트는 모든 응답).
 * 배열이 비었거나 선택 필드인 상위 객체가 없던 응답 때문에 하위 필드가 선택으로 판정되지 않도록 함</li>
 * <li>영구 허용({@link SchemaTolerance})을 더하면 그 경로는 null 허용·선택</li>
 * </ul>
 *
 * @param observations 합친 지문 수
 * @param fields 경로별 통계 (경로 정렬)
 */
public record SchemaBaseline(int observations, Map<String, Field> fields) {

	/**
	 * @param present 이 경로가 있었던 응답 수
	 * @param observed 관측 가능했던(상위 경로가 있었던) 응답 수
	 * @param types 관측한 타입 (정렬)
	 * @param optional 영구 허용으로 선택 ({@link SchemaTolerance.Kind#OPTIONAL})
	 */
	public record Field(int present, int observed, List<String> types, boolean optional) {

		public Field {
			types = List.copyOf(types);
		}

		public Field(int present, int observed, List<String> types) {
			this(present, observed, types, false);
		}

		public boolean required() {
			return !optional && observed > 0 && present == observed;
		}

	}

	public SchemaBaseline {
		fields = Collections.unmodifiableMap(new TreeMap<>(fields));
	}

	public static SchemaBaseline of(List<ResponseSchema> schemas) {
		Map<String, Integer> present = new TreeMap<>();
		Map<String, SortedSet<String>> types = new TreeMap<>();
		for (ResponseSchema schema : schemas) {
			schema.fields().forEach((path, observed) -> {
				present.merge(path, 1, Integer::sum);
				types.computeIfAbsent(path, key -> new TreeSet<>()).addAll(observed);
			});
		}
		Map<String, Field> fields = new TreeMap<>();
		present.forEach((path, count) -> {
			String parent = SchemaPaths.parent(path);
			int observed = parent == null ? schemas.size()
					: (int) schemas.stream().filter(schema -> schema.fields().containsKey(parent)).count();
			fields.put(path, new Field(count, observed, List.copyOf(types.get(path))));
		});
		return new SchemaBaseline(schemas.size(), fields);
	}

	/** 영구 허용을 더한 기준선. 기준선에 없는 경로의 허용은 무시 (나타나면 새 필드) */
	public SchemaBaseline tolerating(Collection<SchemaTolerance> tolerances) {
		Map<String, Field> tolerated = new TreeMap<>(fields);
		for (SchemaTolerance tolerance : tolerances) {
			tolerated.computeIfPresent(tolerance.path(), (path, field) -> switch (tolerance.kind()) {
				case NULLABLE -> {
					SortedSet<String> types = new TreeSet<>(field.types());
					types.add("null");
					yield new Field(field.present(), field.observed(), List.copyOf(types), field.optional());
				}
				case OPTIONAL -> new Field(field.present(), field.observed(), field.types(), true);
			});
		}
		return new SchemaBaseline(observations, tolerated);
	}

}
