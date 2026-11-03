import { describe, expect, it } from 'vitest';
import { aggregateRoleResults, CAP_ALLOW, CAP_INHERIT, CAP_PREVENT, CAP_PROHIBIT, evaluateSingleRole, type RoleOverridePoint } from './permission';

/**
 * Bộ dữ kiện oracle bốn trạng thái ở tầng hàm thuần, dịch từ
 * `docs/access-oracle.md` (mục 3 và 4) và các ca nhóm A của
 * `docs/access-verification-fixtures.md`.
 *
 * Phạm vi trung thực của file này: chỉ kiểm chứng thuật toán thuần (`evaluateSingleRole` + `aggregateRoleResults`) —
 * tức một nhánh vai duyệt lá → gốc và phép tổng hợp nhiều nhánh. Các ca nghiệp vụ ở tầng service/người dùng (cổng
 * tenant, quan hệ phụ huynh, danh tính Keycloak, trạng thái bài lúc ghi) là dữ kiện đặc tả trong tài liệu dữ kiện,
 * KHÔNG được coi là đã chạy đạt nhờ file này.
 *
 * Quy ước dữ kiện: `points` của một nhánh liệt kê theo thứ tự
 * lá → gốc, đúng hợp đồng đầu vào của `evaluateSingleRole`.
 */
describe('Dữ kiện oracle bốn trạng thái (tầng hàm thuần)', () => {
  /** Dựng các điểm ghi đè của một nhánh theo thứ tự lá → gốc. */
  const branch = (...permissions: number[]): RoleOverridePoint[] =>
    permissions.map((permission, i) => ({ contextId: `ctx-${i + 1}`, permission }));

  /** Quyết định cuối: đánh giá từng nhánh rồi tổng hợp như evaluator làm. */
  const decide = (branches: number[][]): boolean =>
    aggregateRoleResults(branches.map((points) => evaluateSingleRole(branch(...points))));

  it('ca A1: PREVENT ở cha, ALLOW ở con trong cùng nhánh thì cho phép', () => {
    // Lá (module) ALLOW, cha (khoá) PREVENT: điểm gần lá nhất thắng trong nhánh.
    expect(decide([[CAP_ALLOW, CAP_PREVENT]])).toBe(true);
  });

  it('ca A2: PROHIBIT ở cha, ALLOW ở con trong cùng nhánh thì từ chối', () => {
    // Lá ALLOW, tổ tiên tenant PROHIBIT: PROHIBIT phủ quyết cả nhánh.
    expect(decide([[CAP_ALLOW, CAP_INHERIT, CAP_PROHIBIT]])).toBe(false);
  });

  it('ca A3: hai nhánh quyền, một PREVENT một ALLOW và không PROHIBIT thì cho phép', () => {
    expect(decide([[CAP_PREVENT], [CAP_ALLOW]])).toBe(true);
  });

  it('ca A4: hai nhánh quyền, một nhánh PROHIBIT thì từ chối dù nhánh còn lại ALLOW', () => {
    expect(decide([[CAP_ALLOW], [CAP_PROHIBIT]])).toBe(false);
  });

  it('ca A5: ghi đè cá nhân PREVENT ở nhánh riêng không chặn ALLOW của nhánh role', () => {
    // Nhánh cá nhân kết quả PREVENT, nhánh role kết quả ALLOW:
    // khác nhánh không có ưu tiên xuyên nhánh, một ALLOW là đủ.
    expect(decide([[CAP_PREVENT], [CAP_ALLOW]])).toBe(true);
  });

  it('ca A6: ghi đè cá nhân PROHIBIT ở tổ tiên phủ quyết ALLOW của nhánh role', () => {
    expect(decide([[CAP_ALLOW], [CAP_INHERIT, CAP_PROHIBIT]])).toBe(false);
  });

  it('ca B3 (tầng hàm): admin có ALLOW ở lá nhưng PROHIBIT ở tổ tiên thì vẫn bị chặn', () => {
    // Nhánh của vai quản trị: mặc định archetype là ALLOW ở gốc,
    // ghi đè PROHIBIT tại context tenant nằm giữa path — phủ quyết
    // áp dụng kể cả với vai quản trị, không có lối tắt trong oracle.
    expect(decide([[CAP_ALLOW, CAP_PROHIBIT, CAP_ALLOW]])).toBe(false);
  });

  it('kế thừa đi lên: toàn INHERIT thì từ chối, ALLOW ở gốc hệ thống thì cho phép', () => {
    expect(decide([[CAP_INHERIT, CAP_INHERIT, CAP_INHERIT]])).toBe(false);
    expect(decide([[CAP_INHERIT, CAP_INHERIT, CAP_ALLOW]])).toBe(true);
  });

  it('cùng nhánh: ghi đè đặt gần lá hơn thắng ghi đè ở tổ tiên bất kể do cá nhân hay role đặt', () => {
    // Điểm gần lá là PREVENT (đặt riêng), điểm ở gốc là ALLOW (mặc
    // định của role): kết quả của nhánh là PREVENT — thứ tự trong
    // nhánh chỉ theo độ gần context.
    expect(decide([[CAP_PREVENT, CAP_ALLOW]])).toBe(false);
    // Chiều ngược: điểm gần lá ALLOW thắng PREVENT ở tổ tiên.
    expect(decide([[CAP_ALLOW, CAP_PREVENT]])).toBe(true);
  });

  it('nhánh không có điểm ghi đè nào được tính là INHERIT và không tự cho phép', () => {
    expect(decide([[]])).toBe(false);
    expect(decide([[], [CAP_ALLOW]])).toBe(true);
  });
});
