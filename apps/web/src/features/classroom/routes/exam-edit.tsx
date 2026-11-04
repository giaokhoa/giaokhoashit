/**
 * Màn hình soạn, phát hành và giao một đề của feature classroom (issue #102).
 *
 * Flow: đề tải theo mã trên đường dẫn. Khi phiên bản đang xem chưa khoá, giáo viên sửa tiêu đề và danh sách mục
 * — mỗi mục là một phiên bản câu kèm điểm — rồi lưu kèm `lockVersion` để server chống ghi đè lạc hậu. Mục được
 * thêm bằng cách tra một câu theo mã, chọn đúng phiên bản của câu đó (thường là bản đã phát hành) và đặt điểm.
 * Phát hành đề thì server đóng băng snapshot của từng câu; từ trạng thái đã phát hành, màn hình mở form giao
 * chính phiên bản này cho một lớp theo cửa sổ thời gian.
 *
 * Bất biến hiển thị: danh sách mục đang sửa là trạng thái cục bộ khởi tạo từ đáp ứng của server và chỉ trở thành
 * sự thật khi lưu thành công; đề đã khoá không còn form sửa, chỉ còn thông tin đóng băng và form giao bài.
 */
import { useState, type FormEvent } from 'react';
import { Link, useParams } from 'react-router';
import { Badge } from '@lms/ui/components/badge';
import { Button } from '@lms/ui/components/button';
import { Card, CardContent, CardHeader, CardTitle } from '@lms/ui/components/card';
import { Input } from '@lms/ui/components/input';
import { Label } from '@lms/ui/components/label';
import { NativeSelect } from '@lms/ui/components/native-select';
import { PageShell } from '#components/page-shell';
import { fetchQuestion } from '../api/client';
import { displayErrorMessage } from '../api/errors';
import { EmptyNote, ErrorNote, LoadingNote, SessionGate } from '../components/states';
import { useClassroomSession } from '../hooks/use-session';
import {
  useAssignments,
  useClasses,
  useCreateAssignment,
  useCreateExamVersion,
  useExam,
  usePublishExamVersion,
  useUpdateExamVersion,
} from '../queries/hooks';
import type { ExamView, QuestionView } from '../types/server';
import { formatDateTime, localInputToIso } from '../utils/datetime';

/** Một mục đang soạn trong form: phiên bản câu được ghim và điểm của mục. */
interface DraftItem {
  questionVersionId: string;
  score: number;
}

/**
 * Màn hình soạn đề: bọc cổng phiên quanh thân màn hình của đề được chọn trên đường dẫn.
 *
 * @returns Phần tử của màn hình soạn đề.
 */
export default function ClassroomExamEdit() {
  const session = useClassroomSession();
  const { examId } = useParams();
  return (
    <SessionGate session={session}>
      {() => (
        <PageShell width="wide">
          <div className="flex flex-col gap-4">
            <p>
              <Link className="text-sm underline-offset-4 hover:underline" to="/classroom">
                ← Về trang chính
              </Link>
            </p>
            {examId ? <ExamEditBody examId={examId} /> : <EmptyNote>Đường dẫn thiếu mã đề.</EmptyNote>}
          </div>
        </PageShell>
      )}
    </SessionGate>
  );
}

/**
 * Thân màn hình soạn đề: thông tin phiên bản, form soạn khi chưa khoá, hành động phát hành/mở bản mới và — khi
 * đã phát hành — form giao bài kèm các lần giao đã tạo từ phiên bản này.
 *
 * @param props - Định danh đề.
 * @returns Phần tử thân màn hình soạn đề.
 */
function ExamEditBody({ examId }: { examId: string }) {
  const examQuery = useExam(examId);
  if (examQuery.isPending) return <LoadingNote label="Đang tải đề…" />;
  if (examQuery.isError) return <ErrorNote error={examQuery.error} onRetry={() => void examQuery.refetch()} />;
  const exam = examQuery.data;
  const isLocked = exam.lockedAt !== null;
  return (
    <>
      <Card>
        <CardHeader>
          <CardTitle>{exam.title}</CardTitle>
        </CardHeader>
        <CardContent className="flex flex-col gap-2">
          <p className="flex flex-wrap items-center gap-2 text-sm text-muted-foreground">
            <span>
              Phiên bản {exam.versionNumber} · Mã phiên bản: {exam.versionId}
            </span>
            <Badge variant="secondary">{exam.status}</Badge>
            {isLocked ? <span>Đã đóng băng lúc {formatDateTime(exam.lockedAt)}</span> : <span>Bản nháp</span>}
          </p>
          {exam.contentHash ? (
            <p className="text-sm text-muted-foreground">Dấu đóng băng (contentHash): {exam.contentHash}</p>
          ) : null}
          <ExamActions exam={exam} />
        </CardContent>
      </Card>
      {isLocked ? (
        <AssignmentPanel exam={exam} />
      ) : (
        <DraftItemsForm key={exam.versionId} exam={exam} />
      )}
    </>
  );
}

