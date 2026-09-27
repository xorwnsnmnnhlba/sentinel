/** 케이스·환경 이름 최대 길이 (Spring 검증과 같음) */
export const MAX_CASE_NAME_LENGTH = 200;
export const MAX_ENVIRONMENT_NAME_LENGTH = 50;

/**
 * 복제본 이름. "원본 (복사)", 이미 있으면 "원본 (복사 2)", "원본 (복사 3)" …
 * 같은 범위(케이스는 스위트, 환경은 프로젝트) 안 이름은 겹칠 수 없고 최대 길이가 있으므로, 길면 원본 이름 쪽을 잘라 맞춤
 * @param taken 같은 범위의 기존 이름
 */
export function copyName(original: string, taken: Iterable<string>, maxLength: number): string {
  const used = new Set(taken);
  for (let n = 1; ; n++) {
    const suffix = n === 1 ? " (복사)" : ` (복사 ${n})`;
    const candidate = original.slice(0, maxLength - suffix.length).trimEnd() + suffix;
    if (!used.has(candidate)) {
      return candidate;
    }
  }
}
