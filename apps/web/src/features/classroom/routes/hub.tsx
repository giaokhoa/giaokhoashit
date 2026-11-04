/**
 * Trang chính của feature classroom (issue #102): điểm vào của vòng dạy–học trên trang của một trung tâm.
 *
 * Flow: cổng phiên (`SessionGate`) bảo đảm người dùng đã đăng nhập và danh tính đã đọc từ `apps/server`; màn
 * hình hiển thị danh tính, vai tại trang và tư cách (thành viên hay khách của trung tâm), rồi ba khối dữ kiện
 * theo vai — lớp của trung tâm (quản trị tạo khóa/lớp tại đây), các lần giao nhìn thấy được (học viên vào làm
 * bài, giáo viên vào chấm), và lối vào ngân hàng câu hỏi/đề cho giáo viên. Mọi quyết định quyền vẫn ở server:
 * màn hình chỉ dẫn luồng theo vai, đáp ứng từ chối hiển thị theo hợp đồng lỗi.
 *
 * Bất biến hiển thị: học viên không thấy khối quản trị và khối ngân hàng; điểm số không xuất hiện ở trang này.
 */
import { useState, type FormEvent, type ReactNode } from 'react';
import { Link } from 'react-router';
import { Badge } from '@lms/ui/components/badge';
import { Button, buttonVariants } from '@lms/ui/components/button';
import { Card, CardContent, CardHeader, CardTitle } from '@lms/ui/components/card';
import { Input } from '@lms/ui/components/input';
import { Label } from '@lms/ui/components/label';
import { NativeSelect } from '@lms/ui/components/native-select';
import { PageShell } from '#components/page-shell';
import { EmptyNote, ErrorNote, LoadingNote, SessionGate } from '../components/states';
import { roleLabelsOf, useClassroomSession } from '../hooks/use-session';
import {
  useAssignments,
  useClasses,
  useCourses,
  useCreateClass,
  useCreateCourse,
  useCreateExam,
} from '../queries/hooks';
import { formatDateTime } from '../utils/datetime';

/**
 * Màn hình trang chính: bọc cổng phiên quanh nội dung theo vai.
 *
 * @returns Phần tử của trang chính.
 */
export default function ClassroomHub() {
  const session = useClassroomSession();
  return (
    <SessionGate session={session}>
      {(ready) => (
        <PageShell width="wide">
          <div className="flex flex-col gap-6">
            <header className="flex flex-col gap-1">
              <h1 className="text-2xl font-semibold">Giáo khoa</h1>
              <p className="text-sm text-muted-foreground">
                {ready.me.name ?? ready.me.preferredUsername ?? ready.me.sub}
                {ready.alias ? (
                  <>
                    {' · '}
                    {ready.me.organizations.includes(ready.alias)
                      ? `Thành viên của ${ready.alias}`
                      : `Khách của ${ready.alias}`}
                  </>
                ) : (
                  ' · Không gian cá nhân'
                )}
              </p>
              <div className="flex flex-wrap gap-2">
                {roleLabelsOf(ready.roles).map((label) => (
                  <Badge key={label} variant="secondary">
                    {label}
                  </Badge>
                ))}
              </div>
            </header>

            <ClassesSection isCenterAdmin={ready.isCenterAdmin} />
            <AssignmentsSection isTeacher={ready.isTeacher} isStudent={ready.isStudent} />
            {ready.isTeacher ? <TeacherToolsSection /> : null}
          </div>
        </PageShell>
      )}
    </SessionGate>
  );
}

/**
 * Khối lớp học: danh sách lớp của trung tâm kèm khóa và giáo viên phụ trách; quản trị trung tâm có thêm form
 * tạo khóa và tạo lớp ngay trong khối.
 *
 * @param props - Cờ vai quản trị để quyết định hiển thị form tạo.
 * @returns Phần tử khối lớp học.
 */
function ClassesSection({ isCenterAdmin }: { isCenterAdmin: boolean }) {
  const coursesQuery = useCourses();
  const classesQuery = useClasses();
  const courseNameById = new Map((coursesQuery.data ?? []).map((course) => [course.id, course.name]));
  return (
    <Card>
      <CardHeader>
        <CardTitle>Lớp học</CardTitle>
      </CardHeader>
      <CardContent className="flex flex-col gap-4">
        {classesQuery.isPending ? <LoadingNote label="Đang tải danh sách lớp…" /> : null}
        {classesQuery.isError ? (
          <ErrorNote error={classesQuery.error} onRetry={() => void classesQuery.refetch()} />
        ) : null}
        {classesQuery.data && classesQuery.data.length === 0 ? (
          <EmptyNote>Chưa có lớp nào trong phạm vi của bạn.</EmptyNote>
        ) : null}
        <ul className="flex flex-col gap-2">
          {(classesQuery.data ?? []).map((teachingClass) => (
            <li key={teachingClass.id} className="flex flex-wrap items-center justify-between gap-2">
              <div className="flex flex-col">
                <Link className="font-medium underline-offset-4 hover:underline" to={`/classroom/classes/${teachingClass.id}`}>
                  {teachingClass.name}
                </Link>
                <span className="text-sm text-muted-foreground">
                  {teachingClass.courseId ? (courseNameById.get(teachingClass.courseId) ?? 'Khóa') : 'Không thuộc khóa'}
                  {' · Giáo viên: '}
                  {teachingClass.teacherSub ?? 'chưa phân công'}
                </span>
              </div>
              <Link
                className={buttonVariants({ variant: 'outline', size: 'sm' })}
                to={`/classroom/classes/${teachingClass.id}`}
              >
                Mở lớp
              </Link>
            </li>
          ))}
        </ul>
        {isCenterAdmin ? (
          <div className="grid gap-4 md:grid-cols-2">
            <CreateCourseForm />
            <CreateClassForm courses={coursesQuery.data ?? []} />
          </div>
        ) : null}
      </CardContent>
    </Card>
  );
}