/**
 * Hành động vòng đời của phiên bản đề đang xem: phát hành khi chưa khoá, mở bản nháp mới từ phiên bản này.
 *
 * Side-effect: gọi các mutation tương ứng; đáp ứng được ghi vào cache của đề.
 *
 * @param props - Đề đang xem.
 * @returns Phần tử hàng hành động của đề.
 */
function ExamActions({ exam }: { exam: ExamView }) {
  const publishExam = usePublishExamVersion();
  const createVersion = useCreateExamVersion();
  return (
    <div className="flex flex-col gap-2">
      <div className="flex flex-wrap gap-2">
        {exam.lockedAt === null ? (
          <Button
            type="button"
            disabled={publishExam.isPending}
            onClick={() => publishExam.mutate({ examId: exam.id, versionId: exam.versionId })}
          >
            Phát hành đề (đóng băng các câu)
          </Button>
        ) : null}
        <Button
          type="button"
          variant="outline"
          disabled={createVersion.isPending}
          onClick={() => createVersion.mutate({ examId: exam.id, sourceVersionId: exam.versionId })}
        >
          Mở bản nháp mới từ phiên bản này
        </Button>
      </div>
      {publishExam.isError ? <ErrorNote error={publishExam.error} /> : null}
      {createVersion.isError ? <ErrorNote error={createVersion.error} /> : null}
    </div>
  );
}

/**
 * Form soạn nội dung một phiên bản đề nháp: tiêu đề, danh sách mục (sửa điểm, gỡ mục), tra câu theo mã để thêm
 * mục, và lưu toàn bộ danh sách kèm `lockVersion` hiện tại.
 *
 * Side-effect: lưu gửi `PUT /api/exams/{id}/versions/{versionId}`; tra câu gọi `GET /api/questions/{id}` trực
 * tiếp một lần khi giáo viên bấm tra.
 *
 * @param props - Đề đang soạn (dữ kiện khởi tạo của form, gồm `lockVersion` hiện tại).
 * @returns Phần tử form soạn đề.
 */
function DraftItemsForm({ exam }: { exam: ExamView }) {
  const updateExam = useUpdateExamVersion();
  const [title, setTitle] = useState(exam.title);
  const [items, setItems] = useState<DraftItem[]>(
    exam.items.map((item) => ({ questionVersionId: item.questionVersionId, score: item.score })),
  );
  const itemTypeByVersionId = new Map(exam.items.map((item) => [item.questionVersionId, item.questionType]));

  const onSubmit = (event: FormEvent) => {
    event.preventDefault();
    updateExam.mutate({
      examId: exam.id,
      versionId: exam.versionId,
      input: { title: title.trim(), lockVersion: exam.lockVersion, items },
    });
  };

  return (
    <Card>
      <CardHeader>
        <CardTitle>Soạn nội dung đề</CardTitle>
      </CardHeader>
      <CardContent>
        <form className="flex flex-col gap-4" onSubmit={onSubmit}>
          <div className="flex flex-col gap-2">
            <Label htmlFor="exam-title">Tiêu đề đề</Label>
            <Input id="exam-title" value={title} onChange={(event) => setTitle(event.target.value)} required />
          </div>
          <ol className="flex flex-col gap-2">
            {items.map((item, index) => (
              <li key={`${item.questionVersionId}-${index}`} className="flex flex-wrap items-center gap-2">
                <span className="font-medium">Mục {index + 1}</span>
                <span className="text-sm text-muted-foreground">
                  Phiên bản câu: {item.questionVersionId}
                  {itemTypeByVersionId.get(item.questionVersionId)
                    ? ` · ${itemTypeByVersionId.get(item.questionVersionId)}`
                    : ''}
                </span>
                <Label htmlFor={`item-score-${index}`}>Điểm</Label>
                <Input
                  id={`item-score-${index}`}
                  type="number"
                  min="0"
                  step="0.25"
                  className="w-24"
                  value={item.score}
                  onChange={(event) =>
                    setItems((current) =>
                      current.map((row, rowIndex) =>
                        rowIndex === index ? { ...row, score: Number(event.target.value) } : row,
                      ),
                    )
                  }
                />
                <Button
                  type="button"
                  variant="outline"
                  size="sm"
                  onClick={() => setItems((current) => current.filter((_, rowIndex) => rowIndex !== index))}
                >
                  Gỡ mục
                </Button>
              </li>
            ))}
          </ol>
          {items.length === 0 ? <EmptyNote>Đề chưa có mục nào. Hãy tra câu theo mã để thêm mục.</EmptyNote> : null}
          <AddItemByQuestion
            onAdd={(questionVersionId, score) => setItems((current) => [...current, { questionVersionId, score }])}
          />
          <div>
            <Button type="submit" disabled={updateExam.isPending || title.trim() === ''}>
              Lưu nội dung đề
            </Button>
          </div>
          {updateExam.isError ? <ErrorNote error={updateExam.error} /> : null}
          {updateExam.isSuccess ? <p className="text-sm text-muted-foreground">Đã lưu nội dung đề.</p> : null}
        </form>
      </CardContent>
    </Card>
  );
}

