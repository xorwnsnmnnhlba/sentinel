import "@testing-library/jest-dom/vitest";

import { cleanup } from "@testing-library/react";
import { afterEach } from "vitest";

afterEach(() => cleanup());

// Radix UI(Select 등)가 사용하지만 jsdom에 없는 브라우저 API
Element.prototype.hasPointerCapture ??= () => false;
Element.prototype.releasePointerCapture ??= () => {};
Element.prototype.scrollIntoView ??= () => {};
globalThis.ResizeObserver ??= function ResizeObserver() {
  return { observe() {}, unobserve() {}, disconnect() {} };
} as unknown as typeof ResizeObserver;
