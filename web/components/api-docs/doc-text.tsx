import { richText, schemaNames } from "@/lib/openapi";

/** Javadoc에서 온 설명. 코드 구간만 코드 글꼴로 (HTML로 그리지 않음) */
export function DocText({ text }: { text: string }) {
  return (
    <>
      {richText(text).map((segment, index) =>
        segment.code ? (
          <code key={index} className="rounded-sm bg-muted px-1 py-0.5 font-mono text-xs">
            {segment.text}
          </code>
        ) : (
          <span key={index}>{segment.text}</span>
        ),
      )}
    </>
  );
}

/** 타입 표기. 데이터 형식 이름은 아래 "데이터 형식"의 해당 항목으로 이동 */
export function TypeLabel({ label, schemas }: { label: string; schemas: Set<string> }) {
  const names = schemaNames(label).filter((name) => schemas.has(name));
  if (names.length === 0) {
    return <span className="font-mono text-xs">{label}</span>;
  }
  const parts = label.split(new RegExp(`\\b(${names.join("|")})\\b`));
  return (
    <span className="font-mono text-xs">
      {parts.map((part, index) =>
        names.includes(part) ? (
          <a key={index} href={`#schema-${part}`} className="text-brand-deep underline underline-offset-2">
            {part}
          </a>
        ) : (
          <span key={index}>{part}</span>
        ),
      )}
    </span>
  );
}
