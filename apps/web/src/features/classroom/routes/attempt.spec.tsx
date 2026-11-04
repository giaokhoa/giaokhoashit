/**
 * Spec luồng làm bài của học viên (issue #102).
 *
 * Hành vi được kiểm chứng theo đúng ca nghiệm thu phía học viên: mở bài thì bắt đầu đúng một lượt; trả lời
 * trắc nghiệm và tự luận rồi lưu thì tầng api nhận đúng hình câu trả lời của từng loại; nộp xong, khi kết quả
 * chưa công bố thì màn hình không hiện bất kỳ điểm nào; sau công bố thì hiện điểm từng mục và tổng điểm của
 * chính học viên — và trong mọi trường hợp không có đáp án đúng hay lời giải nào trong DOM.
 *
 * Biên mock là tầng `api/client` (đã có spec riêng ở biên fetch) và hook phiên; các hook React Query và
 * component chạy thật.
 */
import { cleanup, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { TestProviders } from '#test/providers';
import type { AttemptView } from '../types/server';
import ClassroomAttempt from './attempt';

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
  gradeAttempt: vi.fn(),
  publishResults: vi.fn(),
}));

vi.mock('../api/client', () => clientMock);

vi.mock('../hooks/use-session', () => ({
  useClassroomSession: () => ({
    phase: 'ready',
    me: {
      sub: 'hs-1',
      preferredUsername: 'hs-trung-tam-1',
      name: 'Học Sinh Một',
      email: null,
      realmRoles: [],
      organizations: ['trung-tam-1'],
    },
    alias: 'trung-tam-1',
    roles: ['tenant-member'],
    isCenterAdmin: false,
    isTeacher: false,
    isStudent: true,
  }),
  requestLogin: vi.fn(),
  roleLabelsOf: (roles: string[]) => roles,
  pageRolesOf: () => [],
}));

const attemptInProgress: AttemptView = {
  attemptId: 'att-1',
  assignmentId: 'asg-1',
  status: 'IN_PROGRESS',
  startedAt: '2026-11-05T01:00:00Z',
  submittedAt: null,
  submitMode: null,
  opensAt: '2026-11-05T00:00:00Z',
  dueAt: '2026-11-06T00:00:00Z',
  items: [
    {
      examItemId: 'it-1',
      type: 'single_choice',
      position: 1,
      points: 5,
      prompt: '2 + 2 bằng mấy?',
      choices: [
        { choiceId: 'c1', text: 'Bằng 3' },
        { choiceId: 'c2', text: 'Bằng 4' },
      ],
    },
    {
      examItemId: 'it-2',
      type: 'essay_text',
      position: 2,
      points: 5,
      prompt: 'Viết một đoạn văn ngắn về trường em.',
      choices: [],
    },
  ],
  answers: [],
  result: null,
};

/**
 * Render màn làm bài tại đường của lần giao `asg-1`.
 *
 * @returns Kết quả render của Testing Library.
 */
function renderAttempt() {
  return render(
    <TestProviders>
      <MemoryRouter initialEntries={['/classroom/assignments/asg-1/attempt']}>
        <Routes>
          <Route path="/classroom/assignments/:assignmentId/attempt" element={<ClassroomAttempt />} />
        </Routes>
      </MemoryRouter>
    </TestProviders>,
  );
}

afterEach(() => {
  cleanup();
});

describe('Màn làm bài của học viên (luồng bắt đầu, lưu, nộp và xem kết quả)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    clientMock.saveAnswers.mockResolvedValue(attemptInProgress);
    clientMock.submitAttempt.mockResolvedValue({
      attemptId: 'att-1',
      status: 'SUBMITTED',
      submittedAt: '2026-11-05T02:00:00Z',
      submitMode: 'manual',
    });
  });

  it('bắt đầu một lượt, lưu câu trả lời đúng hình từng loại câu và nộp bài; chưa công bố thì không hiện điểm', async () => {
    clientMock.startAttempt.mockResolvedValue(attemptInProgress);
    clientMock.fetchAttemptResult.mockResolvedValue({ ...attemptInProgress, status: 'SUBMITTED' });
    const user = userEvent.setup();
    renderAttempt();

    expect(await screen.findByText('2 + 2 bằng mấy?')).toBeInTheDocument();
    expect(clientMock.startAttempt).toHaveBeenCalledWith('asg-1', expect.any(Object));

    await user.click(screen.getByLabelText('Bằng 4'));
    await user.type(screen.getByLabelText('Bài viết của bạn'), 'Trường em rất đẹp.');
    await user.click(screen.getByRole('button', { name: 'Lưu bài' }));
    expect(clientMock.saveAnswers).toHaveBeenCalledWith('att-1', [
      { examItemId: 'it-1', answer: { choiceId: 'c2' } },
      { examItemId: 'it-2', answer: { text: 'Trường em rất đẹp.' } },
    ]);

    await user.click(screen.getByRole('button', { name: 'Nộp bài' }));
    expect(clientMock.submitAttempt).toHaveBeenCalledWith('att-1', expect.any(Object));
    expect(await screen.findByText(/Kết quả sẽ hiển thị sau khi giáo viên công bố/)).toBeInTheDocument();
    expect(screen.queryByText(/Tổng điểm/)).not.toBeInTheDocument();
    expect(document.body.textContent).not.toContain('đáp án đúng');
  });

  it('sau khi giáo viên công bố, học viên thấy điểm từng mục và tổng điểm của chính mình, vẫn không thấy đáp án', async () => {
    clientMock.startAttempt.mockResolvedValue({ ...attemptInProgress, status: 'PUBLISHED' });
    clientMock.fetchAttemptResult.mockResolvedValue({
      ...attemptInProgress,
      status: 'PUBLISHED',
      result: {
        items: [
          { examItemId: 'it-1', score: 5, maxScore: 5, comment: 'Đúng' },
          { examItemId: 'it-2', score: 4, maxScore: 5, comment: null },
        ],
        overallComment: 'Làm tốt.',
        totalScore: 9,
        maxScore: 10,
      },
    });
    renderAttempt();

    expect(await screen.findByText('Tổng điểm: 9/10')).toBeInTheDocument();
    expect(screen.getByText('Câu 1: 5/5 điểm')).toBeInTheDocument();
    expect(screen.getByText('Câu 2: 4/5 điểm')).toBeInTheDocument();
    expect(screen.getByText(/Nhận xét chung:/)).toBeInTheDocument();
    expect(document.body.textContent).not.toContain('đáp án đúng');
    expect(screen.queryByRole('button', { name: 'Nộp bài' })).not.toBeInTheDocument();
  });
});
