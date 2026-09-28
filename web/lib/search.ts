/** 케이스 검색 결과 표시 (api/README.md "케이스 검색") */

/** Spring 검색 결과 최대 수와 같음. 이만큼 나오면 더 있을 수 있음 */
export const MAX_SEARCH_RESULTS = 100;

/** Spring 검색어 최대 길이와 같음 */
export const MAX_QUERY_LENGTH = 200;

export type TextPart = { text: string; match: boolean };

/**
 * 검색어와 일치하는 부분(대소문자 무시)을 표시하도록 문자열을 나눔. 검색어가 비어 있으면 전체를 일치 아님으로
 */
export function splitMatches(text: string, query: string): TextPart[] {
  const needle = query.trim().toLowerCase();
  if (needle === "") {
    return [{ text, match: false }];
  }
  const haystack = text.toLowerCase();
  const parts: TextPart[] = [];
  let from = 0;
  for (let index = haystack.indexOf(needle); index >= 0; index = haystack.indexOf(needle, from)) {
    if (index > from) {
      parts.push({ text: text.slice(from, index), match: false });
    }
    parts.push({ text: text.slice(index, index + needle.length), match: true });
    from = index + needle.length;
  }
  if (from < text.length) {
    parts.push({ text: text.slice(from), match: false });
  }
  return parts;
}
