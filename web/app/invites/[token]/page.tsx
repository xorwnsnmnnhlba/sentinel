import { AcceptInviteForm } from "@/components/auth/accept-invite-form";
import { inviteApiPath } from "@/lib/api/link-paths";
import { apiGet, isApiError } from "@/lib/api/server";
import type { LinkInfo } from "@/lib/api/types";
import { ExpiredLink } from "@/components/auth/expired-link";
import { formatDateTime } from "@/lib/format";

export const metadata = { title: "초대 수락 · Sentinel" };

/** 로그인 전에 여는 화면. 토큰 자체가 자격증명이므로 세션 없이 조회 */
export default async function AcceptInvitePage(props: PageProps<"/invites/[token]">) {
  const { token } = await props.params;
  const invite = await linkInfo(inviteApiPath(token));
  if (!invite) {
    return (
      <ExpiredLink description="초대 링크가 만료되었거나 이미 사용되었습니다. 관리자에게 새 링크를 요청해 주세요" />
    );
  }

  return (
    <div className="space-y-6">
      <div className="space-y-1">
        <h1 className="text-2xl font-semibold tracking-tight">계정 만들기</h1>
        <p className="text-sm text-muted-foreground">
          <span className="font-mono">{invite.email}</span> 계정을 만듭니다
        </p>
        <p className="text-xs text-muted-foreground">링크 만료 {formatDateTime(invite.expiresAt)} KST</p>
      </div>
      <AcceptInviteForm token={token} />
    </div>
  );
}

async function linkInfo(path: string): Promise<LinkInfo | null> {
  try {
    return await apiGet<LinkInfo>(path);
  } catch (error) {
    if (isApiError(error) && error.status === 404) {
      return null;
    }
    throw error;
  }
}
