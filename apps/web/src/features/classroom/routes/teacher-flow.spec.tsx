/**
 * Spec các luồng của giáo viên (issue #102): ngân hàng và soạn câu, sửa/phát hành câu, soạn/phát hành đề và
 * giao bài.
 *
 * Hành vi được kiểm chứng theo ca nghiệm thu phía giáo viên: tạo câu trắc nghiệm với đúng một đáp án đúng vào
 * ngân hàng cá nhân; phát hành một phiên bản câu; gắn một phiên bản câu kèm điểm thành mục của đề rồi lưu và
 * phát hành đề; giao phiên bản đã phát hành cho một lớp với cửa sổ thời gian được chuyển sang ISO có múi giờ.
 * Biên mock là tầng `api/client` và hook phiên; hook React Query và component chạy thật.
 */
import { cleanup, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { TestProviders } from '#test/providers';
import type { ExamView, QuestionView } from '../types/server';
import ClassroomBank from './bank';
import ClassroomExamEdit from './exam-edit';
import ClassroomQuestionEdit from './question-edit';

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
      sub: 'gv-1',
      preferredUsername: 'gv-trung-tam-1',
      name: 'Giáo Viên Một',
      email: null,
      realmRoles: [],
      organizations: ['trung-tam-1'],
    },
    alias: 'trung-tam-1',
    roles: ['tenant-teacher'],
    isCenterAdmin: false,
    isTeacher: true,
    isStudent: false,
  }),
  requestLogin: vi.fn(),
  roleLabelsOf: (roles: string[]) => roles,
  pageRolesOf: () => [],
}));

const draftQuestion: QuestionView = {
  id: 'q-1',
  bankId: 'bank-1',
  versions: [
    {
      versionId: 'qv-1',
      versionNumber: 1,
      status: 'draft',
      lockedAt: null,
      contentHash: null,
      revision: 0,
      questionType: 'single_choice',
      stem: '2 + 2 bằng mấy?',
      choices: [
        { id: 'a', text: 'Bằng 3' },
        { id: 'b', text: 'Bằng 4' },
      ],
      correctChoiceId: 'b',
      solution: '2 + 2 = 4.',
      rubric: null,
    },
  ],
  createdAt: '2026-11-04T00:00:00Z',
  updatedAt: '2026-11-04T00:00:00Z',
};

const draftExam: ExamView = {
  id: 'exam-1',
  title: 'Kiểm tra 15 phút',
  versionId: 'ev-1',
  versionNumber: 1,
  status: 'draft',
  lockedAt: null,
  contentHash: null,
  lockVersion: 3,
  items: [],
};

/**
 * Render một màn hình của feature tại một đường dẫn có tham số.
 *
 * @param path - Đường dẫn khởi tạo.
 * @param routePath - Mẫu đường của route.
 * @param element - Phần tử màn hình.
 * @returns Kết quả render của Testing Library.
 */
function renderAt(path: string, routePath: string, element: React.ReactNode) {
  return render(
    <TestProviders>
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route path={routePath} element={element} />
        </Routes>
      </MemoryRouter>
    </TestProviders>,
  );
}

afterEach(() => {
  cleanup();
});

