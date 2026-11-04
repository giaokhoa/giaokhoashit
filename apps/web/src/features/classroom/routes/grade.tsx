/**
 * Màn hình bài nộp, chấm và công bố của giáo viên theo một lần giao (issue #102).
 *
 * Flow: bảng bài nộp của lần giao tải từ `apps/server` — mỗi dòng là một học viên với trạng thái lượt làm và,
 * khi lát chấm/công bố phục vụ, điểm tạm/điểm cuối của lượt. Giáo viên phụ trách chọn một lượt đã nộp để mở
 * hình chiếu chấm (thân câu, đáp án đúng, tiêu chí chấm, câu trả lời và điểm hiện có của từng mục), chấm các
 * mục tự luận theo từng mục kèm nhận xét, rồi công bố kết quả cho cả lần giao bằng một thao tác riêng.
 *
 * Bất biến hiển thị: điểm của học viên chỉ xuất hiện ở màn hình này khi server trả trường điểm — trước khi lát
 * chấm/công bố merge, các trường điểm vắng mặt và bảng chỉ hiện trạng thái; màn hình không tự tính điểm. Phần
 * này xây theo hợp đồng của lát chấm/công bố và chờ PR #86 merge để kiểm chứng tích hợp cuối trên server thật
 * (ghi rõ trong PR của issue này).
 */
import { useState, type FormEvent } from 'react';
import { Link, useParams } from 'react-router';
import { Button } from '@lms/ui/components/button';
import { Card, CardContent, CardHeader, CardTitle } from '@lms/ui/components/card';
import { Input } from '@lms/ui/components/input';
import { Label } from '@lms/ui/components/label';
import { Textarea } from '@lms/ui/components/textarea';
import { PageShell } from '#components/page-shell';
import { EmptyNote, ErrorNote, LoadingNote, SessionGate } from '../components/states';
import { useClassroomSession } from '../hooks/use-session';
import {
  useGradeAttempt,
  usePublishResults,
  useSubmissions,
  useTeacherAttempt,
} from '../queries/hooks';
import type { SubmissionRowView, TeacherAttemptView } from '../types/server';
import { formatDateTime } from '../utils/datetime';

/**
 * Màn hình chấm theo lần giao: bọc cổng phiên quanh bảng bài nộp của lần giao trên đường dẫn.
 *
 * @returns Phần tử của màn hình chấm.
 */
export default function ClassroomGrade() {
  const session = useClassroomSession();
  const { assignmentId } = useParams();
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
            {assignmentId ? (
              <GradeBody assignmentId={assignmentId} />
            ) : (
              <EmptyNote>Đường dẫn thiếu mã lần giao.</EmptyNote>
            )}
          </div>
        </PageShell>
      )}
    </SessionGate>
  );
}

/**
 * Thân màn hình chấm: bảng bài nộp kèm nút công bố theo lần giao, và khung chấm của lượt đang chọn.
 *
 * Side-effect: công bố gửi `POST /api/assignments/{id}/publish-results` qua mutation; thành công thì bảng bài
 * nộp tải lại.
 *
 * @param props - Định danh lần giao.
 * @returns Phần tử thân màn hình chấm.
 */
