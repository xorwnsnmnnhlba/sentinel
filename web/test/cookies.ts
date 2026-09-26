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
  delete(name: string) {
    testCookies.delete(name);
  },
  has(name: string) {
    return testCookies.has(name);
  },
};
