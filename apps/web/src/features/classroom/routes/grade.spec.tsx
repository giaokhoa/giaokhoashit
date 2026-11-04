/**
 * Spec luồng chấm và công bố của giáo viên theo lần giao (issue #102).
 *
 * Hành vi được kiểm chứng theo hợp đồng của lát chấm/công bố: bảng bài nộp hiển thị điểm đã công bố và điểm
 * tạm đúng theo các trường điểm server trả, và hiển thị dấu gạch ngang khi server chưa trả trường điểm; khung
 * chấm mở theo mã lượt hiển thị đáp án đúng và tiêu chí chấm cho giáo viên phụ trách, chấm mục tự luận theo
 * mục kèm nhận xét được gửi đúng hình lên tầng api; công bố theo lần giao gọi đúng endpoint. Biên mock là tầng
 * `api/client` và hook phiên; hook React Query và component chạy thật.
 */
import { cleanup, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { TestProviders } from '#test/providers';
import type { TeacherAttemptView } from '../types/server';
import ClassroomGrade, { scoreLabelOf } from './grade';

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

const teacherAttempt: TeacherAttemptView = {
  attemptId: 'att-1',
  assignmentId: 'asg-1',
  studentSub: 'hs-1',
  status: 'SUBMITTED',
  items: [
    {
      examItemId: 'it-1',
      questionType: 'single_choice',
      order: 1,
      stem: '2 + 2 bằng mấy?',
      choices: [
        { id: 'c1', text: 'Bằng 3' },
        { id: 'c2', text: 'Bằng 4' },
      ],
      correctChoiceId: 'c2',
      rubric: null,
      maxScore: 5,
      answer: { choiceId: 'c2' },
      grade: { score: 5, maxScore: 5, source: 'auto', comment: null },
    },
    {
      examItemId: 'it-2',
      questionType: 'essay_text',
      order: 2,
      stem: 'Viết một đoạn văn ngắn về trường em.',
      choices: [],
      correctChoiceId: null,
      rubric: 'Đúng ý chính đạt tối đa.',
      maxScore: 5,
      answer: { text: 'Trường em rất đẹp.' },
      grade: { score: null, maxScore: 5, source: null, comment: null },
    },
  ],
  totalScore: 5,
  maxScore: 10,
  provisional: true,
  overallComment: null,
};

afterEach(() => {
  cleanup();
});

describe('scoreLabelOf (nhãn điểm của một dòng bài nộp)', () => {
  it('chưa có trường điểm thì hiện dấu gạch ngang; điểm tạm và điểm đã công bố được ghi rõ', () => {
    const base = {
      studentSub: 'hs-1',
      status: 'SUBMITTED',
      startedAt: null,
      submittedAt: null,
      submitMode: null,
      removed: false,
    };
    expect(scoreLabelOf(base)).toBe('—');
    expect(scoreLabelOf({ ...base, totalScore: 7, maxScore: 10, provisional: true, published: false })).toBe(
      '7/10 (tạm)',
    );
    expect(scoreLabelOf({ ...base, totalScore: 9, maxScore: 10, provisional: false, published: true })).toBe(
      '9/10 (đã công bố)',
    );
  });
});

describe('Màn chấm và công bố theo lần giao', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    clientMock.fetchSubmissions.mockResolvedValue([
      {
        studentSub: 'hs-1',
        status: 'PUBLISHED',
        startedAt: '2026-11-05T01:00:00Z',
        submittedAt: '2026-11-05T02:00:00Z',
        submitMode: 'manual',
        removed: false,
        totalScore: 9,
        maxScore: 10,
        provisional: false,
        published: true,
      },
      {
        studentSub: 'hs-2',
        status: 'NOT_STARTED',
        startedAt: null,
        submittedAt: null,
        submitMode: null,
        removed: false,
      },
    ]);
    clientMock.publishResults.mockResolvedValue({
      assignmentId: 'asg-1',
      publishedBySub: 'gv-1',
      publishedAt: '2026-11-05T03:00:00Z',
    });
    clientMock.fetchTeacherAttempt.mockResolvedValue(teacherAttempt);
    clientMock.gradeAttempt.mockResolvedValue({
      attemptId: 'att-1',
      status: 'GRADED',
      totalScore: 9,
      maxScore: 10,
      provisional: false,
    });
  });

  it('bảng bài nộp hiện điểm đã công bố của học viên và gạch ngang cho học viên chưa có điểm', async () => {
    render(
      <TestProviders>
        <MemoryRouter initialEntries={['/classroom/assignments/asg-1/grade']}>
          <Routes>
            <Route path="/classroom/assignments/:assignmentId/grade" element={<ClassroomGrade />} />
          </Routes>
        </MemoryRouter>
      </TestProviders>,
    );
    expect(await screen.findByText('9/10 (đã công bố)')).toBeInTheDocument();
    expect(screen.getByText('hs-2')).toBeInTheDocument();
  });

  it('mở khung chấm theo mã lượt, chấm mục tự luận theo mục kèm nhận xét và công bố theo lần giao', async () => {
    const user = userEvent.setup();
    render(
      <TestProviders>
        <MemoryRouter initialEntries={['/classroom/assignments/asg-1/grade']}>
          <Routes>
            <Route path="/classroom/assignments/:assignmentId/grade" element={<ClassroomGrade />} />
          </Routes>
        </MemoryRouter>
      </TestProviders>,
    );
    expect(await screen.findByText('hs-1')).toBeInTheDocument();

    await user.type(screen.getByLabelText('Mã lượt làm'), 'att-1');
    await user.click(screen.getByRole('button', { name: 'Mở khung chấm' }));
    expect(await screen.findByText('Chấm lượt của hs-1')).toBeInTheDocument();
    expect(screen.getByText(/Đáp án đúng: Bằng 4/)).toBeInTheDocument();
    expect(screen.getByText(/Tiêu chí chấm:/)).toBeInTheDocument();

    await user.type(screen.getByLabelText('Điểm của mục'), '4');
    await user.type(screen.getByLabelText('Nhận xét theo mục'), 'Khá tốt');
    await user.click(screen.getByRole('button', { name: 'Lưu điểm chấm' }));
    expect(clientMock.gradeAttempt).toHaveBeenCalledWith('att-1', {
      items: [{ examItemId: 'it-2', score: 4, comment: 'Khá tốt' }],
      comment: null,
    });
    expect(await screen.findByText(/Đã chấm: 9\/10/)).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Công bố kết quả của lần giao này' }));
    expect(clientMock.publishResults).toHaveBeenCalledWith('asg-1', expect.any(Object));
    expect(await screen.findByText(/Đã công bố lúc/)).toBeInTheDocument();
  });
});
