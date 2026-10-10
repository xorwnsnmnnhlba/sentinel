import { Download } from "lucide-react";

import { DocText, TypeLabel } from "@/components/api-docs/doc-text";
import { MethodBadge } from "@/components/catalog/method-badge";
import { Panel, PanelBody } from "@/components/catalog/section";
import { PageHeader } from "@/components/page-header";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { apiGet } from "@/lib/api/server";
import { type ApiOperation, groupOperations, type OpenApiDocument, schemaList } from "@/lib/openapi";

export const metadata = { title: "API 문서 · Sentinel" };

/**
 * API 문서. Spring이 만든 OpenAPI 문서(GET /api/openapi)를 묶음별로 표시. 설명은 컨트롤러 Javadoc에서 옴
 * 묶음마다 API를 접어 두고 펼치면 조회 조건·요청 본문·응답. 타입 이름은 아래 "데이터 형식"으로 이동
 */
export default async function ApiDocsPage() {
  const doc = await apiGet<OpenApiDocument>("/api/openapi");
  const groups = groupOperations(doc);
  const schemas = schemaList(doc);
  const schemaNames = new Set(schemas.map((schema) => schema.name));
  const total = groups.reduce((sum, group) => sum + group.operations.length, 0);

  return (
    <div className="space-y-4">
      <PageHeader
        title="API 문서"
        description={
          <span className="font-mono text-xs">
            {doc.info.version} · OpenAPI {doc.openapi} · {total}개
          </span>
        }
        actions={
          <Button asChild variant="outline">
            {/* 브라우저용 조회 프록시(/api/**)로 같은 문서를 파일로 */}
            <a href="/api/openapi" download="sentinel-openapi.json">
              <Download /> JSON 내려받기
            </a>
          </Button>
        }
      />

      {doc.info.description && (
        <Panel title="개요" meta="Bearer 토큰 (로그인 세션 또는 API 토큰)">
          <PanelBody>
            <p className="text-sm whitespace-pre-line text-muted-foreground">
              <DocText text={doc.info.description} />
            </p>
          </PanelBody>
        </Panel>
      )}

      <div className="grid gap-4 lg:grid-cols-[13rem_minmax(0,1fr)]">
        <nav aria-label="API 묶음" className="lg:sticky lg:top-4 lg:self-start">
          <ul className="flex flex-wrap gap-1 lg:flex-col">
            {groups.map((group, index) => (
              <li key={group.name}>
                <a
                  href={`#group-${index}`}
                  className="flex items-center justify-between gap-3 rounded-sm px-2 py-1 text-sm hover:bg-muted"
                >
                  {group.name}
                  <span className="font-mono text-xs text-muted-foreground">{group.operations.length}</span>
                </a>
              </li>
            ))}
            <li>
              <a href="#schemas" className="flex rounded-sm px-2 py-1 text-sm hover:bg-muted">
                데이터 형식
              </a>
            </li>
          </ul>
        </nav>

        <div className="min-w-0 space-y-4">
          {groups.map((group, index) => (
            <Panel
              key={group.name}
              id={`group-${index}`}
              title={group.name}
              count={group.operations.length}
              meta={group.description ?? undefined}
            >
              <ul className="divide-y">
                {group.operations.map((operation) => (
                  <li key={operation.id} id={operation.id}>
                    <OperationDetails operation={operation} schemas={schemaNames} />
                  </li>
                ))}
              </ul>
            </Panel>
          ))}

          <Panel id="schemas" title="데이터 형식" count={schemas.length} meta="요청 본문·응답의 모양">
            <div className="divide-y">
              {schemas.map((schema) => (
                <section key={schema.name} id={`schema-${schema.name}`} aria-label={schema.name} className="p-4">
                  <h3 className="font-mono text-sm font-semibold">{schema.name}</h3>
                  {schema.description && (
                    <p className="mt-1 text-xs text-muted-foreground">
                      <DocText text={schema.description} />
                    </p>
                  )}
                  {schema.fields.length > 0 && (
                    <Table className="mt-2">
                      <TableHeader>
                        <TableRow>
                          <TableHead className="w-48">필드</TableHead>
                          <TableHead className="w-56">타입</TableHead>
                          <TableHead>설명</TableHead>
                        </TableRow>
                      </TableHeader>
                      <TableBody>
                        {schema.fields.map((field) => (
                          <TableRow key={field.name}>
                            <TableCell className="font-mono text-xs">
                              {field.name}
                              {field.required && <span className="ml-1 text-destructive">*</span>}
                            </TableCell>
                            <TableCell className="whitespace-normal">
                              <TypeLabel label={field.type} schemas={schemaNames} />
                            </TableCell>
                            <TableCell className="text-xs whitespace-normal text-muted-foreground">
                              {field.description && <DocText text={field.description} />}
                            </TableCell>
                          </TableRow>
                        ))}
                      </TableBody>
                    </Table>
                  )}
                </section>
              ))}
            </div>
          </Panel>
        </div>
      </div>
    </div>
  );
}

