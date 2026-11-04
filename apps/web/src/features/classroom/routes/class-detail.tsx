/**
 * Màn hình chi tiết một lớp của feature classroom (issue #102).
 *
 * Flow: lớp được tra từ danh sách lớp của trung tâm (cùng cache với trang chính), danh sách thành viên tải riêng
 * theo lớp. Quản trị trung tâm thấy hai thao tác quản trị của lớp — đặt giáo viên phụ trách và ghi danh/gỡ học
 * viên; ghi danh nhận cả học viên không là thành viên của trung tâm, và học viên đó tham gia lớp với tư cách
 * khách theo đúng mô hình của server. Giáo viên và học viên chỉ đọc danh sách theo phạm vi server trả.
 *
 * Bất biến hiển thị: màn hình không tự chặn thao tác theo vai ngoài việc ẩn form quản trị khỏi vai không quản
 * trị để dẫn luồng; mọi quyết định quyền thật vẫn do server trả lời từng lời gọi.
 */
import { useState, type FormEvent } from 'react';
import { Link, useParams } from 'react-router';
import { Button } from '@lms/ui/components/button';
import { Card, CardContent, CardHeader, CardTitle } from '@lms/ui/components/card';
import { Input } from '@lms/ui/components/input';
import { Label } from '@lms/ui/components/label';
import { PageShell } from '#components/page-shell';
import { EmptyNote, ErrorNote, LoadingNote, SessionGate } from '../components/states';
import { useClassroomSession } from '../hooks/use-session';
import {
  useAddMember,
  useClasses,
  useMembers,
  useRemoveMember,
  useSetClassTeacher,
} from '../queries/hooks';
import { formatDateTime } from '../utils/datetime';

/**
 * Màn hình chi tiết lớp: bọc cổng phiên quanh nội dung của lớp được chọn trên đường dẫn.
 *
 * @returns Phần tử của màn hình chi tiết lớp.
 */
export default function ClassroomClassDetail() {
  const session = useClassroomSession();
  const { classId } = useParams();
  return (
    <SessionGate session={session}>
      {(ready) => (
        <PageShell width="wide">
          <div className="flex flex-col gap-4">
            <p>
              <Link className="text-sm underline-offset-4 hover:underline" to="/classroom">
                ← Về trang chính
              </Link>
            </p>
            {classId ? (
              <ClassDetailBody classId={classId} isCenterAdmin={ready.isCenterAdmin} />
            ) : (
              <EmptyNote>Đường dẫn thiếu mã lớp.</EmptyNote>
            )}
          </div>
        </PageShell>
      )}
    </SessionGate>
  );
}

/**
 * Thân màn hình chi tiết lớp: thông tin lớp, danh sách thành viên và — với quản trị trung tâm — các form phân
 * công giáo viên và ghi danh/gỡ học viên.
 *
 * @param props - Định danh lớp và cờ vai quản trị.
 * @returns Phần tử thân màn hình chi tiết lớp.
 */
function ClassDetailBody({ classId, isCenterAdmin }: { classId: string; isCenterAdmin: boolean }) {
  const classesQuery = useClasses();
  const membersQuery = useMembers(classId);
  const teachingClass = (classesQuery.data ?? []).find((item) => item.id === classId);
  return (
    <>
      <Card>
        <CardHeader>
          <CardTitle>{teachingClass ? teachingClass.name : 'Lớp học'}</CardTitle>
        </CardHeader>
        <CardContent className="flex flex-col gap-4">
          {classesQuery.isPending ? <LoadingNote label="Đang tải thông tin lớp…" /> : null}
          {classesQuery.isError ? (
            <ErrorNote error={classesQuery.error} onRetry={() => void classesQuery.refetch()} />
          ) : null}
          {teachingClass ? (
            <p className="text-sm text-muted-foreground">
              Giáo viên phụ trách: {teachingClass.teacherSub ?? 'chưa phân công'}
            </p>
          ) : null}
          {isCenterAdmin ? <SetTeacherForm classId={classId} /> : null}
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>Học viên của lớp</CardTitle>
        </CardHeader>
        <CardContent className="flex flex-col gap-4">
          {membersQuery.isPending ? <LoadingNote label="Đang tải danh sách học viên…" /> : null}
          {membersQuery.isError ? (
            <ErrorNote error={membersQuery.error} onRetry={() => void membersQuery.refetch()} />
          ) : null}
          {membersQuery.data && membersQuery.data.length === 0 ? (
            <EmptyNote>Lớp chưa có học viên nào.</EmptyNote>
          ) : null}
          <ul className="flex flex-col gap-2">
            {(membersQuery.data ?? []).map((member) => (
              <li key={member.studentSub} className="flex flex-wrap items-center justify-between gap-2">
                <div className="flex flex-col">
                  <span className="font-medium">{member.studentSub}</span>
                  <span className="text-sm text-muted-foreground">
                    Ghi danh lúc {formatDateTime(member.enrolledAt)} bởi {member.enrolledBySub}
                  </span>
                </div>
                {isCenterAdmin ? <RemoveMemberButton classId={classId} studentSub={member.studentSub} /> : null}
              </li>
            ))}
          </ul>
          {isCenterAdmin ? <AddMemberForm classId={classId} /> : null}
        </CardContent>
      </Card>
    </>
  );
}