describe('Luồng giáo viên: ngân hàng, câu, đề và giao bài', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    clientMock.fetchBanks.mockResolvedValue([
      {
        id: 'bank-1',
        name: 'Ngân hàng Toán',
        workspaceKind: 'personal',
        ownerType: 'user',
        ownerUserSub: 'gv-1',
        custodyKind: 'owned',
        createdAt: '2026-11-04T00:00:00Z',
        updatedAt: '2026-11-04T00:00:00Z',
      },
    ]);
    clientMock.fetchClasses.mockResolvedValue([
      { id: 'class-1', name: '6A1', courseId: 'course-1', teacherSub: 'gv-1' },
    ]);
    clientMock.fetchAssignments.mockResolvedValue([]);
  });

  it('tạo câu trắc nghiệm với đúng một đáp án đúng vào ngân hàng cá nhân và liệt kê câu vừa soạn', async () => {
    clientMock.createQuestion.mockResolvedValue(draftQuestion);
    const user = userEvent.setup();
    renderAt('/classroom/bank', '/classroom/bank', <ClassroomBank />);

    expect(await screen.findByText('Mã ngân hàng: bank-1')).toBeInTheDocument();
    await user.type(screen.getByLabelText('Thân câu hỏi'), '2 + 2 bằng mấy?');
    await user.type(screen.getByLabelText('Lựa chọn 1'), 'Bằng 3');
    await user.type(screen.getByLabelText('Lựa chọn 2'), 'Bằng 4');
    await user.click(screen.getByLabelText('Đáp án đúng là lựa chọn 2'));
    await user.click(screen.getByRole('button', { name: 'Tạo câu' }));

    expect(clientMock.createQuestion).toHaveBeenCalledWith('bank-1', {
      questionType: 'single_choice',
      stem: '2 + 2 bằng mấy?',
      choices: [
        { id: 'a', text: 'Bằng 3' },
        { id: 'b', text: 'Bằng 4' },
      ],
      correctChoiceId: 'b',
      solution: null,
      rubric: null,
    });
    expect(await screen.findByText('Câu vừa soạn trong phiên này')).toBeInTheDocument();
  });

  it('sửa bản nháp kèm revision hiện tại và phát hành được phiên bản câu', async () => {
    clientMock.fetchQuestion.mockResolvedValue(draftQuestion);
    clientMock.updateQuestionVersion.mockResolvedValue(draftQuestion.versions[0]);
    clientMock.publishQuestionVersion.mockResolvedValue({
      ...draftQuestion.versions[0],
      status: 'published',
      lockedAt: '2026-11-04T02:00:00Z',
      contentHash: 'hash-1',
    });
    const user = userEvent.setup();
    renderAt('/classroom/questions/q-1', '/classroom/questions/:questionId', <ClassroomQuestionEdit />);

    expect(await screen.findByText('Phiên bản 1 — Trắc nghiệm')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Lưu bản nháp' }));
    expect(clientMock.updateQuestionVersion).toHaveBeenCalledWith(
      'q-1',
      'qv-1',
      expect.objectContaining({ revision: 0, questionType: 'single_choice', correctChoiceId: 'b' }),
    );
    await user.click(screen.getByRole('button', { name: 'Phát hành phiên bản này' }));
    expect(clientMock.publishQuestionVersion).toHaveBeenCalledWith('q-1', 'qv-1');
  });

  it('gắn một phiên bản câu kèm điểm thành mục của đề, lưu kèm lockVersion và phát hành đề', async () => {
    clientMock.fetchExam.mockResolvedValue(draftExam);
    clientMock.fetchQuestion.mockResolvedValue({
      ...draftQuestion,
      versions: [{ ...draftQuestion.versions[0], status: 'published', lockedAt: '2026-11-04T02:00:00Z' }],
    });
    clientMock.updateExamVersion.mockResolvedValue({
      ...draftExam,
      items: [
        { id: 'item-1', questionVersionId: 'qv-1', questionType: 'single_choice', position: 1, score: 2, content: {} },
      ],
    });
    clientMock.publishExamVersion.mockResolvedValue({
      ...draftExam,
      status: 'published',
      lockedAt: '2026-11-04T03:00:00Z',
      contentHash: 'exam-hash',
    });
    const user = userEvent.setup();
    renderAt('/classroom/exams/exam-1', '/classroom/exams/:examId', <ClassroomExamEdit />);

    expect(await screen.findByText('Soạn nội dung đề')).toBeInTheDocument();
    await user.type(screen.getByLabelText('Mã câu hỏi'), 'q-1');
    await user.click(screen.getByRole('button', { name: 'Tra câu' }));
    expect(await screen.findByText(/Bản 1 — published/)).toBeInTheDocument();
    await user.clear(screen.getByLabelText('Điểm của mục'));
    await user.type(screen.getByLabelText('Điểm của mục'), '2');
    await user.click(screen.getByRole('button', { name: 'Thêm mục' }));
    expect(await screen.findByText('Phiên bản câu: qv-1')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Lưu nội dung đề' }));
    expect(clientMock.updateExamVersion).toHaveBeenCalledWith('exam-1', 'ev-1', {
      title: 'Kiểm tra 15 phút',
      lockVersion: 3,
      items: [{ questionVersionId: 'qv-1', score: 2 }],
    });
    await user.click(screen.getByRole('button', { name: 'Phát hành đề (đóng băng các câu)' }));
    expect(clientMock.publishExamVersion).toHaveBeenCalledWith('exam-1', 'ev-1');
  });

  it('giao phiên bản đề đã phát hành cho lớp với cửa sổ thời gian có múi giờ', async () => {
    const publishedExam: ExamView = {
      ...draftExam,
      status: 'published',
      lockedAt: '2026-11-04T03:00:00Z',
      contentHash: 'exam-hash',
    };
    clientMock.fetchExam.mockResolvedValue(publishedExam);
    clientMock.createAssignment.mockResolvedValue({
      id: 'asg-1',
      classId: 'class-1',
      examResourceId: 'exam-1',
      examVersionId: 'ev-1',
      assignedBySub: 'gv-1',
      opensAt: '2026-11-05T01:00:00.000Z',
      dueAt: '2026-11-06T01:00:00.000Z',
    });
    const user = userEvent.setup();
    renderAt('/classroom/exams/exam-1', '/classroom/exams/:examId', <ClassroomExamEdit />);

    expect(await screen.findByText('Giao phiên bản này cho lớp')).toBeInTheDocument();
    await user.type(screen.getByLabelText('Mở bài lúc'), '2026-11-05T08:00');
    await user.type(screen.getByLabelText('Hạn nộp lúc'), '2026-11-06T08:00');
    await user.click(screen.getByRole('button', { name: 'Giao bài' }));
    expect(clientMock.createAssignment).toHaveBeenCalledWith(
      {
        examVersionId: 'ev-1',
        classId: 'class-1',
        opensAt: new Date('2026-11-05T08:00').toISOString(),
        dueAt: new Date('2026-11-06T08:00').toISOString(),
      },
      expect.any(Object),
    );
  });
});
