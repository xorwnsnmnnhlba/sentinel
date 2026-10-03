import { IssueTokenDialog } from "@/components/auth/issue-token-dialog";
import { ActionMenu } from "@/components/catalog/action-menu";
import { DeleteDialog } from "@/components/catalog/delete-dialog";
import { Panel, PanelEmpty } from "@/components/catalog/section";
import { PageHeader } from "@/components/page-header";
import { Badge } from "@/components/ui/badge";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { runCommand } from "@/lib/access-token";
import { issueAccessToken, revokeAccessToken } from "@/lib/actions/access-tokens";
import { apiGet } from "@/lib/api/server";
import type { AccessTokenItem } from "@/lib/api/types";
import { formatDateTime } from "@/lib/format";
import { siteUrl } from "@/lib/site-url";

export const metadata = { title: "API 토큰 · Sentinel" };

/** 내 API 토큰. CI 등 자동화에서 스위트를 실행하고 결과를 확인하는 용도 */
export default async function AccessTokensPage() {
  const [tokens, baseUrl] = await Promise.all([apiGet<AccessTokenItem[]>("/api/access-tokens"), siteUrl()]);

  return (
    <div className="space-y-4">
      <PageHeader title="API 토큰" actions={<IssueTokenDialog action={issueAccessToken} />} />

      <Panel title="내 토큰" count={tokens.length} meta="실행 시작·결과 조회 전용">
        {tokens.length === 0 ? (
          <PanelEmpty message="발급한 토큰이 없습니다" />
        ) : (
          <Table aria-label="내 토큰">
            <TableHeader>
              <TableRow>
                <TableHead>이름</TableHead>
                <TableHead>토큰</TableHead>
                <TableHead>만료 (KST)</TableHead>
                <TableHead>마지막 사용</TableHead>
                <TableHead className="w-12">
                  <span className="sr-only">작업</span>
                </TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {tokens.map((token) => (
                <TableRow key={token.id}>
                  <TableCell className="font-medium whitespace-normal">{token.name}</TableCell>
                  <TableCell className="font-mono text-xs">{token.prefix}…</TableCell>
                  <TableCell className="font-mono text-xs">
                    <div className="flex items-center gap-2">
                      {formatDateTime(token.expiresAt)}
                      {token.expired && <Badge variant="outline">만료됨</Badge>}
                    </div>
                  </TableCell>
                  <TableCell className="font-mono text-xs text-muted-foreground">
                    {token.lastUsedAt ? formatDateTime(token.lastUsedAt) : "사용 전"}
                  </TableCell>
                  <TableCell className="text-right">
                    <ActionMenu
                      label={`토큰 ${token.name} 작업`}
                      items={[{ id: "delete", label: "폐기", icon: "delete", destructive: true }]}
                    >
                      <DeleteDialog
                        menuId="delete"
                        title={`토큰 "${token.name}" 폐기`}
                        description="이 토큰을 쓰는 CI의 요청은 바로 실패합니다(401). 되돌릴 수 없습니다."
                        confirmLabel="폐기"
                        action={revokeAccessToken.bind(null, token.id)}
                      />
                    </ActionMenu>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        )}
      </Panel>

      <Panel title="CI에서 사용" meta="scripts/sentinel-run.sh">
        <pre className="overflow-x-auto border-b bg-muted p-4 font-mono text-xs">{runCommand(baseUrl)}</pre>
        <p className="p-4 text-xs text-muted-foreground">
          끝날 때까지 기다린 뒤 모두 통과 0, 실패 1, 실행하지 못함 2로 끝남. 스위트 ID는 스위트 화면
          주소(/suites/번호)의 번호, 환경 ID는 프로젝트 화면 환경 이름 옆의 번호. 사내 인증 기관 인증서를 쓰면
          SENTINEL_CA_CERT에 루트 인증서 경로 지정. GitHub Actions 예시는 저장소의 api/README.md &quot;API 토큰&quot;
        </p>
      </Panel>
    </div>
  );
}
