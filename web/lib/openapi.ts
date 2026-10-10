/** API 문서 화면용 OpenAPI 3.1 해석 (api/README.md "API 문서"). 화면에 쓰는 부분만 */

export interface Schema {
  type?: string | string[];
  format?: string;
  enum?: unknown[];
  items?: Schema;
  $ref?: string;
  properties?: Record<string, Schema>;
  required?: string[];
  description?: string;
  additionalProperties?: Schema | boolean;
  oneOf?: Schema[];
  anyOf?: Schema[];
}

interface Content {
  schema?: Schema;
}

interface Parameter {
  name: string;
  in: string;
  required?: boolean;
  description?: string;
  schema?: Schema;
}

interface Operation {
  summary?: string;
  description?: string;
  tags?: string[];
  parameters?: Parameter[];
  requestBody?: { required?: boolean; content?: Record<string, Content> };
  responses?: Record<string, { description?: string; content?: Record<string, Content> }>;
  /** 빈 목록이면 로그인 없이 호출 */
  security?: unknown[];
}

export interface OpenApiDocument {
  openapi: string;
  info: { title: string; version: string; description?: string };
  tags?: { name: string; description?: string }[];
  paths: Record<string, Partial<Record<string, Operation>>>;
  components?: { schemas?: Record<string, Schema> };
}

export type ApiMethod = "GET" | "POST" | "PUT" | "PATCH" | "DELETE";

const METHODS: ApiMethod[] = ["GET", "POST", "PUT", "PATCH", "DELETE"];

export interface ApiOperation {
  /** 화면 안 이동용 id (예: op-get-api-holidays-holidayid) */
  id: string;
  method: ApiMethod;
  path: string;
  summary: string;
  /** 요약 뒤의 설명. 없으면 null */
  detail: string | null;
  /** 로그인 없이 호출 */
  open: boolean;
  parameters: { name: string; in: string; required: boolean; type: string; description: string | null }[];
  requestBody: { type: string; required: boolean } | null;
  responses: { status: string; type: string | null }[];
}

export interface ApiGroup {
  name: string;
  description: string | null;
  operations: ApiOperation[];
}

/** 묶음(태그)별 API. 묶음은 문서의 tags 순서, 목록에 없는 묶음은 뒤에 이름 순. 같은 묶음 안은 경로·메서드 순 */
export function groupOperations(doc: OpenApiDocument): ApiGroup[] {
  const groups = new Map<string, ApiGroup>();
  for (const tag of doc.tags ?? []) {
    groups.set(tag.name, { name: tag.name, description: tag.description ?? null, operations: [] });
  }
  const unlisted: ApiGroup[] = [];
  for (const [path, operations] of Object.entries(doc.paths)) {
    for (const method of METHODS) {
      const operation = operations[method.toLowerCase()];
      if (!operation) {
        continue;
      }
      const name = operation.tags?.[0] ?? "기타";
      let group = groups.get(name);
      if (!group) {
        group = { name, description: null, operations: [] };
        groups.set(name, group);
        unlisted.push(group);
      }
      group.operations.push(toOperation(method, path, operation));
    }
  }
  const ordered = [...groups.values()].filter((group) => !unlisted.includes(group));
  unlisted.sort((a, b) => a.name.localeCompare(b.name, "ko"));
  return [...ordered, ...unlisted]
    .filter((group) => group.operations.length > 0)
    .map((group) => ({
      ...group,
      operations: [...group.operations].sort(
        (a, b) => a.path.localeCompare(b.path) || METHODS.indexOf(a.method) - METHODS.indexOf(b.method),
      ),
    }));
}

function toOperation(method: ApiMethod, path: string, operation: Operation): ApiOperation {
  // Javadoc 첫 문장의 마침표 유무가 섞여 있어 화면에서는 빼고 표시
  const written = operation.summary?.trim() ?? "";
  const summary = written.replace(/\.$/, "") || `${method} ${path}`;
  const body = firstContent(operation.requestBody?.content);
  return {
    id: `op-${method}-${path}`
      .toLowerCase()
      .replace(/[^a-z0-9]+/g, "-")
      .replace(/-+$/, ""),
    method,
    path,
    summary,
    detail: detailText(written || summary, operation.description),
    open: Array.isArray(operation.security) && operation.security.length === 0,
    parameters: (operation.parameters ?? []).map((parameter) => ({
      name: parameter.name,
      in: parameter.in,
      required: parameter.required === true,
      type: typeLabel(parameter.schema),
      description: parameter.description ?? null,
    })),
    requestBody: body ? { type: typeLabel(body.schema), required: operation.requestBody?.required === true } : null,
    responses: Object.entries(operation.responses ?? {}).map(([status, response]) => {
      const content = firstContent(response.content);
      return { status, type: content?.schema ? typeLabel(content.schema) : null };
    }),
  };
}

