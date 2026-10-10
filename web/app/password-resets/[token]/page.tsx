import { ExpiredLink } from "@/components/auth/expired-link";
import { ResetPasswordForm } from "@/components/auth/reset-password-form";
import { apiGet, isApiError } from "@/lib/api/server";
import type { PasswordResetLinkInfo } from "@/lib/api/types";
import { formatDateTime } from "@/lib/format";

export const metadata = { title: "비밀번호 재설정 · Sentinel" };

export default async function ResetPasswordPage(props: PageProps<"/password-resets/[token]">) {
  const { token } = await props.params;
  let reset: PasswordResetLinkInfo;
  try {
    reset = await apiGet<PasswordResetLinkInfo>(`/api/password-resets/${token}`);
  } catch (error) {
    if (isApiError(error) && error.status === 404) {
      return (
        <ExpiredLink description="재설정 링크가 만료되었거나 이미 사용되었습니다. 관리자에게 새 링크를 요청해 주세요" />
      );
    }
    throw error;
  }

  return (
    <div className="space-y-6">
      <div className="space-y-1">
        <h1 className="text-2xl font-semibold tracking-tight">비밀번호 재설정</h1>
        <p className="text-sm text-muted-foreground">
          <span className="font-mono">{reset.user.name}</span> 계정의 새 비밀번호를 설정합니다
        </p>
        <p className="text-xs text-muted-foreground">링크 만료 {formatDateTime(reset.expiresAt)} KST</p>
      </div>
      <ResetPasswordForm token={token} />
    </div>
  );
}