function GradeBody({ assignmentId }: { assignmentId: string }) {
  const submissionsQuery = useSubmissions(assignmentId);
  const publishResults = usePublishResults();
  const [selectedAttemptId, setSelectedAttemptId] = useState<string | null>(null);

  return (
    <div className="flex flex-col gap-4">
      <Card>
        <CardHeader>
          <CardTitle>Bài nộp của lần giao</CardTitle>
        </CardHeader>
        <CardContent className="flex flex-col gap-4">
          {submissionsQuery.isPending ? <LoadingNote label="Đang tải bảng bài nộp…" /> : null}
          {submissionsQuery.isError ? (
            <ErrorNote error={submissionsQuery.error} onRetry={() => void submissionsQuery.refetch()} />
          ) : null}
          {submissionsQuery.data && submissionsQuery.data.length === 0 ? (
            <EmptyNote>Lần giao này chưa có học viên nào trong danh sách.</EmptyNote>
          ) : null}
          <SubmissionsTable rows={submissionsQuery.data ?? []} />
          <div className="flex flex-col gap-2">
            <div>
              <Button
                type="button"
                disabled={publishResults.isPending}
                onClick={() => publishResults.mutate(assignmentId)}
              >
                Công bố kết quả của lần giao này
              </Button>
            </div>
            {publishResults.isError ? <ErrorNote error={publishResults.error} /> : null}
            {publishResults.isSuccess ? (
              <p className="text-sm text-muted-foreground">
                Đã công bố lúc {formatDateTime(publishResults.data.publishedAt)} bởi {publishResults.data.publishedBySub}.
              </p>
            ) : null}
          </div>
        </CardContent>
      </Card>
      {selectedAttemptId ? (
        <GradingPanel key={selectedAttemptId} assignmentId={assignmentId} attemptId={selectedAttemptId} />
      ) : null}
      <OpenAttemptById onOpen={setSelectedAttemptId} />
    </div>
  );
}

/**
 * Bảng bài nộp: một dòng cho mỗi học viên với trạng thái lượt, thời điểm nộp và điểm khi server trả trường
 * điểm. Cột điểm phân biệt điểm tạm (còn mục tự luận chưa chấm) và điểm đã công bố đúng theo các cờ của server.
 *
 * @param props - Các dòng bài nộp.
 * @returns Phần tử bảng bài nộp.
 */