/**
 * Form tạo khóa của quản trị trung tâm.
 *
 * Side-effect: gửi `POST /api/courses` qua mutation; thành công thì danh sách khóa tự tải lại.
 *
 * @returns Phần tử form tạo khóa.
 */
function CreateCourseForm() {
  const [name, setName] = useState('');
  const createCourse = useCreateCourse();
  const onSubmit = (event: FormEvent) => {
    event.preventDefault();
    createCourse.mutate(name.trim(), { onSuccess: () => setName('') });
  };
  return (
    <form className="flex flex-col gap-2" onSubmit={onSubmit}>
      <Label htmlFor="hub-course-name">Tên khóa mới</Label>
      <div className="flex gap-2">
        <Input
          id="hub-course-name"
          value={name}
          onChange={(event) => setName(event.target.value)}
          placeholder="Ví dụ: Toán 6"
          required
        />
        <Button type="submit" disabled={createCourse.isPending || name.trim() === ''}>
          Tạo khóa
        </Button>
      </div>
      {createCourse.isError ? <ErrorNote error={createCourse.error} /> : null}
    </form>
  );
}

/**
 * Form tạo lớp của quản trị trung tâm, chọn khóa chứa lớp khi cần.
 *
 * Side-effect: gửi `POST /api/classes` qua mutation; thành công thì danh sách lớp tự tải lại.
 *
 * @param props - Danh sách khóa để chọn.
 * @returns Phần tử form tạo lớp.
 */
function CreateClassForm({ courses }: { courses: { id: string; name: string }[] }) {
  const [name, setName] = useState('');
  const [courseId, setCourseId] = useState('');
  const createClass = useCreateClass();
  const onSubmit = (event: FormEvent) => {
    event.preventDefault();
    createClass.mutate(
      { name: name.trim(), courseId: courseId === '' ? null : courseId },
      { onSuccess: () => setName('') },
    );
  };
  return (
    <form className="flex flex-col gap-2" onSubmit={onSubmit}>
      <Label htmlFor="hub-class-name">Tên lớp mới</Label>
      <div className="flex gap-2">
        <Input
          id="hub-class-name"
          value={name}
          onChange={(event) => setName(event.target.value)}
          placeholder="Ví dụ: 6A1"
          required
        />
        <NativeSelect
          aria-label="Khóa chứa lớp"
          value={courseId}
          onChange={(event) => setCourseId(event.target.value)}
        >
          <option value="">Không thuộc khóa</option>
          {courses.map((course) => (
            <option key={course.id} value={course.id}>
              {course.name}
            </option>
          ))}
        </NativeSelect>
        <Button type="submit" disabled={createClass.isPending || name.trim() === ''}>
          Tạo lớp
        </Button>
      </div>
      {createClass.isError ? <ErrorNote error={createClass.error} /> : null}
    </form>
  );
}

/**
 * Khối lần giao: mỗi dòng dẫn học viên vào màn làm bài và giáo viên vào màn bài nộp/chấm của đúng lần giao;
 * tên lớp tra từ danh sách lớp đã tải.
 *
 * @param props - Cờ vai giáo viên/học viên để hiển thị hành động tương ứng.
 * @returns Phần tử khối lần giao.
 */
