/**
 * Spec của tầng gọi API của feature classroom (issue #102).
 *
 * Hành vi được kiểm chứng ở biên fetch: mọi lời gọi làm mới token bằng `updateToken(30)` trên adapter dùng
 * chung và đính `Authorization: Bearer`; không lời gọi nào tự đặt header `X-LMS-Organization-Alias` (header ngữ
 * cảnh trang do tầng web phía server gắn theo hostname); lỗi của server được chuẩn hoá thành `ClassroomApiError`
 * giữ đúng status và mã lỗi; chưa đăng nhập thì ném 401 mà không gọi mạng.
 *
 * Adapter được mock ở biên package `keycloak-js` (cùng khuôn với spec của feature auth), fetch được stub toàn
 * cục; mỗi test nạp lại module để instance dùng chung được tạo mới.
 */
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ClassroomApiError } from './errors';

const adapterMock = vi.hoisted(() => ({
  authenticated: true as boolean | undefined,
  token: 'access-token',
  tokenParsed: undefined,
  updateToken: vi.fn(async () => true),
  login: vi.fn(async () => undefined),
  logout: vi.fn(async () => undefined),
  init: vi.fn(async () => true),
}));

vi.mock('keycloak-js', () => ({
  default: class MockKeycloak {
    constructor() {
      return adapterMock;
    }
  },
}));

/**
 * Dựng một đáp ứng fetch tối thiểu cho các test của file này.
 *
 * @param init - Status, thân JSON (nếu có) của đáp ứng.
 * @returns Đối tượng đáp ứng giả có `ok`, `status` và `json()`.
 */
function fakeResponse(init: { status: number; body?: unknown }): Response {
  return {
    ok: init.status >= 200 && init.status < 300,
    status: init.status,
    json: async () => init.body,
  } as Response;
}

describe('client của feature classroom (biên gọi apps/server)', () => {
  beforeEach(() => {
    vi.resetModules();
    vi.clearAllMocks();
    adapterMock.authenticated = true;
    adapterMock.token = 'access-token';
  });

  it('làm mới token bằng updateToken(30) và đính Bearer vào lời gọi, không tự đặt header ngữ cảnh trang', async () => {
    const fetchMock = vi.fn(async () => fakeResponse({ status: 200, body: [] }));
    vi.stubGlobal('fetch', fetchMock);
    const { fetchClasses } = await import('./client');
    await expect(fetchClasses()).resolves.toEqual([]);
    expect(adapterMock.updateToken).toHaveBeenCalledWith(30);
    const [path, init] = fetchMock.mock.calls[0] as unknown as [string, RequestInit];
    expect(path).toBe('/api/classes');
    const headers = (init.headers ?? {}) as Record<string, string>;
    expect(headers.Authorization).toBe('Bearer access-token');
    expect(Object.keys(headers)).not.toContain('X-LMS-Organization-Alias');
  });

  it('gửi thân JSON đúng hình khi tạo lớp và ghi danh', async () => {
    const fetchMock = vi.fn(async () =>
      fakeResponse({ status: 201, body: { id: 'class-1', name: '6A1', courseId: null, teacherSub: null } }),
    );
    vi.stubGlobal('fetch', fetchMock);
    const { createClass } = await import('./client');
    await createClass({ name: '6A1' });
    const [path, init] = fetchMock.mock.calls[0] as unknown as [string, RequestInit];
    expect(path).toBe('/api/classes');
    expect(init.method).toBe('POST');
    expect(JSON.parse(init.body as string)).toEqual({ name: '6A1', courseId: null });
  });

  it('chuẩn hoá lỗi của server thành ClassroomApiError giữ status và mã lỗi', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => fakeResponse({ status: 404, body: { code: 'RESOURCE_NOT_FOUND', message: 'Không thấy.' } })),
    );
    const { fetchClasses } = await import('./client');
    // Lớp lỗi phải lấy từ cùng bản module vừa nạp lại (resetModules tạo bản sao mới của module errors).
    const { ClassroomApiError: FreshClassroomApiError } = await import('./errors');
    const error = await fetchClasses().catch((caught: unknown) => caught);
    expect(error).toBeInstanceOf(FreshClassroomApiError);
    expect((error as ClassroomApiError).status).toBe(404);
    expect((error as ClassroomApiError).code).toBe('RESOURCE_NOT_FOUND');
  });

  it('đáp ứng 204 (gỡ ghi danh, gỡ mục ngân hàng) hoàn thành mà không cần thân', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => fakeResponse({ status: 204 })));
    const { removeMember } = await import('./client');
    await expect(removeMember('class-1', 'student-1')).resolves.toBeUndefined();
  });

  it('chưa đăng nhập thì ném lỗi 401 và không gọi mạng', async () => {
    adapterMock.authenticated = false;
    const fetchMock = vi.fn(async () => fakeResponse({ status: 200, body: [] }));
    vi.stubGlobal('fetch', fetchMock);
    const { fetchClasses } = await import('./client');
    const { ClassroomApiError: FreshClassroomApiError } = await import('./errors');
    const error = await fetchClasses().catch((caught: unknown) => caught);
    expect(error).toBeInstanceOf(FreshClassroomApiError);
    expect((error as ClassroomApiError).status).toBe(401);
    expect(fetchMock).not.toHaveBeenCalled();
  });
});
