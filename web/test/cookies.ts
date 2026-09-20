/** 테스트용 쿠키 저장소. next/headers의 cookies()를 대체 (요청 범위 밖에서는 호출 불가) */
export const testCookies = new Map<string, string>();

export const cookieStore = {
  get(name: string) {
    const value = testCookies.get(name);
    return value === undefined ? undefined : { name, value };
  },
  set(name: string, value: string) {
    testCookies.set(name, value);
  },
  delete(name: string) {
    testCookies.delete(name);
  },
  has(name: string) {
    return testCookies.has(name);
  },
};
