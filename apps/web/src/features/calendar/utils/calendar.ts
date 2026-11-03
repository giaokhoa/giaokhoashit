
/**
 * Múi giờ nghiệp vụ của ứng dụng (trường học Việt Nam): mọi khoảng
 * ngày "tháng hiện tại" / "N ngày tới" mà query key phụ thuộc vào đều
 * tính theo múi giờ này ở CẢ hai phía server và client.
 *
 * Lý do phải ghim một múi giờ thay vì múi giờ máy chạy (lát SL-1 của #7): query key của sự kiện lịch chứa đúng chuỗi
 * from/to, nên loader phía server chỉ dehydrate trúng key khi nó tính ra ĐÚNG chuỗi mà phía client sẽ tính. Tiến trình
 * SSR chạy múi giờ UTC còn trình duyệt của người dùng ở Việt Nam — tính theo giờ máy là hai phía lệch nhau và dữ liệu
 * hydrate không bao giờ khớp key. Ghim Asia/Ho_Chi_Minh (không có giờ mùa, lệch UTC cố định) làm hai phía tất định như
 * nhau; người dùng ngoài Việt Nam cũng xem lịch theo ngày Việt Nam, đúng bối cảnh nghiệp vụ của hệ.
 */
export const APP_TIME_ZONE = 'Asia/Ho_Chi_Minh';

const zonedDayPartsFormatter = new Intl.DateTimeFormat('en-US', {
  timeZone: APP_TIME_ZONE,
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
});

const zonedClockPartsFormatter = new Intl.DateTimeFormat('en-US', {
  timeZone: APP_TIME_ZONE,
  hourCycle: 'h23',
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
  hour: '2-digit',
  minute: '2-digit',
  second: '2-digit',
});

function partNumber(parts: Intl.DateTimeFormatPart[], type: string): number {
  return Number(parts.find((p) => p.type === type)?.value ?? '0');
}

function zonedDateParts(instant: Date): { year: number; month: number; day: number } {
  const parts = zonedDayPartsFormatter.formatToParts(instant);
  return { year: partNumber(parts, 'year'), month: partNumber(parts, 'month'), day: partNumber(parts, 'day') };
}

function zonedOffsetMs(instant: Date): number {
  const parts = zonedClockPartsFormatter.formatToParts(instant);
  const asUtcMs = Date.UTC(
    partNumber(parts, 'year'),
    partNumber(parts, 'month') - 1,
    partNumber(parts, 'day'),
    partNumber(parts, 'hour'),
    partNumber(parts, 'minute'),
    partNumber(parts, 'second'),
  );
  return asUtcMs - Math.floor(instant.getTime() / 1000) * 1000;
}

/**
 * Mốc UTC của 00:00:00.000 một ngày lịch theo múi giờ ứng dụng: đoán
 * nửa đêm UTC của ngày đó, trừ độ lệch múi giờ đo tại mốc đoán (độ
 * lệch không đổi trong một ngày nên một lượt hiệu chỉnh là đủ).
 */
function zonedMidnightUtcMs(year: number, month: number, day: number): number {
  const guessUtcMs = Date.UTC(year, month - 1, day);
  return guessUtcMs - zonedOffsetMs(new Date(guessUtcMs));
}

/**
 * Khoảng sự kiện của THÁNG HIỆN TẠI theo múi giờ ứng dụng, dạng ISO (from = 00:00 ngày 1, to = 23:59:59.999 ngày cuối
 * tháng). Trang lịch và loader SL-1 của nó dùng CHUNG hàm này nên query key `calendarEvents(from, to)` khớp tuyệt đối
 * giữa hai phía.
 */
export function currentMonthRangeIso(now: Date = new Date()): { from: string; to: string } {
  const { year, month } = zonedDateParts(now);
  const fromMs = zonedMidnightUtcMs(year, month, 1);
  const nextYear = month === 12 ? year + 1 : year;
  const nextMonth = month === 12 ? 1 : month + 1;
  const toMs = zonedMidnightUtcMs(nextYear, nextMonth, 1) - 1;
  return { from: new Date(fromMs).toISOString(), to: new Date(toMs).toISOString() };
}

export function upcomingDaysRangeIso(days: number, now: Date = new Date()): { from: string; to: string } {
  const { year, month, day } = zonedDateParts(now);
  const fromMs = zonedMidnightUtcMs(year, month, day);
  return { from: new Date(fromMs).toISOString(), to: new Date(fromMs + days * 86_400_000).toISOString() };
}
