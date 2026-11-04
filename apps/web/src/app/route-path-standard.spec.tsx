import { describe, expect, it } from 'vitest';
import routesConfig from './routes';

/**
 * Spec gác trên CHÍNH bảng route thật của app (`src/app/routes.ts`).
 *
 * Tiền thân của spec này khoá bản đồ route của lát R2–R4 (chuẩn đường dẫn #221) cho ba họ: khu vực phụ huynh
 * (`/guardian*`), Kho học liệu (`/content-bank*` kèm lớp tương thích `/contentbank*`) và họ `admin/curriculum`.
 * Cả ba họ thuộc các tính năng đã hoãn sau bản phát hành 07/11 và route của chúng không còn được đăng ký (issue
 * #79), nên các ca khoá bản đồ đó đã gỡ cùng bề mặt. Ca còn giữ là bất biến tổng quát của bảng route, độc lập với
 * từng họ: không hai path nào được dùng chung một route module — id route suy ra từ file, trùng là lỗi cấu hình
 * khi build.
 */

const ALL_ROUTES: Array<{ path: string; file: string }> = (() => {
  const collected: Array<{ path: string; file: string }> = [];
  const walk = (entries: unknown) => {
    if (!Array.isArray(entries)) return;
    for (const entry of entries as Array<{ path?: string; file?: string; children?: unknown }>) {
      if (typeof entry.path === 'string') collected.push({ path: entry.path, file: entry.file ?? '' });
      walk(entry.children);
    }
  };
  walk(routesConfig);
  return collected;
})();

describe('Bảng route của app', () => {
  it('không có hai path nào dùng chung một route module (id route suy ra từ file, trùng là lỗi cấu hình khi build)', () => {
    const byFile = new Map<string, string[]>();
    for (const route of ALL_ROUTES) {
      if (!route.file) continue;
      byFile.set(route.file, [...(byFile.get(route.file) ?? []), route.path]);
    }
    const duplicated = [...byFile.entries()].filter(([, paths]) => paths.length > 1);
    expect(duplicated).toEqual([]);
  });
});
