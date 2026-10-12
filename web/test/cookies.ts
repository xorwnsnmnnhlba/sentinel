/** 테스트용 쿠키 저장소. next/headers의 cookies()를 대체 (요청 범위 밖에서는 호출 불가) */
export const testCookies = new Map<string, string>();

/** set() 호출 시 지정한 속성 (httpOnly, maxAge 등) */
export const testCookieOptions = new Map<string, Record<string, unknown>>();

export const cookieStore = {
  get(name: string) {
    const value = testCookies.get(name);
    return value === undefined ? undefined : { name, value };
  },
  set(name: string, value: string, options: Record<string, unknown> = {}) {
    testCookies.set(name, value);
    testCookieOptions.set(name, options);
  },
  /** 이름만 또는 속성과 함께 (next/headers와 같은 두 형태). 속성은 testCookieOptions에 기록 */
  delete(target: string | ({ name: string } & Record<string, unknown>)) {
    if (typeof target === "string") {
      testCookies.delete(target);
      return;
    }
    const { name, ...options } = target;
    testCookies.delete(name);
    testCookieOptions.set(name, options);
  },
  has(name: string) {
    return testCookies.has(name);
  },
};
