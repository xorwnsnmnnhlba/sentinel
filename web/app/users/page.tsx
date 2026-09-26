import { headers } from "next/headers";

import { InviteDialog } from "@/components/auth/invite-dialog";
import { ResetLinkDialog } from "@/components/auth/reset-link-dialog";
import { ActionMenu, type ActionMenuItem } from "@/components/catalog/action-menu";
import { DeleteDialog } from "@/components/catalog/delete-dialog";
import { Empty, TableFrame } from "@/components/catalog/section";
import { PageHeader } from "@/components/page-header";
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
      <PageHeader
        title="사용자"
        description="가입은 초대 링크로만 가능합니다"
        actions={<InviteDialog baseUrl={baseUrl} />}
      />

      {users.length === 0 ? (
        <Empty message="사용자가 없습니다" />
      ) : (
        <TableFrame>
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>이메일</TableHead>
                <TableHead>이름</TableHead>
                <TableHead>역할</TableHead>
                <TableHead>등록 (KST)</TableHead>
                <TableHead className="w-12">
                  <span className="sr-only">작업</span>
                </TableHead>
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
                  <TableCell className="text-right">
                    <ActionMenu label={`${user.email} 작업`} items={userMenu(user.id !== me.id)}>
                      <ResetLinkDialog menuId="reset" userId={user.id} email={user.email} baseUrl={baseUrl} />
                      {user.id !== me.id && (
                        <DeleteDialog
                          menuId="delete"
                          title={`사용자 "${user.email}" 삭제`}
                          description="로그인할 수 없게 되고 사용 중인 세션도 모두 종료됩니다. 실행 이력은 유지됩니다."
                          action={deleteUser.bind(null, user.id)}
                        />
                      )}
                    </ActionMenu>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </TableFrame>
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

/** 본인 계정은 삭제 불가 */
function userMenu(deletable: boolean): ActionMenuItem[] {
  const reset: ActionMenuItem = { id: "reset", label: "비밀번호 재설정 링크", icon: "key" };
  return deletable ? [reset, { id: "delete", label: "삭제", icon: "delete", destructive: true }] : [reset];
}