/**
 * Khối tra một câu theo mã và thêm một phiên bản của câu đó thành mục của đề: sau khi tra, giáo viên chọn đúng
 * phiên bản (bản đã phát hành là bản nên ghim) và đặt điểm cho mục.
 *
 * Side-effect: tra câu gọi thẳng tầng api một lần cho mỗi lần bấm tra; kết quả chỉ sống trong khối này.
 *
 * @param props - Hàm nhận mục mới (phiên bản câu + điểm) để form soạn thêm vào danh sách.
 * @returns Phần tử khối thêm mục theo câu.
 */
function AddItemByQuestion({ onAdd }: { onAdd: (questionVersionId: string, score: number) => void }) {
  const [questionId, setQuestionId] = useState('');
  const [question, setQuestion] = useState<QuestionView | null>(null);
  const [versionId, setVersionId] = useState('');
  const [score, setScore] = useState(1);
  const [lookupError, setLookupError] = useState<string | null>(null);
  const [lookingUp, setLookingUp] = useState(false);

  const lookup = async () => {
    setLookingUp(true);
    setLookupError(null);
    try {
      const found = await fetchQuestion(questionId.trim());
      setQuestion(found);
      const latestPublished = [...found.versions]
        .sort((a, b) => b.versionNumber - a.versionNumber)
        .find((version) => version.lockedAt !== null);
      setVersionId(latestPublished?.versionId ?? found.versions[0]?.versionId ?? '');
    } catch (error) {
      setQuestion(null);
      setLookupError(displayErrorMessage(error));
    } finally {
      setLookingUp(false);
    }
  };

  return (
    <fieldset className="flex flex-col gap-2">
      <legend className="text-sm font-medium">Thêm mục từ một câu trong ngân hàng</legend>
      <div className="flex gap-2">
        <Input
          aria-label="Mã câu hỏi"
          value={questionId}
          onChange={(event) => setQuestionId(event.target.value)}
          placeholder="Mã câu hỏi"
        />
        <Button type="button" variant="outline" disabled={lookingUp || questionId.trim() === ''} onClick={() => void lookup()}>
          Tra câu
        </Button>
      </div>
      {lookupError ? (
        <p role="alert" className="text-sm text-destructive">
          {lookupError}
        </p>
      ) : null}
      {question ? (
        <div className="flex flex-wrap items-end gap-2">
          <div className="flex flex-col gap-2">
            <Label htmlFor="add-item-version">Phiên bản của câu</Label>
            <NativeSelect id="add-item-version" value={versionId} onChange={(event) => setVersionId(event.target.value)}>
              {[...question.versions]
                .sort((a, b) => b.versionNumber - a.versionNumber)
                .map((version) => (
                  <option key={version.versionId} value={version.versionId}>
                    Bản {version.versionNumber} — {version.status} — {version.stem.slice(0, 40)}
                  </option>
                ))}
            </NativeSelect>
          </div>
          <div className="flex flex-col gap-2">
            <Label htmlFor="add-item-score">Điểm của mục</Label>
            <Input
              id="add-item-score"
              type="number"
              min="0"
              step="0.25"
              className="w-24"
              value={score}
              onChange={(event) => setScore(Number(event.target.value))}
            />
          </div>
          <Button
            type="button"
            disabled={versionId === ''}
            onClick={() => {
              onAdd(versionId, score);
              setQuestion(null);
              setQuestionId('');
            }}
          >
            Thêm mục
          </Button>
        </div>
      ) : null}
    </fieldset>
  );
}

