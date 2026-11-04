/**
 * Spec khối tệp bài làm (issue #121, ca NT-34d phía web).
 *
 * Hành vi được kiểm chứng: trạng thái hiển thị của từng tệp khớp đúng dữ kiện server đã chốt — tệp `ready`
 * mới được trình bày là đã chốt và tính là đã nộp; tệp đang kiểm tra/xử lý và tệp bị từ chối phải ghi rõ
 * chưa/không tính là đã nộp, không được báo nộp thành công chỉ vì byte đã truyền xong.
 *
 * Biên mock là tầng `api/client` (cùng biên với các spec khác của feature); các hook React Query và
 * component chạy thật.
 */
import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { TestProviders } from '#test/providers';
import type { SubmissionFileView } from '../types/server';
import { SubmissionFilesPanel } from './submission-files';

const clientMock = vi.hoisted(() => ({
  fetchMe: vi.fn(),
  fetchCourses: vi.fn(),
  fetchClasses: vi.fn(),
  createCourse: vi.fn(),
  createClass: vi.fn(),
  setClassTeacher: vi.fn(),
  fetchMembers: vi.fn(),
  addMember: vi.fn(),
  removeMember: vi.fn(),
  fetchBanks: vi.fn(),
  createBank: vi.fn(),
  addBankEntry: vi.fn(),
  removeBankEntry: vi.fn(),
  createQuestion: vi.fn(),
  fetchQuestion: vi.fn(),
  updateQuestionVersion: vi.fn(),
  createQuestionVersion: vi.fn(),
  publishQuestionVersion: vi.fn(),
  createExam: vi.fn(),
  fetchExam: vi.fn(),
  updateExamVersion: vi.fn(),
  createExamVersion: vi.fn(),
  publishExamVersion: vi.fn(),
  fetchAssignments: vi.fn(),
  createAssignment: vi.fn(),
  startAttempt: vi.fn(),
  saveAnswers: vi.fn(),
  submitAttempt: vi.fn(),
  fetchAttemptResult: vi.fn(),
  fetchTeacherAttempt: vi.fn(),
  fetchSubmissions: vi.fn(),
  fetchSubmissionFiles: vi.fn(),
  attachSubmissionFile: vi.fn(),
  removeSubmissionFile: vi.fn(),
  gradeAttempt: vi.fn(),
  publishResults: vi.fn(),
}));

vi.mock('../api/client', () => clientMock);

function fileView(overrides: Partial<SubmissionFileView>): SubmissionFileView {
  return {
    id: 'file-1',
    attemptId: 'attempt-1',
    blobId: null,
    displayName: 'bai-lam.png',
    contentType: 'image/png',
    sizeBytes: 1024,
    status: 'processing',
    submitted: false,
    attachedAt: null,
    ...overrides,
  };
}

describe('Khối tệp bài làm', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  afterEach(() => {
    cleanup();
  });

  it('NT-34d: trạng thái hiển thị khớp dữ kiện server đã chốt — chỉ tệp ready mới báo đã nộp, tệp đang xử lý và bị từ chối ghi rõ không tính là đã nộp', async () => {
    clientMock.fetchSubmissionFiles.mockResolvedValue([
      fileView({
        id: 'file-ready',
        displayName: 'anh-da-chot.png',
        status: 'ready',
        submitted: true,
        blobId: 'blob-1',
        attachedAt: '2026-11-05T00:00:00Z',
      }),
      fileView({ id: 'file-processing', displayName: 'anh-dang-xu-ly.png', status: 'processing', submitted: false }),
      fileView({ id: 'file-rejected', displayName: 'anh-bi-tu-choi.png', status: 'rejected', submitted: false }),
    ]);
    render(
      <TestProviders>
        <SubmissionFilesPanel attemptId="attempt-1" locked={false} />
      </TestProviders>,
    );

    expect(await screen.findByText('Đã chốt — tính là tệp đã nộp')).toBeTruthy();
    expect(screen.getByText('Đang kiểm tra/xử lý — chưa tính là đã nộp')).toBeTruthy();
    expect(screen.getByText('Bị từ chối — không tính là đã nộp')).toBeTruthy();
    // Đúng một tệp được báo là đã nộp: hai tệp còn lại không mang dòng trạng thái đã chốt.
    expect(screen.getAllByText('Đã chốt — tính là tệp đã nộp')).toHaveLength(1);
    expect(screen.getByText('anh-da-chot.png')).toBeTruthy();
    expect(screen.getByText('anh-dang-xu-ly.png')).toBeTruthy();
  });

  it('Khi lượt đã khoá thì khối chỉ đọc: không còn ô gắn phiên và nút gỡ tệp', async () => {
    clientMock.fetchSubmissionFiles.mockResolvedValue([
      fileView({ id: 'file-ready', status: 'ready', submitted: true, blobId: 'blob-1' }),
    ]);
    render(
      <TestProviders>
        <SubmissionFilesPanel attemptId="attempt-1" locked />
      </TestProviders>,
    );

    expect(await screen.findByText('Đã chốt — tính là tệp đã nộp')).toBeTruthy();
    expect(screen.queryByLabelText('Mã phiên tải lên')).toBeNull();
    expect(screen.queryByRole('button', { name: 'Gỡ tệp' })).toBeNull();
  });
});
