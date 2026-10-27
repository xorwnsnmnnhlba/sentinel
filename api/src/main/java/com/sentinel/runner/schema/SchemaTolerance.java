package com.sentinel.runner.schema;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 기준선이 영구히 허용하는 변동 (docs/design.md "스키마 드리프트 감지" 3단계). 기준선은 최근 실행으로 계산하므로 null·없음을 본 실행이
 * 창 밖으로 밀려나면 같은 변동을 다시 구조 변화로 보고함. 받아들인 지문에서 본 변동을 따로 남겨 창과 무관하게 허용
 * <ul>
 * <li>NULLABLE: null을 관측한 경로. null은 승격만 허용, 강등 금지</li>
 * <li>OPTIONAL: 한 번 나타난 뒤 없던 적이 있는 경로. 처음 나타나기 전의 없음은 새 필드라 세지 않음</li>
 * </ul>
 * null 외 타입은 남기지 않음: 바뀐 타입이 창 밖으로 밀려난 뒤 되돌아오면 다시 보고 (되돌림도 호환이 깨지는 변화)
 */
public record SchemaTolerance(String path, Kind kind) {

	public enum Kind {
		NULLABLE, OPTIONAL
	}

	/**
	 * @param schemas 받아들인 지문 (오래된 순)
	 * @return 지문에서 관측한 변동. 없음은 일부만 기록한 지문에서 판정하지 않음, 비어도 정상인 경로·상위가 없거나 컨테이너가 아닌 경로 제외
	 *     ({@link SchemaDrift}가 없어진 필드로 보고하지 않는 경우와 같음)
	 */
	public static Set<SchemaTolerance> observedIn(List<ResponseSchema> schemas) {
		Set<SchemaTolerance> tolerances = new HashSet<>();
		Set<String> seen = new HashSet<>();
		for (ResponseSchema schema : schemas) {
			if (schema.complete()) {
				for (String path : seen) {
					if (!schema.fields().containsKey(path) && underContainer(path, schema)) {
						tolerances.add(new SchemaTolerance(path, Kind.OPTIONAL));
					}
				}
			}
			schema.fields().forEach((path, types) -> {
				seen.add(path);
				if (types.contains("null")) {
					tolerances.add(new SchemaTolerance(path, Kind.NULLABLE));
				}
			});
		}
		return tolerances;
	}

	private static boolean underContainer(String path, ResponseSchema schema) {
		if (SchemaPaths.mayBeEmpty(path)) {
			return false;
		}
		String parent = SchemaPaths.parent(path);
		if (parent == null) {
			return false;
		}
		List<String> parentTypes = schema.fields().get(parent);
		return parentTypes != null && parentTypes.contains(SchemaPaths.containerOf(path));
	}

}