function SubmissionsTable({ rows }: { rows: SubmissionRowView[] }) {
  if (rows.length === 0) return null;
  return (
    <table className="w-full text-sm">
      <thead>
        <tr className="text-left">
          <th className="py-1 pr-2 font-medium">Học viên</th>
          <th className="py-1 pr-2 font-medium">Trạng thái</th>
          <th className="py-1 pr-2 font-medium">Nộp lúc</th>
          <th className="py-1 pr-2 font-medium">Điểm</th>
        </tr>
      </thead>
      <tbody>
        {rows.map((row) => (
          <tr key={row.studentSub} className="border-t">
            <td className="py-1 pr-2">
              {row.studentSub}
              {row.removed ? ' (đã gỡ ghi danh)' : ''}
            </td>
            <td className="py-1 pr-2">{row.status ?? 'Chưa bắt đầu'}</td>
            <td className="py-1 pr-2">{formatDateTime(row.submittedAt)}</td>
            <td className="py-1 pr-2">{scoreLabelOf(row)}</td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

/**
 * Diễn giải trường điểm của một dòng bài nộp thành nhãn hiển thị: chưa có điểm khi server chưa trả, điểm tạm
 * khi còn cờ `provisional`, và điểm đã công bố khi cờ `published` bật.
 *
 * @param row - Dòng bài nộp.
 * @returns Nhãn điểm để hiển thị.
 */
export function scoreLabelOf(row: SubmissionRowView): string {
  if (row.totalScore === undefined || row.totalScore === null) return '—';
  const score = `${row.totalScore}/${row.maxScore ?? '—'}`;
  if (row.published) return `${score} (đã công bố)`;
  if (row.provisional) return `${score} (tạm)`;
  return score;
}

/**
 * Khối mở khung chấm bằng mã lượt làm do giáo viên nhập: bảng bài nộp của server không mang attemptId nên
 * đường vào khung chấm hiện tại là mã lượt (học viên thấy mã lượt của mình ở màn làm bài qua dữ kiện lượt).
 *
 * @param props - Hàm nhận attemptId để mở khung chấm.
 * @returns Phần tử form mở khung chấm theo mã lượt.
 */
function OpenAttemptById({ onOpen }: { onOpen: (attemptId: string) => void }) {
  const [attemptId, setAttemptId] = useState('');
  const onSubmit = (event: FormEvent) => {
    event.preventDefault();
    if (attemptId.trim() !== '') onOpen(attemptId.trim());
  };
  return (
    <Card>
      <CardHeader>
        <CardTitle>Mở lượt làm để chấm</CardTitle>
      </CardHeader>
      <CardContent>
        <form className="flex flex-col gap-2" onSubmit={onSubmit}>
          <Label htmlFor="grade-attempt-id">Mã lượt làm</Label>
          <div className="flex gap-2">
            <Input
              id="grade-attempt-id"
              value={attemptId}
              onChange={(event) => setAttemptId(event.target.value)}
              required
            />
            <Button type="submit" variant="outline" disabled={attemptId.trim() === ''}>
              Mở khung chấm
            </Button>
          </div>
        </form>
      </CardContent>
    </Card>
  );
}

/**
 * Khung chấm một lượt làm: từng mục kèm thân câu, đáp án đúng/tiêu chí chấm (chỉ giáo viên phụ trách mới nhận
 * được hình chiếu này), câu trả lời của học viên và điểm hiện có. Mục trắc nghiệm đã có điểm tự động — chỉ đọc;
 * mục tự luận chấm tay bằng ô điểm và nhận xét theo mục. Một lần lưu gửi toàn bộ mục tự luận kèm nhận xét chung.
 *
 * Side-effect: lưu gửi `POST /api/attempts/{id}/grade` qua mutation; thành công thì hình chiếu chấm và bảng
 * bài nộp tải lại.
 *
 * @param props - Định danh lần giao (để vô hiệu hoá bảng bài nộp) và định danh lượt làm.
 * @returns Phần tử khung chấm.
 */
function GradingPanel({ assignmentId, attemptId }: { assignmentId: string; attemptId: string }) {
  const attemptQuery = useTeacherAttempt(attemptId, true);
  if (attemptQuery.isPending) return <LoadingNote label="Đang tải hình chiếu chấm…" />;
  if (attemptQuery.isError) {
    return <ErrorNote error={attemptQuery.error} onRetry={() => void attemptQuery.refetch()} />;
  }
  return <GradingForm key={attemptQuery.data.attemptId} assignmentId={assignmentId} attempt={attemptQuery.data} />;
}

/**
 * Form chấm của một lượt: state khởi tạo từ hình chiếu chấm của server (điểm/nhận xét hiện có của từng mục tự
 * luận và nhận xét chung).
 *
 * @param props - Định danh lần giao và hình chiếu chấm của lượt.
 * @returns Phần tử form chấm.
 */
function GradingForm({ assignmentId, attempt }: { assignmentId: string; attempt: TeacherAttemptView }) {
  const gradeAttempt = useGradeAttempt();
  const essayItems = attempt.items.filter((item) => item.questionType === 'essay_text');
  const [scores, setScores] = useState<Record<string, string>>(() => {
    const initial: Record<string, string> = {};
    for (const item of essayItems) {
      initial[item.examItemId] = item.grade.score === null ? '' : String(item.grade.score);
    }
    return initial;
  });
  const [comments, setComments] = useState<Record<string, string>>(() => {
    const initial: Record<string, string> = {};
    for (const item of essayItems) {
      initial[item.examItemId] = item.grade.comment ?? '';
    }
    return initial;
  });
  const [overallComment, setOverallComment] = useState(attempt.overallComment ?? '');

  const onSubmit = (event: FormEvent) => {
    event.preventDefault();
    gradeAttempt.mutate({
      attemptId: attempt.attemptId,
      assignmentId,
      input: {
        items: essayItems.map((item) => ({
          examItemId: item.examItemId,
          score: Number(scores[item.examItemId] ?? 0),
          comment: (comments[item.examItemId] ?? '').trim() === '' ? null : (comments[item.examItemId] ?? '').trim(),
        })),
        comment: overallComment.trim() === '' ? null : overallComment.trim(),
      },
    });
  };

  return (
    <Card>
      <CardHeader>
        <CardTitle>Chấm lượt của {attempt.studentSub}</CardTitle>
      </CardHeader>
      <CardContent>
        <form className="flex flex-col gap-4" onSubmit={onSubmit}>
          <p className="text-sm text-muted-foreground">
            Trạng thái lượt: {attempt.status}
            {attempt.totalScore !== null ? ` · Tổng hiện tại: ${attempt.totalScore}/${attempt.maxScore}` : ''}
            {attempt.provisional ? ' · Điểm tạm (còn mục chưa chấm)' : ''}
          </p>
          {attempt.items.map((item, index) => (
            <fieldset key={item.examItemId} className="flex flex-col gap-2 border-t pt-3">
              <legend className="text-sm font-medium">
                Câu {index + 1} — {item.questionType === 'single_choice' ? 'Trắc nghiệm' : 'Tự luận'} (tối đa{' '}
                {item.maxScore} điểm)
              </legend>
              <p className="whitespace-pre-wrap">{item.stem}</p>
              {item.questionType === 'single_choice' ? (
                <>
                  <p className="text-sm">
                    Đáp án đúng: {item.choices.find((choice) => choice.id === item.correctChoiceId)?.text ?? '—'}
                  </p>
                  <p className="text-sm">
                    Học viên chọn:{' '}
                    {item.choices.find((choice) => choice.id === item.answer?.['choiceId'])?.text ?? 'Chưa trả lời'}
                  </p>
                  <p className="text-sm">
                    Điểm tự động: {item.grade.score ?? '—'}/{item.grade.maxScore}
                  </p>
                </>
              ) : (
                <>
                  {item.rubric ? (
                    <p className="text-sm">
                      <span className="font-medium">Tiêu chí chấm:</span> {item.rubric}
                    </p>
                  ) : null}
                  <p className="whitespace-pre-wrap text-sm">
                    <span className="font-medium">Bài viết:</span>{' '}
                    {typeof item.answer?.['text'] === 'string' ? item.answer['text'] : 'Chưa trả lời'}
                  </p>
                  <div className="flex flex-wrap items-end gap-2">
                    <div className="flex flex-col gap-2">
                      <Label htmlFor={`grade-score-${item.examItemId}`}>Điểm của mục</Label>
                      <Input
                        id={`grade-score-${item.examItemId}`}
                        type="number"
                        min="0"
                        max={item.maxScore}
                        step="0.25"
                        className="w-28"
                        value={scores[item.examItemId] ?? ''}
                        onChange={(event) =>
                          setScores((current) => ({ ...current, [item.examItemId]: event.target.value }))
                        }
                        required
                      />
                    </div>
                    <div className="flex flex-1 flex-col gap-2">
                      <Label htmlFor={`grade-comment-${item.examItemId}`}>Nhận xét theo mục</Label>
                      <Input
                        id={`grade-comment-${item.examItemId}`}
                        value={comments[item.examItemId] ?? ''}
                        onChange={(event) =>
                          setComments((current) => ({ ...current, [item.examItemId]: event.target.value }))
                        }
                      />
                    </div>
                  </div>
                </>
              )}
            </fieldset>
          ))}
          <div className="flex flex-col gap-2">
            <Label htmlFor="grade-overall-comment">Nhận xét chung</Label>
            <Textarea
              id="grade-overall-comment"
              rows={2}
              value={overallComment}
              onChange={(event) => setOverallComment(event.target.value)}
            />
          </div>
          <div>
            <Button type="submit" disabled={gradeAttempt.isPending}>
              Lưu điểm chấm
            </Button>
          </div>
          {gradeAttempt.isError ? <ErrorNote error={gradeAttempt.error} /> : null}
          {gradeAttempt.isSuccess ? (
            <p className="text-sm text-muted-foreground">
              Đã chấm: {gradeAttempt.data.totalScore}/{gradeAttempt.data.maxScore}
              {gradeAttempt.data.provisional ? ' (điểm tạm)' : ''}.
            </p>
          ) : null}
        </form>
      </CardContent>
    </Card>
  );
}