/**
 * Form đặt giáo viên phụ trách của lớp cho quản trị trung tâm.
 *
 * Side-effect: gửi `PUT /api/classes/{id}/teacher` qua mutation; thành công thì danh sách lớp tự tải lại.
 *
 * @param props - Định danh lớp.
 * @returns Phần tử form phân công giáo viên.
 */
function SetTeacherForm({ classId }: { classId: string }) {
  const [teacherSub, setTeacherSub] = useState('');
  const setTeacher = useSetClassTeacher();
  const onSubmit = (event: FormEvent) => {
    event.preventDefault();
    setTeacher.mutate({ classId, teacherSub: teacherSub.trim() }, { onSuccess: () => setTeacherSub('') });
  };
  return (
    <form className="flex flex-col gap-2" onSubmit={onSubmit}>
      <Label htmlFor="class-teacher-sub">Mã định danh (sub) của giáo viên phụ trách</Label>
      <div className="flex gap-2">
        <Input
          id="class-teacher-sub"
          value={teacherSub}
          onChange={(event) => setTeacherSub(event.target.value)}
          required
        />
        <Button type="submit" disabled={setTeacher.isPending || teacherSub.trim() === ''}>
          Phân công
        </Button>
      </div>
      {setTeacher.isError ? <ErrorNote error={setTeacher.error} /> : null}
    </form>
  );
}

/**
 * Form ghi danh một học viên vào lớp cho quản trị trung tâm. Học viên không là thành viên của trung tâm vẫn ghi
 * danh được và tham gia lớp với tư cách khách — ghi rõ điều này ngay tại form để người vận hành không nhầm ghi
 * danh với việc cấp tư cách thành viên.
 *
 * Side-effect: gửi `POST /api/classes/{id}/members` qua mutation; thành công thì danh sách thành viên tải lại.
 *
 * @param props - Định danh lớp.
 * @returns Phần tử form ghi danh.
 */
function AddMemberForm({ classId }: { classId: string }) {
  const [studentSub, setStudentSub] = useState('');
  const addMember = useAddMember();
  const onSubmit = (event: FormEvent) => {
    event.preventDefault();
    addMember.mutate({ classId, studentSub: studentSub.trim() }, { onSuccess: () => setStudentSub('') });
  };
  return (
    <form className="flex flex-col gap-2" onSubmit={onSubmit}>
      <Label htmlFor="class-student-sub">Mã định danh (sub) của học viên</Label>
      <div className="flex gap-2">
        <Input
          id="class-student-sub"
          value={studentSub}
          onChange={(event) => setStudentSub(event.target.value)}
          required
        />
        <Button type="submit" disabled={addMember.isPending || studentSub.trim() === ''}>
          Ghi danh
        </Button>
      </div>
      <p className="text-sm text-muted-foreground">
        Học viên chưa là thành viên của trung tâm sẽ tham gia lớp này với tư cách khách và chỉ thấy đúng lớp này.
      </p>
      {addMember.isError ? <ErrorNote error={addMember.error} /> : null}
    </form>
  );
}

/**
 * Nút gỡ ghi danh của một học viên khỏi lớp.
 *
 * Side-effect: gửi `DELETE /api/classes/{id}/members/{studentSub}` qua mutation; thành công thì danh sách
 * thành viên tải lại.
 *
 * @param props - Định danh lớp và của học viên.
 * @returns Phần tử nút gỡ ghi danh kèm lỗi nếu có.
 */
function RemoveMemberButton({ classId, studentSub }: { classId: string; studentSub: string }) {
  const removeMember = useRemoveMember();
  return (
    <span className="flex items-center gap-2">
      <Button
        type="button"
        variant="outline"
        size="sm"
        disabled={removeMember.isPending}
        onClick={() => removeMember.mutate({ classId, studentSub })}
      >
        Gỡ khỏi lớp
      </Button>
      {removeMember.isError ? <ErrorNote error={removeMember.error} /> : null}
    </span>
  );
}
