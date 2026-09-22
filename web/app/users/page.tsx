import { headers } from "next/headers";

import { InviteDialog } from "@/components/auth/invite-dialog";
import { ResetLinkButton } from "@/components/auth/reset-link-button";
import { DeleteButton } from "@/components/catalog/delete-button";
import { Empty, RowActions } from "@/components/catalog/section";
import { Badge } from "@/components/ui/badge";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { deleteUser } from "@/lib/actions/users";
import { apiGet } from "@/lib/api/server";
import type { AuthenticatedUser, UserSummary } from "@/lib/api/types";
import { formatDateTime } from "@/lib/format";

export const metadata = { title: "사용자 · Sentinel" };

/** 관리자 전용. 일반 사용자는 Spring이 403으로 응답하여 오류 화면 표시 */
export default async function UsersPage() {
  const [users, me, baseUrl] = await Promise.all([
    apiGet<UserSummary[]>("/api/users"),
    apiGet<AuthenticatedUser>("/api/sessions/current"),
    siteUrl(),
  ]);

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-end justify-between gap-4">
        <div className="space-y-1">
          <h1 className="text-2xl font-semibold tracking-tight">사용자</h1>
          <p className="text-sm text-muted-foreground">가입은 초대 링크로만 가능합니다</p>
        </div>
        <InviteDialog baseUrl={baseUrl} />
      </div>

      {users.length === 0 ? (
        <Empty message="사용자가 없습니다" />
      ) : (
        <div className="rounded-lg border">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>이메일</TableHead>
                <TableHead>이름</TableHead>
                <TableHead>역할</TableHead>
                <TableHead>등록 (KST)</TableHead>
                <TableHead className="w-24" />
              </TableRow>
            </TableHeader>
            <TableBody>
              {users.map((user) => (
                <TableRow key={user.id}>
                  <TableCell className="font-medium">
                    {user.email}
                    {user.id === me.id && <span className="ml-2 text-xs text-muted-foreground">(나)</span>}
                  </TableCell>
                  <TableCell>{user.name}</TableCell>
                  <TableCell>
                    <Badge variant={user.role === "ADMIN" ? "default" : "secondary"}>
                      {user.role === "ADMIN" ? "관리자" : "구성원"}
                    </Badge>
                  </TableCell>
                  <TableCell className="font-mono text-xs text-muted-foreground">
                    {formatDateTime(user.createdAt)}
                  </TableCell>
                  <TableCell>
                    <RowActions>
                      <ResetLinkButton userId={user.id} email={user.email} baseUrl={baseUrl} />
                      {user.id !== me.id && (
                        <DeleteButton
                          title={`사용자 "${user.email}" 삭제`}
                          description="로그인할 수 없게 되고 사용 중인 세션도 모두 종료됩니다. 실행 이력은 유지됩니다."
                          action={deleteUser.bind(null, user.id)}
                        />
                      )}
                    </RowActions>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </div>
      )}
    </div>
  );
}

/** 초대·재설정 링크에 쓸 주소. 요청 헤더 기준이라 배포 주소를 따로 설정하지 않아도 됨 */
async function siteUrl(): Promise<string> {
  const header = await headers();
  const host = header.get("x-forwarded-host") ?? header.get("host") ?? "localhost:3000";
  const protocol = header.get("x-forwarded-proto") ?? (host.startsWith("localhost") ? "http" : "https");
  return `${protocol}://${host}`;
}
