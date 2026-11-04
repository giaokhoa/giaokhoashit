/**
 * Spec luồng tổ chức dạy học phía quản trị trung tâm (issue #102): trang chính và chi tiết lớp.
 *
 * Hành vi được kiểm chứng: quản trị thấy danh sách khóa/lớp, tạo được khóa; ở chi tiết lớp, đặt được giáo viên
 * phụ trách, ghi danh được học viên (form ghi rõ học viên ngoài trung tâm tham gia với tư cách khách) và gỡ
 * được học viên — mọi thao tác đi đúng hàm của tầng api với đúng tham số. Biên mock là tầng `api/client` và
 * hook phiên; hook React Query và component chạy thật.
 */
import { cleanup, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { TestProviders } from '#test/providers';
import ClassroomClassDetail from './class-detail';
import ClassroomHub from './hub';

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
      sub: 'admin-1',
      preferredUsername: 'admin-trung-tam-1',
      name: 'Quản Trị Một',
      email: null,
      realmRoles: [],
      organizations: ['trung-tam-1'],
    },
    alias: 'trung-tam-1',
    roles: ['tenant-admin'],
    isCenterAdmin: true,
    isTeacher: false,
    isStudent: false,
  }),
  requestLogin: vi.fn(),
  roleLabelsOf: (roles: string[]) => roles,
  pageRolesOf: () => [],
}));

afterEach(() => {
  cleanup();
});

describe('Luồng quản trị trung tâm: lớp, phân công và ghi danh', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    clientMock.fetchCourses.mockResolvedValue([{ id: 'course-1', name: 'Toán 6' }]);
    clientMock.fetchClasses.mockResolvedValue([
      { id: 'class-1', name: '6A1', courseId: 'course-1', teacherSub: null },
    ]);
    clientMock.fetchAssignments.mockResolvedValue([]);
    clientMock.fetchMembers.mockResolvedValue([
      { studentSub: 'hs-1', enrolledBySub: 'admin-1', enrolledAt: '2026-11-04T00:00:00Z' },
    ]);
    clientMock.createCourse.mockResolvedValue({ id: 'course-2', name: 'Văn 6' });
    clientMock.setClassTeacher.mockResolvedValue({
      id: 'class-1',
      name: '6A1',
      courseId: 'course-1',
      teacherSub: 'gv-1',
    });
    clientMock.addMember.mockResolvedValue({
      studentSub: 'khach-1',
      enrolledBySub: 'admin-1',
      enrolledAt: '2026-11-04T01:00:00Z',
    });
    clientMock.removeMember.mockResolvedValue(undefined);
  });

  it('trang chính liệt kê lớp kèm khóa và cho quản trị tạo khóa mới', async () => {
    const user = userEvent.setup();
    render(
      <TestProviders>
        <MemoryRouter initialEntries={['/classroom']}>
          <Routes>
            <Route path="/classroom" element={<ClassroomHub />} />
          </Routes>
        </MemoryRouter>
      </TestProviders>,
    );
    expect(await screen.findByText('6A1')).toBeInTheDocument();
    expect(screen.getByText('Toán 6 · Giáo viên: chưa phân công')).toBeInTheDocument();
    await user.type(screen.getByLabelText('Tên khóa mới'), 'Văn 6');
    await user.click(screen.getByRole('button', { name: 'Tạo khóa' }));
    expect(clientMock.createCourse).toHaveBeenCalledWith('Văn 6', expect.any(Object));
  });

  it('chi tiết lớp: phân công giáo viên, ghi danh học viên khách và gỡ học viên', async () => {
    const user = userEvent.setup();
    render(
      <TestProviders>
        <MemoryRouter initialEntries={['/classroom/classes/class-1']}>
          <Routes>
            <Route path="/classroom/classes/:classId" element={<ClassroomClassDetail />} />
          </Routes>
        </MemoryRouter>
      </TestProviders>,
    );
    expect(await screen.findByText('hs-1')).toBeInTheDocument();
    expect(screen.getByText(/tham gia lớp này với tư cách khách/)).toBeInTheDocument();

    await user.type(screen.getByLabelText('Mã định danh (sub) của giáo viên phụ trách'), 'gv-1');
    await user.click(screen.getByRole('button', { name: 'Phân công' }));
    expect(clientMock.setClassTeacher).toHaveBeenCalledWith('class-1', 'gv-1');

    await user.type(screen.getByLabelText('Mã định danh (sub) của học viên'), 'khach-1');
    await user.click(screen.getByRole('button', { name: 'Ghi danh' }));
    expect(clientMock.addMember).toHaveBeenCalledWith('class-1', 'khach-1');

    await user.click(screen.getByRole('button', { name: 'Gỡ khỏi lớp' }));
    expect(clientMock.removeMember).toHaveBeenCalledWith('class-1', 'hs-1');
  });
});
