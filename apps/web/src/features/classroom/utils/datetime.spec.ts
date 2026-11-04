/**
 * Spec của các tiện ích thời gian của feature classroom (issue #102).
 *
 * Hành vi được kiểm chứng: mốc rỗng hiển thị dấu gạch ngang thay vì ngày tháng vô nghĩa; giá trị của ô nhập
 * `datetime-local` chuyển thành chuỗi ISO đầy đủ có múi giờ để gửi lên server; giá trị rỗng/hỏng cho `null` để
 * form chặn tại chỗ thay vì gửi dữ kiện sai.
 */
import { describe, expect, it } from 'vitest';
import { formatDateTime, localInputToIso } from './datetime';

describe('formatDateTime', () => {
  it('mốc rỗng hoặc không đọc được hiển thị dấu gạch ngang', () => {
    expect(formatDateTime(null)).toBe('—');
    expect(formatDateTime(undefined)).toBe('—');
    expect(formatDateTime('không-phải-ngày')).toBe('—');
  });

  it('mốc ISO hợp lệ được định dạng thành chuỗi ngày giờ', () => {
    expect(formatDateTime('2026-11-05T08:30:00.000Z')).not.toBe('—');
  });
});

describe('localInputToIso', () => {
  it('giá trị datetime-local hợp lệ chuyển thành ISO có múi giờ và giữ đúng thời điểm', () => {
    const local = '2026-11-05T08:30';
    const iso = localInputToIso(local);
    expect(iso).not.toBeNull();
    expect(new Date(iso as string).getTime()).toBe(new Date(local).getTime());
  });

  it('giá trị rỗng hoặc hỏng cho null', () => {
    expect(localInputToIso('')).toBeNull();
    expect(localInputToIso('giờ-hỏng')).toBeNull();
  });
});
