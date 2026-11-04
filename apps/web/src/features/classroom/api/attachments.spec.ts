/**
 * Spec của các lời gọi tệp đính kèm ở tầng API của feature classroom (issue #119).
 *
 * Hành vi được kiểm chứng ở biên fetch, cùng khuôn với spec của client: mọi lời gọi làm mới token bằng
 * `updateToken(30)` và đính `Authorization: Bearer`; byte ảnh tải lên bằng `FormData` mà không đặt tay
 * `Content-Type` (trình duyệt tự đặt kèm boundary theo tài liệu MDN); không lời gọi nào đặt access token
 * vào URL; đáp ứng byte được trả nguyên dạng Blob để tầng hiển thị dùng blob URL.
 */
import { beforeEach, describe, expect, it, vi } from 'vitest';

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
 * @param init - Status, thân JSON và thân Blob (nếu có) của đáp ứng.
 * @returns Đối tượng đáp ứng giả có `ok`, `status`, `json()` và `blob()`.
 */
function fakeResponse(init: { status: number; body?: unknown; blob?: Blob }): Response {
  return {
    ok: init.status >= 200 && init.status < 300,
    status: init.status,
    json: async () => init.body,
    blob: async () => init.blob ?? new Blob([]),
  } as Response;
}

const SESSION = {
  sessionId: 'session-1',
  questionId: 'question-1',
  versionId: 'version-1',
  position: 'content',
  blockId: null,
  displayName: 'anh.png',
  status: 'ready',
  contentType: 'image/png',
  sizeBytes: 123,
  width: 9,
  height: 6,
  checksumSha256: 'abc123',
  blobId: null,
  linkId: null,
  expiresAt: '2026-11-06T00:00:00Z',
};

describe('lời gọi tệp đính kèm của feature classroom', () => {
  beforeEach(() => {
    vi.resetModules();
    vi.clearAllMocks();
    adapterMock.authenticated = true;
    adapterMock.token = 'access-token';
  });

  it('mở phiên tải lên bằng JSON đúng đường và đúng hình, kèm Bearer sau updateToken(30)', async () => {
    const fetchMock = vi.fn(async () => fakeResponse({ status: 200, body: { ...SESSION, status: 'uploading' } }));
    vi.stubGlobal('fetch', fetchMock);
    const { openAttachmentSession } = await import('./client');
    await expect(
      openAttachmentSession('question-1', 'version-1', { position: 'content', displayName: 'anh.png' }),
    ).resolves.toMatchObject({ sessionId: 'session-1', status: 'uploading' });
    expect(adapterMock.updateToken).toHaveBeenCalledWith(30);
    const [path, init] = fetchMock.mock.calls[0] as unknown as [string, RequestInit];
    expect(path).toBe('/api/questions/question-1/versions/version-1/attachments/sessions');
    const headers = (init.headers ?? {}) as Record<string, string>;
    expect(headers.Authorization).toBe('Bearer access-token');
    expect(JSON.parse(init.body as string)).toEqual({ position: 'content', blockId: null, displayName: 'anh.png' });
  });

  it('tải byte ảnh bằng FormData không đặt tay Content-Type và không đưa token vào URL', async () => {
    const fetchMock = vi.fn(async () => fakeResponse({ status: 200, body: SESSION }));
    vi.stubGlobal('fetch', fetchMock);
    const { uploadAttachmentContent } = await import('./client');
    const file = new File([new Uint8Array([1, 2, 3])], 'anh.png', { type: 'image/png' });
    await expect(uploadAttachmentContent('question-1', 'version-1', 'session-1', file)).resolves.toMatchObject({
      status: 'ready',
      checksumSha256: 'abc123',
    });
    const [path, init] = fetchMock.mock.calls[0] as unknown as [string, RequestInit];
    expect(path).toBe('/api/questions/question-1/versions/version-1/attachments/sessions/session-1/content');
    expect(path).not.toContain('access-token');
    expect(init.body).toBeInstanceOf(FormData);
    expect((init.body as FormData).get('file')).toBeInstanceOf(File);
    const headers = (init.headers ?? {}) as Record<string, string>;
    expect(headers.Authorization).toBe('Bearer access-token');
    expect(Object.keys(headers)).not.toContain('Content-Type');
  });

  it('hoàn tất phiên bằng POST thân rỗng tới đúng đường complete và đọc byte tệp thành Blob qua đường content', async () => {
    const blob = new Blob([new Uint8Array([9, 9])], { type: 'image/png' });
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(
        fakeResponse({
          status: 200,
          body: {
            linkId: 'link-1',
            questionId: 'question-1',
            versionId: 'version-1',
            position: 'content',
            blockId: null,
            blobId: 'blob-1',
            displayName: 'anh.png',
            contentType: 'image/png',
            sizeBytes: 2,
            checksumSha256: 'abc123',
          },
        }),
      )
      .mockResolvedValueOnce(fakeResponse({ status: 200, blob }));
    vi.stubGlobal('fetch', fetchMock);
    const { completeAttachmentSession, fetchAttachmentContent } = await import('./client');
    await expect(completeAttachmentSession('question-1', 'version-1', 'session-1')).resolves.toMatchObject({
      linkId: 'link-1',
    });
    const [completePath] = fetchMock.mock.calls[0] as unknown as [string, RequestInit];
    expect(completePath).toBe(
      '/api/questions/question-1/versions/version-1/attachments/sessions/session-1/complete',
    );
    await expect(fetchAttachmentContent('question-1', 'version-1', 'link-1')).resolves.toBe(blob);
    const [contentPath, contentInit] = fetchMock.mock.calls[1] as unknown as [string, RequestInit];
    expect(contentPath).toBe('/api/questions/question-1/versions/version-1/attachments/link-1/content');
    expect(contentPath).not.toContain('access-token');
    const headers = (contentInit.headers ?? {}) as Record<string, string>;
    expect(headers.Authorization).toBe('Bearer access-token');
  });
});
