import Link from "next/link";

import { MethodBadge } from "@/components/catalog/method-badge";
import { Panel, PanelEmpty } from "@/components/catalog/section";
import { PageHeader } from "@/components/page-header";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { apiGet } from "@/lib/api/server";
import type { CaseItem, SuiteItem } from "@/lib/api/types";
import { MAX_QUERY_LENGTH, MAX_SEARCH_RESULTS, splitMatches } from "@/lib/search";

export const metadata = { title: "케이스 검색 · Sentinel" };

/**
 * 모든 스위트의 케이스를 이름·경로로 검색 (대소문자 무시). GET 폼이라 검색어가 주소에 남음
 * GW는 경로 접두어로 모듈을 나누므로 경로 일부(예: /billing/v1)로 해당 모듈을 검사하는 케이스를 찾는 용도
 */
export default async function SearchPage(props: PageProps<"/search">) {
  const searchParams = await props.searchParams;
  const raw = single(searchParams.q) ?? "";
  const query = raw.trim().slice(0, MAX_QUERY_LENGTH);

  // 케이스 응답에는 스위트까지, 스위트의 프로젝트 이름은 스위트 목록에서
  const [cases, suites] =
    query === ""
      ? [null, []]
      : await Promise.all([
          apiGet<CaseItem[]>(`/api/cases?${new URLSearchParams({ q: query })}`),
          apiGet<SuiteItem[]>("/api/suites"),
        ]);
  const projectBySuite = new Map(suites.map((suite) => [suite.id, suite.project.name]));

  return (
    <div className="space-y-4">
      <PageHeader title="케이스 검색" />

      <form method="get" action="/search" role="search" aria-label="케이스 검색" className="flex gap-2">
        <Input
          name="q"
          type="search"
          aria-label="검색어"
          defaultValue={raw}
          maxLength={MAX_QUERY_LENGTH}
          placeholder="/billing/v1 또는 결제 승인"
          className="max-w-xl font-mono"
        />
        <Button type="submit">검색</Button>
      </form>

      {cases === null ? null : (
        <Panel
          title="검색 결과"
          count={cases.length}
          meta={
            cases.length >= MAX_SEARCH_RESULTS ? `${MAX_SEARCH_RESULTS}건까지 표시 · 검색어를 구체적으로` : undefined
          }
        >
          {cases.length === 0 ? (
            <PanelEmpty
              message={<>&quot;{query}&quot; 검색 결과가 없습니다. 이름이나 경로의 다른 부분으로 찾아보세요</>}
            />
          ) : (
            <Table aria-label="검색 결과">
              <TableHeader>
                <TableRow>
                  <TableHead>케이스</TableHead>
                  <TableHead>요청</TableHead>
                  <TableHead>스위트</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {cases.map((testCase) => {
                  return (
                    <TableRow key={testCase.id}>
                      <TableCell className="font-medium whitespace-normal">
                        <Link href={`/cases/${testCase.id}`} className="hover:underline">
                          <Highlighted text={testCase.name} query={query} />
                        </Link>
                      </TableCell>
                      <TableCell className="min-w-72 whitespace-normal">
                        <div className="flex items-start gap-2 font-mono text-xs">
                          <MethodBadge method={testCase.request.method} />
                          <span className="[overflow-wrap:anywhere]">
                            <Highlighted text={testCase.request.path} query={query} />
                          </span>
                        </div>
                      </TableCell>
                      <TableCell className="whitespace-normal">
                        <Link href={`/suites/${testCase.suite.id}`} className="hover:underline">
                          {testCase.suite.name}
                        </Link>
                        <div className="text-xs text-muted-foreground">{projectBySuite.get(testCase.suite.id)}</div>
                      </TableCell>
                    </TableRow>
                  );
                })}
              </TableBody>
            </Table>
          )}
        </Panel>
      )}
    </div>
  );
}

/** 검색어와 일치하는 부분 강조 */
function Highlighted({ text, query }: { text: string; query: string }) {
  return (
    <>
      {splitMatches(text, query).map((part, index) =>
        part.match ? (
          <mark key={index} className="rounded-sm bg-amber-200 text-foreground dark:bg-amber-500/40">
            {part.text}
          </mark>
        ) : (
          <span key={index}>{part.text}</span>
        ),
      )}
    </>
  );
}

function single(value: string | string[] | undefined): string | undefined {
  return Array.isArray(value) ? value[0] : value;
}
