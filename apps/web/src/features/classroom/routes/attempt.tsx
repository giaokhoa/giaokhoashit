/**
 * Màn hình làm bài và xem kết quả của học viên (issue #102).
 *
 * Flow: mở màn hình thì bắt đầu lượt làm cho lần giao trên đường dẫn — server trả đúng một lượt cho mỗi học
 * viên nên mở lại trang là tiếp tục đúng lượt đó. Các mục hiển thị từ hình chiếu của server (thân câu và các
 * lựa chọn, không có đáp án); học viên chọn/viết câu trả lời, lưu từng phần hoặc lưu toàn bộ, rồi nộp bài. Sau
 * khi nộp, màn hình chuyển sang khối kết quả: điểm chỉ xuất hiện khi hình chiếu kết quả của server mang dữ kiện
 * điểm, tức là sau khi giáo viên công bố.
 *
 * Bất biến hiển thị: không trường đáp án đúng, lời giải hay tiêu chí chấm nào được hiển thị ở màn hình này —
 * hình chiếu làm bài của server không chứa chúng, và khối kết quả chỉ đọc các trường điểm/nhận xét của hình
 * chiếu kết quả, không đọc thêm bất kỳ trường nào khác.
 */
import { useEffect, useState } from 'react';
import { Link, useParams } from 'react-router';
import { Button } from '@lms/ui/components/button';
import { Card, CardContent, CardHeader, CardTitle } from '@lms/ui/components/card';
import { Label } from '@lms/ui/components/label';
import { Textarea } from '@lms/ui/components/textarea';
import { PageShell } from '#components/page-shell';
import { EmptyNote, ErrorNote, LoadingNote, SessionGate } from '../components/states';
import { useClassroomSession } from '../hooks/use-session';
import { useAttemptResult, useSaveAnswers, useStartAttempt, useSubmitAttempt } from '../queries/hooks';
import type { AttemptView } from '../types/server';
import { formatDateTime } from '../utils/datetime';

/**
 * Màn hình làm bài: bọc cổng phiên quanh thân màn hình của lần giao trên đường dẫn.
 *
 * @returns Phần tử của màn hình làm bài.
 */