function AssignmentsSection({ isTeacher, isStudent }: { isTeacher: boolean; isStudent: boolean }) {
  const assignmentsQuery = useAssignments();
  const classesQuery = useClasses();
  const classNameById = new Map((classesQuery.data ?? []).map((item) => [item.id, item.name]));
  return (
    <Card>
      <CardHeader>
        <CardTitle>Bài được giao</CardTitle>
      </CardHeader>
      <CardContent className="flex flex-col gap-4">
        {assignmentsQuery.isPending ? <LoadingNote label="Đang tải danh sách bài được giao…" /> : null}
        {assignmentsQuery.isError ? (
          <ErrorNote error={assignmentsQuery.error} onRetry={() => void assignmentsQuery.refetch()} />
        ) : null}
        {assignmentsQuery.data && assignmentsQuery.data.length === 0 ? (
          <EmptyNote>Chưa có bài nào được giao trong phạm vi của bạn.</EmptyNote>
        ) : null}
        <ul className="flex flex-col gap-2">
          {(assignmentsQuery.data ?? []).map((assignment) => (
            <li key={assignment.id} className="flex flex-wrap items-center justify-between gap-2">
              <div className="flex flex-col">
                <span className="font-medium">{classNameById.get(assignment.classId) ?? 'Lớp'}</span>
                <span className="text-sm text-muted-foreground">
                  Mở: {formatDateTime(assignment.opensAt)} · Hạn: {formatDateTime(assignment.dueAt)}
                </span>
              </div>
              <div className="flex gap-2">
                {isStudent ? (
                  <Link
                    className={buttonVariants({ size: 'sm' })}
                    to={`/classroom/assignments/${assignment.id}/attempt`}
                  >
                    Làm bài
                  </Link>
                ) : null}
                {isTeacher ? (
                  <Link
                    className={buttonVariants({ variant: 'outline', size: 'sm' })}
                    to={`/classroom/assignments/${assignment.id}/grade`}
                  >
                    Bài nộp & chấm
                  </Link>
                ) : null}
              </div>
            </li>
          ))}
        </ul>
      </CardContent>
    </Card>
  );
}

/**
 * Khối công cụ của giáo viên: lối vào ngân hàng câu hỏi cá nhân, tạo đề mới và mở câu/đề theo định danh (server
 * chưa có endpoint danh sách cho câu và đề nên phiên làm việc đi qua các bản ghi vừa tạo và định danh).
 *
 * Side-effect: tạo đề gửi `POST /api/exams` rồi điều hướng sang màn soạn đề của đề vừa tạo.
 *
 * @returns Phần tử khối công cụ giáo viên.
 */
function TeacherToolsSection() {
  const createExam = useCreateExam();
  const [examTitle, setExamTitle] = useState('');
  const [questionId, setQuestionId] = useState('');
  const [examId, setExamId] = useState('');
  const onCreateExam = (event: FormEvent) => {
    event.preventDefault();
    createExam.mutate(examTitle.trim(), {
      onSuccess: (exam) => {
        window.location.assign(`/classroom/exams/${exam.id}`);
      },
    });
  };
  const openById = (path: string, id: string) => {
    if (id.trim() !== '') window.location.assign(`${path}/${id.trim()}`);
  };
  return (
    <Card>
      <CardHeader>
        <CardTitle>Ngân hàng và đề của tôi</CardTitle>
      </CardHeader>
      <CardContent className="flex flex-col gap-4">
        <div>
          <Link className={buttonVariants()} to="/classroom/bank">
            Mở ngân hàng câu hỏi cá nhân
          </Link>
        </div>
        <form className="flex flex-col gap-2" onSubmit={onCreateExam}>
          <Label htmlFor="hub-exam-title">Tiêu đề đề mới</Label>
          <div className="flex gap-2">
            <Input
              id="hub-exam-title"
              value={examTitle}
              onChange={(event) => setExamTitle(event.target.value)}
              placeholder="Ví dụ: Kiểm tra 15 phút — Phân số"
              required
            />
            <Button type="submit" disabled={createExam.isPending || examTitle.trim() === ''}>
              Tạo đề
            </Button>
          </div>
          {createExam.isError ? <ErrorNote error={createExam.error} /> : null}
        </form>
        <div className="grid gap-4 md:grid-cols-2">
          <OpenByIdForm
            label="Mở câu hỏi theo mã"
            inputId="hub-open-question"
            value={questionId}
            onChange={setQuestionId}
            onOpen={() => openById('/classroom/questions', questionId)}
          />
          <OpenByIdForm
            label="Mở đề theo mã"
            inputId="hub-open-exam"
            value={examId}
            onChange={setExamId}
            onOpen={() => openById('/classroom/exams', examId)}
          />
        </div>
      </CardContent>
    </Card>
  );
}

/**
 * Form nhỏ mở một bản ghi theo định danh do người dùng nhập.
 *
 * @param props - Nhãn, giá trị hiện tại và các hàm xử lý nhập/mở.
 * @returns Phần tử form mở theo mã.
 */
function OpenByIdForm({
  label,
  inputId,
  value,
  onChange,
  onOpen,
}: {
  label: string;
  inputId: string;
  value: string;
  onChange: (value: string) => void;
  onOpen: () => void;
}): ReactNode {
  return (
    <form
      className="flex flex-col gap-2"
      onSubmit={(event) => {
        event.preventDefault();
        onOpen();
      }}
    >
      <Label htmlFor={inputId}>{label}</Label>
      <div className="flex gap-2">
        <Input id={inputId} value={value} onChange={(event) => onChange(event.target.value)} />
        <Button type="submit" variant="outline" disabled={value.trim() === ''}>
          Mở
        </Button>
      </div>
    </form>
  );
}
