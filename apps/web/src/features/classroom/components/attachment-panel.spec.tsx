/**
 * Spec của khung tệp đính kèm trên màn hình sửa câu (issue #119).
 *
 * Biên mock là tầng `api/client`; hook React Query và component chạy thật. Hành vi được kiểm chứng: luồng
 * tải lên đi đúng thứ tự mở phiên → tải byte → xem trước đúng bản đã xử lý kèm checksum → xác nhận gắn, và
 * sau khi gắn danh sách tệp của phiên bản tải lại từ server; tệp chắc chắn bị từ chối ở phép kiểm sớm thì
 * báo lỗi tại chỗ và không gọi API.
 */
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { AttachmentLinkView, QuestionVersionView, UploadSessionView } from '../types/server';
import { AttachmentPanel } from './attachment-panel';

const clientMock = vi.hoisted(() => ({
  fetchAttachments: vi.fn(),
  fetchAttachmentContent: vi.fn(),
  fetchAttachmentPreview: vi.fn(),
  openAttachmentSession: vi.fn(),
  uploadAttachmentContent: vi.fn(),
  completeAttachmentSession: vi.fn(),
}));

vi.mock('../api/client', () => clientMock);

vi.mock('../hooks/use-session', () => ({
  requestLogin: vi.fn(),
  useClassroomSession: () => ({ status: 'ready' }),
}));

const VERSION: QuestionVersionView = {
  versionId: 'version-1',
  versionNumber: 1,
  status: 'draft',
  lockedAt: null,
  contentHash: null,
  revision: 0,
  questionType: 'essay_text',
  stem: 'than-cau',
  choices: [],
};

const READY_SESSION: UploadSessionView = {
  sessionId: 'session-1',
  questionId: 'question-1',
  versionId: 'version-1',
  position: 'content',
  blockId: null,
  displayName: 'anh.png',
  status: 'ready',
  contentType: 'image/png',
  sizeBytes: 3,
  width: 9,
  height: 6,
  checksumSha256: 'deadbeefcafe',
  blobId: null,
  linkId: null,
  expiresAt: '2026-11-06T00:00:00Z',
};

const LINK: AttachmentLinkView = {
  linkId: 'link-1',
  questionId: 'question-1',
  versionId: 'version-1',
  position: 'content',
  blockId: null,
  blobId: 'blob-1',
  displayName: 'anh.png',
  contentType: 'image/png',
  sizeBytes: 3,
  checksumSha256: 'deadbeefcafe',
};

function renderPanel() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <AttachmentPanel questionId="question-1" version={VERSION} />
    </QueryClientProvider>,
  );
}

afterEach(() => {
  cleanup();
});

describe('khung tệp đính kèm của một phiên bản câu', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    let attached: AttachmentLinkView[] = [];
    clientMock.fetchAttachments.mockImplementation(async () => attached);
    clientMock.completeAttachmentSession.mockImplementation(async () => {
      attached = [LINK];
      return LINK;
    });
    clientMock.fetchAttachmentContent.mockResolvedValue(new Blob([new Uint8Array([1])], { type: 'image/png' }));
    clientMock.fetchAttachmentPreview.mockResolvedValue(new Blob([new Uint8Array([1])], { type: 'image/png' }));
    clientMock.openAttachmentSession.mockResolvedValue({ ...READY_SESSION, status: 'uploading' });
    clientMock.uploadAttachmentContent.mockResolvedValue(READY_SESSION);
    URL.createObjectURL = vi.fn(() => 'blob:preview-1');
    URL.revokeObjectURL = vi.fn();
  });

  it('đi trọn luồng tải lên: mở phiên, tải byte, xem trước kèm checksum của bản đã xử lý, xác nhận gắn và danh sách tải lại', async () => {
    const { container } = renderPanel();
    expect(await screen.findByText('Chưa có tệp đính kèm nào.')).toBeTruthy();
    const input = container.querySelector('input[type="file"]') as HTMLInputElement;
    const file = new File([new Uint8Array([1, 2, 3])], 'anh.png', { type: 'image/png' });
    fireEvent.change(input, { target: { files: [file] } });
    fireEvent.click(screen.getByRole('button', { name: 'Tải ảnh lên' }));
    expect(await screen.findByText(/checksum của bản sẽ gắn: deadbeefcafe/)).toBeTruthy();
    expect(clientMock.openAttachmentSession).toHaveBeenCalledWith('question-1', 'version-1', {
      position: 'content',
      displayName: 'anh.png',
    });
    expect(clientMock.uploadAttachmentContent).toHaveBeenCalledWith('question-1', 'version-1', 'session-1', file);
    fireEvent.click(screen.getByRole('button', { name: 'Gắn vào bản nháp' }));
    await waitFor(() => expect(clientMock.completeAttachmentSession).toHaveBeenCalledTimes(1));
    expect(await screen.findByText('anh.png')).toBeTruthy();
  });

  it('tệp vượt 5 MiB bị báo lỗi tại chỗ ở phép kiểm sớm và không gọi API nào', async () => {
    const { container } = renderPanel();
    expect(await screen.findByText('Chưa có tệp đính kèm nào.')).toBeTruthy();
    const input = container.querySelector('input[type="file"]') as HTMLInputElement;
    const file = new File([new Uint8Array([1])], 'anh-lon.png', { type: 'image/png' });
    Object.defineProperty(file, 'size', { value: 5 * 1024 * 1024 + 1 });
    fireEvent.change(input, { target: { files: [file] } });
    fireEvent.click(screen.getByRole('button', { name: 'Tải ảnh lên' }));
    expect(await screen.findByText(/vượt quá 5 MiB/)).toBeTruthy();
    expect(clientMock.openAttachmentSession).not.toHaveBeenCalled();
  });
});