/** API 하나. 접힌 줄은 메서드·경로·요약, 펼치면 설명·조회 조건·요청 본문·응답 */
function OperationDetails({ operation, schemas }: { operation: ApiOperation; schemas: Set<string> }) {
  return (
    <details className="group">
      <summary className="flex flex-wrap items-center gap-x-3 gap-y-1 px-4 py-2.5 hover:bg-muted/50">
        <MethodBadge method={operation.method} />
        <span className="font-mono text-sm">{operation.path}</span>
        <span className="text-sm text-muted-foreground">
          <DocText text={operation.summary} />
        </span>
        {operation.open && <Badge variant="outline">로그인 불필요</Badge>}
      </summary>
      <div className="space-y-3 border-t bg-muted/20 px-4 py-3 text-sm">
        {operation.detail && (
          <p className="whitespace-pre-line text-muted-foreground">
            <DocText text={operation.detail} />
          </p>
        )}
        {operation.parameters.length > 0 && (
          <div>
            <p className="mb-1 text-xs font-semibold text-muted-foreground">조건</p>
            <Table aria-label={`${operation.method} ${operation.path} 조건`}>
              <TableHeader>
                <TableRow>
                  <TableHead className="w-40">이름</TableHead>
                  <TableHead className="w-20">위치</TableHead>
                  <TableHead className="w-48">타입</TableHead>
                  <TableHead>설명</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {operation.parameters.map((parameter) => (
                  <TableRow key={`${parameter.in}-${parameter.name}`}>
                    <TableCell className="font-mono text-xs">
                      {parameter.name}
                      {parameter.required && <span className="ml-1 text-destructive">*</span>}
                    </TableCell>
                    <TableCell className="text-xs">{parameter.in === "path" ? "경로" : "조회"}</TableCell>
                    <TableCell className="whitespace-normal">
                      <TypeLabel label={parameter.type} schemas={schemas} />
                    </TableCell>
                    <TableCell className="text-xs whitespace-normal text-muted-foreground">
                      {parameter.description && <DocText text={parameter.description} />}
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </div>
        )}
        <dl className="grid grid-cols-[6rem_minmax(0,1fr)] gap-x-3 gap-y-1">
          {operation.requestBody && (
            <>
              <dt className="text-xs font-semibold text-muted-foreground">요청 본문</dt>
              <dd>
                <TypeLabel label={operation.requestBody.type} schemas={schemas} />
              </dd>
            </>
          )}
          {operation.responses.map((response) => (
            <div key={response.status} className="contents">
              <dt className="text-xs font-semibold text-muted-foreground">응답 {response.status}</dt>
              <dd>
                {response.type ? (
                  <TypeLabel label={response.type} schemas={schemas} />
                ) : (
                  <span className="text-xs text-muted-foreground">본문 없음</span>
                )}
              </dd>
            </div>
          ))}
        </dl>
      </div>
    </details>
  );
}
