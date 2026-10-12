/**
 * 초대·재설정 링크 확인 API 경로. 토큰은 주소의 한 조각으로만 쓰이도록 인코딩
 * (화면 주소의 토큰은 Next.js가 풀어서 전달하므로 "../users/1" 같은 값이 다른 API 경로가 되지 않도록)
 */
export function inviteApiPath(token: string): string {
  return `/api/invites/${encodeURIComponent(token)}`;
}

export function passwordResetApiPath(token: string): string {
  return `/api/password-resets/${encodeURIComponent(token)}`;
}