function firstContent(content: Record<string, Content> | undefined): Content | undefined {
  return content ? Object.values(content)[0] : undefined;
}

/** 설명에서 요약과 겹치는 앞부분을 뺀 나머지. 남는 것이 없으면 null */
export function detailText(summary: string, description: string | undefined): string | null {
  if (!description) {
    return null;
  }
  const trimmed = description.trim();
  const rest = trimmed.startsWith(summary) ? trimmed.slice(summary.length).trim() : trimmed;
  return rest === "" ? null : rest;
}

/** 타입 표기. 예: string, integer, IdName, RunSummary[], date-time, "A | B" */
export function typeLabel(schema: Schema | undefined): string {
  if (!schema) {
    return "any";
  }
  if (schema.$ref) {
    return refName(schema.$ref);
  }
  const variants = schema.oneOf ?? schema.anyOf;
  if (variants) {
    return variants.map(typeLabel).join(" | ");
  }
  if (schema.enum) {
    return schema.enum.map(String).join(" | ");
  }
  const type = Array.isArray(schema.type) ? schema.type.filter((t) => t !== "null")[0] : schema.type;
  if (type === "array") {
    return `${typeLabel(schema.items)}[]`;
  }
  if (type === "object" || (!type && schema.additionalProperties)) {
    if (schema.additionalProperties && typeof schema.additionalProperties === "object") {
      return `map<string, ${typeLabel(schema.additionalProperties)}>`;
    }
    return "object";
  }
  if (type === "string" && schema.format && ["date", "date-time", "uri"].includes(schema.format)) {
    return schema.format;
  }
  return type ?? "object";
}

/** 타입 표기 안의 데이터 형식 이름 (화면에서 데이터 형식으로 이동하는 링크용) */
export function schemaNames(label: string): string[] {
  return label.match(/[A-Z][A-Za-z0-9]*/g) ?? [];
}

function refName(ref: string): string {
  return ref.slice(ref.lastIndexOf("/") + 1);
}

export interface SchemaField {
  name: string;
  type: string;
  required: boolean;
  description: string | null;
}

/** 데이터 형식 목록, 이름 순. 필드는 문서 순서 */
export function schemaList(
  doc: OpenApiDocument,
): { name: string; description: string | null; fields: SchemaField[] }[] {
  return Object.entries(doc.components?.schemas ?? {})
    .sort(([a], [b]) => a.localeCompare(b))
    .map(([name, schema]) => ({
      name,
      description: schema.description ?? null,
      fields: Object.entries(schema.properties ?? {}).map(([field, property]) => ({
        name: field,
        type: typeLabel(property),
        required: schema.required?.includes(field) ?? false,
        description: property.description ?? null,
      })),
    }));
}

export interface TextSegment {
  text: string;
  code: boolean;
}

/**
 * Javadoc에서 온 설명을 화면 문구로. <code>는 코드 글꼴 구간, 그 외 태그는 지우고 문자 참조는 풀어 둠 (HTML로 그리지 않음)
 */
export function richText(text: string): TextSegment[] {
  const segments: TextSegment[] = [];
  const pattern = /<code>([\s\S]*?)<\/code>/g;
  let last = 0;
  for (const match of text.matchAll(pattern)) {
    if (match.index > last) {
      segments.push({ text: plain(text.slice(last, match.index)), code: false });
    }
    segments.push({ text: plain(match[1]), code: true });
    last = match.index + match[0].length;
  }
  if (last < text.length) {
    segments.push({ text: plain(text.slice(last)), code: false });
  }
  return segments.filter((segment) => segment.text !== "");
}

function plain(text: string): string {
  return text
    .replace(/<[^>]+>/g, "")
    .replace(/&lt;/g, "<")
    .replace(/&gt;/g, ">")
    .replace(/&quot;/g, '"')
    .replace(/&#39;/g, "'")
    .replace(/&amp;/g, "&");
}