/**
 * Khối giao bài của một phiên bản đề đã phát hành: chọn lớp, đặt cửa sổ mở/đóng theo giờ địa phương và tạo lần
 * giao; các lần giao đã tạo từ đúng phiên bản này được liệt kê ngay dưới form.
 *
 * Side-effect: tạo lần giao gửi `POST /api/assignments` qua mutation; danh sách lần giao tự tải lại.
 *
 * @param props - Đề ở phiên bản đã phát hành.
 * @returns Phần tử khối giao bài.
 */
function AssignmentPanel({ exam }: { exam: ExamView }) {
  const classesQuery = useClasses();
  const assignmentsQuery = useAssignments();
  const createAssignment = useCreateAssignment();
  const [classId, setClassId] = useState('');
  const [opensAt, setOpensAt] = useState('');
  const [dueAt, setDueAt] = useState('');
  const [windowError, setWindowError] = useState<string | null>(null);
  const selectedClassId = classId || (classesQuery.data?.[0]?.id ?? '');
  const ownAssignments = (assignmentsQuery.data ?? []).filter((item) => item.examVersionId === exam.versionId);

  const onSubmit = (event: FormEvent) => {
    event.preventDefault();
    const opensIso = localInputToIso(opensAt);
    const dueIso = localInputToIso(dueAt);
    if (!opensIso || !dueIso || selectedClassId === '') {
      setWindowError('Hãy chọn lớp và điền đủ thời điểm mở và hạn nộp.');
      return;
    }
    setWindowError(null);
    createAssignment.mutate({ examVersionId: exam.versionId, classId: selectedClassId, opensAt: opensIso, dueAt: dueIso });
  };

  return (
    <Card>
      <CardHeader>
        <CardTitle>Giao phiên bản này cho lớp</CardTitle>
      </CardHeader>
      <CardContent className="flex flex-col gap-4">
        <form className="flex flex-col gap-4" onSubmit={onSubmit}>
          <div className="flex flex-col gap-2">
            <Label htmlFor="assignment-class">Lớp nhận bài</Label>
            <NativeSelect
              id="assignment-class"
              value={selectedClassId}
              onChange={(event) => setClassId(event.target.value)}
            >
              {(classesQuery.data ?? []).map((teachingClass) => (
                <option key={teachingClass.id} value={teachingClass.id}>
                  {teachingClass.name}
                </option>
              ))}
            </NativeSelect>
          </div>
          <div className="grid gap-4 md:grid-cols-2">
            <div className="flex flex-col gap-2">
              <Label htmlFor="assignment-opens">Mở bài lúc</Label>
              <Input
                id="assignment-opens"
                type="datetime-local"
                value={opensAt}
                onChange={(event) => setOpensAt(event.target.value)}
                required
              />
            </div>
            <div className="flex flex-col gap-2">
              <Label htmlFor="assignment-due">Hạn nộp lúc</Label>
              <Input
                id="assignment-due"
                type="datetime-local"
                value={dueAt}
                onChange={(event) => setDueAt(event.target.value)}
                required
              />
            </div>
          </div>
          <div>
            <Button type="submit" disabled={createAssignment.isPending || selectedClassId === ''}>
              Giao bài
            </Button>
          </div>
          {windowError ? (
            <p role="alert" className="text-sm text-destructive">
              {windowError}
            </p>
          ) : null}
          {createAssignment.isError ? <ErrorNote error={createAssignment.error} /> : null}
          {createAssignment.isSuccess ? <p className="text-sm text-muted-foreground">Đã tạo lần giao.</p> : null}
        </form>
        {ownAssignments.length > 0 ? (
          <ul className="flex flex-col gap-2">
            {ownAssignments.map((assignment) => (
              <li key={assignment.id} className="text-sm">
                Đã giao cho lớp {assignment.classId} · Mở: {formatDateTime(assignment.opensAt)} · Hạn:{' '}
                {formatDateTime(assignment.dueAt)}
              </li>
            ))}
          </ul>
        ) : null}
      </CardContent>
    </Card>
  );
}
