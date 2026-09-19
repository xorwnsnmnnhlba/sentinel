/**
 * next/navigation의 notFound()·redirect()는 digest가 있는 오류를 throw하여 Next.js가 처리.
 * 테스트에서는 throw된 오류의 digest로 결과 확인
 */
export async function captureNavigation(run: () => Promise<unknown>): Promise<string> {
  try {
    await run();
  } catch (error) {
    const digest = (error as { digest?: unknown }).digest;
    if (typeof digest === "string") {
      return digest;
    }
    throw error;
  }
  throw new Error("expected notFound() or redirect()");
}

/** redirect() digest(NEXT_REDIRECT;<type>;<url>;<status>;)에서 이동 경로 추출 */
export async function captureRedirect(run: () => Promise<unknown>): Promise<string> {
  const digest = await captureNavigation(run);
  const [kind, , url] = digest.split(";");
  if (kind !== "NEXT_REDIRECT") {
    throw new Error(`expected redirect but got ${digest}`);
  }
  return url;
}