export default function ClassroomAttempt() {
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
              <AttemptBody assignmentId={assignmentId} />
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
 * Thân màn hình làm bài: khởi tạo lượt làm một lần khi mở màn hình, giữ lượt trong state cục bộ và cập nhật
 * theo đáp ứng của các thao tác lưu/nộp.
 *
 * Side-effect: gửi `POST /api/assignments/{id}/attempts` đúng một lần khi thân màn hình mount.
 *
 * @param props - Định danh lần giao.
 * @returns Phần tử thân màn hình làm bài.
 */
function AttemptBody({ assignmentId }: { assignmentId: string }) {
  const startAttempt = useStartAttempt();
  const [attempt, setAttempt] = useState<AttemptView | null>(null);
  useEffect(() => {
    startAttempt.mutate(assignmentId, { onSuccess: setAttempt });
    // Chỉ khởi tạo một lần cho mỗi lần giao; mutation object thay đổi theo render nên không đưa vào deps.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [assignmentId]);

  if (attempt) {
    return <AttemptWorkspace key={attempt.attemptId} attempt={attempt} onAttemptChange={setAttempt} />;
  }
  if (startAttempt.isError) {
    return <ErrorNote error={startAttempt.error} onRetry={() => startAttempt.mutate(assignmentId, { onSuccess: setAttempt })} />;
  }
  return <LoadingNote label="Đang mở bài làm…" />;
}

/**
 * Không gian làm bài của một lượt: danh sách mục kèm ô trả lời, nút lưu và nút nộp khi lượt còn đang làm; khi
 * lượt đã nộp thì chuyển sang chỉ đọc và hiển thị khối kết quả.
 *
 * Side-effect: lưu gửi `PUT /api/attempts/{id}/answers`, nộp gửi `POST /api/attempts/{id}/submit`; đáp ứng của
 * server thay thế state của lượt qua `onAttemptChange`.
 *
 * @param props - Lượt làm hiện tại và hàm nhận lượt mới sau mỗi thao tác.
 * @returns Phần tử không gian làm bài.
 */
function AttemptWorkspace({
  attempt,
  onAttemptChange,
}: {
  attempt: AttemptView;
  onAttemptChange: (attempt: AttemptView) => void;
}) {
  const saveAnswers = useSaveAnswers();
  const submitAttempt = useSubmitAttempt();
  const [answers, setAnswers] = useState<Record<string, unknown>>(() => {
    const initial: Record<string, unknown> = {};
    for (const saved of attempt.answers) {
      initial[saved.examItemId] = saved.answer;
    }
    return initial;
  });
  const [submitView, setSubmitView] = useState<{ status: string; submittedAt: string | null } | null>(null);
  const isWorking = attempt.status === 'IN_PROGRESS' || attempt.status === 'NOT_STARTED';
  const status = submitView?.status ?? attempt.status;

  const collectAnswers = () =>
    attempt.items
      .filter((item) => answers[item.examItemId] !== undefined)
      .map((item) => ({ examItemId: item.examItemId, answer: answers[item.examItemId] }));

  const onSave = () => {
    saveAnswers.mutate({ attemptId: attempt.attemptId, answers: collectAnswers() }, { onSuccess: onAttemptChange });
  };
  const onSubmit = () => {
    submitAttempt.mutate(attempt.attemptId, {
      onSuccess: (view) => setSubmitView({ status: view.status, submittedAt: view.submittedAt }),
    });
  };

  return (
    <div className="flex flex-col gap-4">
      <Card>
        <CardHeader>
          <CardTitle>Bài làm</CardTitle>
        </CardHeader>
        <CardContent className="flex flex-col gap-1">
          <p className="text-sm text-muted-foreground">
            Trạng thái: {status} · Mở: {formatDateTime(attempt.opensAt)} · Hạn: {formatDateTime(attempt.dueAt)}
            {submitView?.submittedAt ? ` · Đã nộp lúc ${formatDateTime(submitView.submittedAt)}` : ''}
            {attempt.submittedAt ? ` · Đã nộp lúc ${formatDateTime(attempt.submittedAt)}` : ''}
          </p>
        </CardContent>
      </Card>

      {attempt.items.map((item, index) => {
        const answer = answers[item.examItemId] as { choiceId?: string; text?: string } | undefined;
        return (
          <Card key={item.examItemId}>
            <CardHeader>
              <CardTitle>
                Câu {index + 1} ({item.points} điểm)
              </CardTitle>
            </CardHeader>
            <CardContent className="flex flex-col gap-3">
              <p className="whitespace-pre-wrap">{item.prompt}</p>
              {item.type === 'single_choice' ? (
                <fieldset className="flex flex-col gap-2">
                  <legend className="sr-only">Chọn một đáp án cho câu {index + 1}</legend>
                  {item.choices.map((choice) => (
                    <label key={choice.choiceId} className="flex items-center gap-2">
                      <input
                        type="radio"
                        name={`choice-${item.examItemId}`}
                        checked={answer?.choiceId === choice.choiceId}
                        disabled={!isWorking}
                        onChange={() =>
                          setAnswers((current) => ({ ...current, [item.examItemId]: { choiceId: choice.choiceId } }))
                        }
                      />
                      <span>{choice.text}</span>
                    </label>
                  ))}
                </fieldset>
              ) : (
                <div className="flex flex-col gap-2">
                  <Label htmlFor={`essay-${item.examItemId}`}>Bài viết của bạn</Label>
                  <Textarea
                    id={`essay-${item.examItemId}`}
                    rows={5}
                    value={answer?.text ?? ''}
                    disabled={!isWorking}
                    onChange={(event) =>
                      setAnswers((current) => ({ ...current, [item.examItemId]: { text: event.target.value } }))
                    }
                  />
                </div>
              )}
            </CardContent>
          </Card>
        );
      })}

      {isWorking ? (
        <div className="flex flex-col gap-2">
          <div className="flex flex-wrap gap-2">
            <Button type="button" variant="outline" disabled={saveAnswers.isPending} onClick={onSave}>
              Lưu bài
            </Button>
            <Button type="button" disabled={submitAttempt.isPending} onClick={onSubmit}>
              Nộp bài
            </Button>
          </div>
          {saveAnswers.isError ? <ErrorNote error={saveAnswers.error} /> : null}
          {saveAnswers.isSuccess ? <p className="text-sm text-muted-foreground">Đã lưu câu trả lời.</p> : null}
          {submitAttempt.isError ? <ErrorNote error={submitAttempt.error} /> : null}
        </div>
      ) : (
        <ResultPanel attemptId={attempt.attemptId} />
      )}
    </div>
  );
}

/**
 * Khối kết quả của học viên: chỉ hiển thị điểm khi hình chiếu kết quả của server đã mang dữ kiện điểm (sau
 * công bố); trước công bố hiển thị đúng một dòng trạng thái chờ. Khối này không đọc và không hiển thị bất kỳ
 * trường đáp án nào.
 *
 * @param props - Định danh lượt làm.
 * @returns Phần tử khối kết quả.
 */
function ResultPanel({ attemptId }: { attemptId: string }) {
  const resultQuery = useAttemptResult(attemptId);
  if (resultQuery.isPending) return <LoadingNote label="Đang tải kết quả…" />;
  if (resultQuery.isError) {
    return <ErrorNote error={resultQuery.error} onRetry={() => void resultQuery.refetch()} />;
  }
  const result = resultQuery.data.result;
  if (!result) {
    return (
      <Card>
        <CardContent>
          <p className="text-sm text-muted-foreground">Bài đã nộp. Kết quả sẽ hiển thị sau khi giáo viên công bố.</p>
        </CardContent>
      </Card>
    );
  }
  return (
    <Card>
      <CardHeader>
        <CardTitle>Kết quả của bạn</CardTitle>
      </CardHeader>
      <CardContent className="flex flex-col gap-3">
        <ul className="flex flex-col gap-2">
          {result.items.map((item, index) => (
            <li key={item.examItemId} className="flex flex-col">
              <span className="font-medium">
                Câu {index + 1}: {item.score ?? '—'}/{item.maxScore} điểm
              </span>
              {item.comment ? <span className="text-sm text-muted-foreground">Nhận xét: {item.comment}</span> : null}
            </li>
          ))}
        </ul>
        {result.overallComment ? (
          <p className="text-sm">
            <span className="font-medium">Nhận xét chung:</span> {result.overallComment}
          </p>
        ) : null}
        <p className="font-medium">
          Tổng điểm: {result.totalScore}/{result.maxScore}
        </p>
      </CardContent>
    </Card>
  );
}
