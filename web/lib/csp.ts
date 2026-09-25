/**
 * Content-Security-Policy. 요청마다 새 nonce를 만들어 proxy에서 헤더로 전달하면 Next.js가 렌더링 중
 * 프레임워크·페이지 스크립트와 인라인 스타일에 같은 nonce를 붙임 (모든 화면이 동적 렌더링이라 가능)
 *
 * - 스크립트: nonce가 있는 스크립트와 그 스크립트가 불러온 스크립트만 ('strict-dynamic'). 주입된 인라인 스크립트 차단
 * - 스타일 요소: 자기 출처 파일과 nonce가 있는 <style>만. 스타일 속성(style="")은 차트·막대·Radix 위치 계산에 쓰여 허용
 * - upgrade-insecure-requests는 쓰지 않음. HTTP→HTTPS는 앞단(Caddy)이 처리하고, 같은 PC의 http://localhost:3000 접속을 깨뜨리지 않기 위함
 * - 개발 서버: React 디버그 정보용 eval, 핫 리로드 웹소켓, 개발용 인라인 스타일 허용 (운영 빌드에는 미포함)
 */
export function contentSecurityPolicy(nonce: string, development: boolean): string {
  const styleElements = development ? "'self' 'unsafe-inline'" : `'self' 'nonce-${nonce}'`;
  return [
    "default-src 'self'",
    `script-src 'self' 'nonce-${nonce}' 'strict-dynamic'${development ? " 'unsafe-eval'" : ""}`,
    `style-src ${styleElements}`,
    `style-src-elem ${styleElements}`,
    "style-src-attr 'unsafe-inline'",
    "img-src 'self' blob: data:",
    "font-src 'self'",
    `connect-src 'self'${development ? " ws: wss:" : ""}`,
    "object-src 'none'",
    "base-uri 'self'",
    "form-action 'self'",
    "frame-ancestors 'none'",
  ].join("; ");
}

/** 추측할 수 없는 요청별 값 (16바이트 난수, Base64) */
export function createNonce(): string {
  const bytes = crypto.getRandomValues(new Uint8Array(16));
  return btoa(String.fromCharCode(...bytes));
}
