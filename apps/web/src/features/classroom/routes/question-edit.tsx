/**
 * Màn hình sửa và phát hành một câu hỏi của feature classroom (issue #102).
 *
 * Flow: câu tải theo mã trên đường dẫn cùng toàn bộ các phiên bản; giáo viên chọn một phiên bản để xem. Phiên
 * bản chưa khoá (`lockedAt` rỗng) là bản nháp: sửa được nội dung kèm `revision` hiện tại để server chống ghi đè
 * lạc hậu, và phát hành được. Phiên bản đã khoá chỉ đọc — muốn sửa tiếp thì mở bản nháp mới từ chính phiên bản
 * đó. Cuối màn hình là thao tác đưa một phiên bản vào một ngân hàng đích.
 *
 * Bất biến hiển thị: các trường đáp án (đáp án đúng, lời giải, tiêu chí chấm) chỉ hiển thị khi server thật sự
 * trả chúng trong phiên bản; màn hình không tự suy các trường bị lược.
 */
import { useState, type FormEvent } from 'react';
import { Link, useParams } from 'react-router';
import { Badge } from '@lms/ui/components/badge';
import { Button } from '@lms/ui/components/button';
import { Card, CardContent, CardHeader, CardTitle } from '@lms/ui/components/card';
import { Input } from '@lms/ui/components/input';
import { Label } from '@lms/ui/components/label';
import { NativeSelect } from '@lms/ui/components/native-select';
import { Textarea } from '@lms/ui/components/textarea';
import { PageShell } from '#components/page-shell';
import type { QuestionContentInput } from '../api/client';
import { EmptyNote, ErrorNote, LoadingNote, SessionGate } from '../components/states';
import { useClassroomSession } from '../hooks/use-session';
import {
  useAddBankEntry,
  useBanks,
  useCreateQuestionVersion,
  usePublishQuestionVersion,
  useQuestion,
  useUpdateQuestionVersion,
} from '../queries/hooks';
import type { QuestionVersionView } from '../types/server';
import { formatDateTime } from '../utils/datetime';

/**
 * Màn hình sửa câu: bọc cổng phiên quanh thân màn hình của câu được chọn trên đường dẫn.
 *
 * @returns Phần tử của màn hình sửa câu.
 */
export default function ClassroomQuestionEdit() {
  const session = useClassroomSession();
  const { questionId } = useParams();
  return (
    <SessionGate session={session}>
      {() => (
        <PageShell width="wide">
          <div className="flex flex-col gap-4">
            <p>
              <Link className="text-sm underline-offset-4 hover:underline" to="/classroom/bank">
                ← Về ngân hàng câu hỏi
              </Link>
            </p>
            {questionId ? <QuestionEditBody questionId={questionId} /> : <EmptyNote>Đường dẫn thiếu mã câu hỏi.</EmptyNote>}
          </div>
        </PageShell>
      )}
    </SessionGate>
  );
}

/**
 * Thân màn hình sửa câu: danh sách phiên bản để chọn, khung nội dung của phiên bản đang chọn (form sửa khi là
 * bản nháp, chỉ đọc khi đã khoá) và form đưa phiên bản vào ngân hàng đích.
 *
 * @param props - Định danh câu hỏi.
 * @returns Phần tử thân màn hình sửa câu.
 */
function QuestionEditBody({ questionId }: { questionId: string }) {
  const questionQuery = useQuestion(questionId);
  const [selectedVersionId, setSelectedVersionId] = useState<string | null>(null);
  if (questionQuery.isPending) return <LoadingNote label="Đang tải câu hỏi…" />;
  if (questionQuery.isError) {
    return <ErrorNote error={questionQuery.error} onRetry={() => void questionQuery.refetch()} />;
  }
  const question = questionQuery.data;
  const versions = [...question.versions].sort((a, b) => b.versionNumber - a.versionNumber);
  const selected = versions.find((version) => version.versionId === selectedVersionId) ?? versions[0];
  if (!selected) return <EmptyNote>Câu hỏi chưa có phiên bản nào.</EmptyNote>;
  return (
    <>
      <Card>
        <CardHeader>
          <CardTitle>Các phiên bản của câu</CardTitle>
        </CardHeader>
        <CardContent className="flex flex-col gap-2">
          {versions.map((version) => (
            <button
              key={version.versionId}
              type="button"
              className="flex flex-wrap items-center justify-between gap-2 text-left"
              onClick={() => setSelectedVersionId(version.versionId)}
            >
              <span className="font-medium">
                Phiên bản {version.versionNumber}
                {version.versionId === selected.versionId ? ' (đang xem)' : ''}
              </span>
              <span className="flex items-center gap-2">
                <Badge variant="secondary">{version.status}</Badge>
                <span className="text-sm text-muted-foreground">
                  {version.lockedAt ? `Đã khoá lúc ${formatDateTime(version.lockedAt)}` : 'Bản nháp'}
                </span>
              </span>
            </button>
          ))}
        </CardContent>
      </Card>
      <VersionPanel key={selected.versionId} questionId={questionId} version={selected} />
      <AddToBankPanel questionId={questionId} version={selected} />
    </>
  );
}

/**
 * Khung một phiên bản câu: form sửa khi phiên bản chưa khoá; chỉ đọc kèm nút mở bản nháp mới khi đã khoá.
 *
 * @param props - Định danh câu và phiên bản đang xem.
 * @returns Phần tử khung phiên bản.
 */
function VersionPanel({ questionId, version }: { questionId: string; version: QuestionVersionView }) {
  const isLocked = version.lockedAt !== null;
  return (
    <Card>
      <CardHeader>
        <CardTitle>
          Phiên bản {version.versionNumber} — {version.questionType === 'single_choice' ? 'Trắc nghiệm' : 'Tự luận'}
        </CardTitle>
      </CardHeader>
      <CardContent className="flex flex-col gap-4">
        {version.contentHash ? (
          <p className="text-sm text-muted-foreground">Dấu đóng băng (contentHash): {version.contentHash}</p>
        ) : null}
        {isLocked ? <LockedVersionView version={version} /> : null}
        {!isLocked ? <DraftVersionForm questionId={questionId} version={version} /> : null}
        <VersionActions questionId={questionId} version={version} />
      </CardContent>
    </Card>
  );
}

/**
 * Nội dung chỉ đọc của một phiên bản đã khoá: thân câu, các lựa chọn, và các trường đáp án chỉ khi server trả.
 *
 * @param props - Phiên bản đã khoá.
 * @returns Phần tử nội dung chỉ đọc.
 */
function LockedVersionView({ version }: { version: QuestionVersionView }) {
  return (
    <div className="flex flex-col gap-2">
      <p className="whitespace-pre-wrap">{version.stem}</p>
      {version.choices.length > 0 ? (
        <ul className="list-inside list-disc">
          {version.choices.map((choice) => (
            <li key={choice.id}>
              {choice.text}
              {version.correctChoiceId === choice.id ? ' (đáp án đúng)' : ''}
            </li>
          ))}
        </ul>
      ) : null}
      {version.solution ? (
        <p className="text-sm">
          <span className="font-medium">Lời giải:</span> {version.solution}
        </p>
      ) : null}
      {version.rubric ? (
        <p className="text-sm">
          <span className="font-medium">Tiêu chí chấm:</span> {version.rubric}
        </p>
      ) : null}
    </div>
  );
}

/**
 * Form sửa nội dung một phiên bản nháp. Gửi kèm `revision` hiện tại; server trả 409 khi bản nháp đã bị sửa ở
 * nơi khác và thông điệp hiển thị yêu cầu tải lại thay vì ghi đè.
 *
 * Side-effect: gửi `PUT /api/questions/{id}/versions/{versionId}` qua mutation; thành công thì câu tải lại.
 *
 * @param props - Định danh câu và phiên bản nháp (dữ kiện khởi tạo của form).
 * @returns Phần tử form sửa phiên bản nháp.
 */
function DraftVersionForm({ questionId, version }: { questionId: string; version: QuestionVersionView }) {
  const updateVersion = useUpdateQuestionVersion();
  const [stem, setStem] = useState(version.stem);
  const [choices, setChoices] = useState(version.choices.map((choice) => ({ ...choice })));
  const [correctChoiceId, setCorrectChoiceId] = useState(version.correctChoiceId ?? version.choices[0]?.id ?? '');
  const [solution, setSolution] = useState(version.solution ?? '');
  const [rubric, setRubric] = useState(version.rubric ?? '');
  const isChoice = version.questionType === 'single_choice';
  const onSubmit = (event: FormEvent) => {
    event.preventDefault();
    const content: QuestionContentInput = isChoice
      ? {
          questionType: 'single_choice',
          stem: stem.trim(),
          choices: choices.map((choice) => ({ id: choice.id, text: choice.text.trim() })),
          correctChoiceId,
          solution: solution.trim() === '' ? null : solution.trim(),
          rubric: null,
        }
      : {
          questionType: 'essay_text',
          stem: stem.trim(),
          choices: [],
          correctChoiceId: null,
          solution: null,
          rubric: rubric.trim() === '' ? null : rubric.trim(),
        };
    updateVersion.mutate({ questionId, versionId: version.versionId, input: { ...content, revision: version.revision } });
  };
  return (
    <form className="flex flex-col gap-4" onSubmit={onSubmit}>
      <div className="flex flex-col gap-2">
        <Label htmlFor="version-stem">Thân câu hỏi</Label>
        <Textarea id="version-stem" value={stem} onChange={(event) => setStem(event.target.value)} rows={3} required />
      </div>
      {isChoice ? (
        <>
          <fieldset className="flex flex-col gap-2">
            <legend className="text-sm font-medium">Các lựa chọn và đáp án đúng</legend>
            {choices.map((choice, index) => (
              <div key={choice.id} className="flex items-center gap-2">
                <input
                  type="radio"
                  name="edit-correct-choice"
                  aria-label={`Đáp án đúng là lựa chọn ${index + 1}`}
                  checked={correctChoiceId === choice.id}
                  onChange={() => setCorrectChoiceId(choice.id)}
                />
                <Input
                  aria-label={`Lựa chọn ${index + 1}`}
                  value={choice.text}
                  onChange={(event) =>
                    setChoices((current) =>
                      current.map((item) => (item.id === choice.id ? { ...item, text: event.target.value } : item)),
                    )
                  }
                  required
                />
              </div>
            ))}
          </fieldset>
          <div className="flex flex-col gap-2">
            <Label htmlFor="version-solution">Lời giải</Label>
            <Textarea
              id="version-solution"
              value={solution}
              onChange={(event) => setSolution(event.target.value)}
              rows={2}
            />
          </div>
        </>
      ) : (
        <div className="flex flex-col gap-2">
          <Label htmlFor="version-rubric">Tiêu chí chấm</Label>
          <Textarea id="version-rubric" value={rubric} onChange={(event) => setRubric(event.target.value)} rows={3} />
        </div>
      )}
      <div>
        <Button type="submit" disabled={updateVersion.isPending || stem.trim() === ''}>
          Lưu bản nháp
        </Button>
      </div>
      {updateVersion.isError ? <ErrorNote error={updateVersion.error} /> : null}
      {updateVersion.isSuccess ? <p className="text-sm text-muted-foreground">Đã lưu bản nháp.</p> : null}
    </form>
  );
}

/**
 * Hai hành động vòng đời của phiên bản đang xem: phát hành (chỉ khi chưa khoá) và mở bản nháp mới từ phiên bản
 * này.
 *
 * Side-effect: gọi các mutation tương ứng; thành công thì câu tải lại với danh sách phiên bản mới.
 *
 * @param props - Định danh câu và phiên bản đang xem.
 * @returns Phần tử hàng hành động của phiên bản.
 */
function VersionActions({ questionId, version }: { questionId: string; version: QuestionVersionView }) {
  const publishVersion = usePublishQuestionVersion();
  const createVersion = useCreateQuestionVersion();
  return (
    <div className="flex flex-col gap-2">
      <div className="flex flex-wrap gap-2">
        {version.lockedAt === null ? (
          <Button
            type="button"
            disabled={publishVersion.isPending}
            onClick={() => publishVersion.mutate({ questionId, versionId: version.versionId })}
          >
            Phát hành phiên bản này
          </Button>
        ) : null}
        <Button
          type="button"
          variant="outline"
          disabled={createVersion.isPending}
          onClick={() => createVersion.mutate({ questionId, fromVersionId: version.versionId })}
        >
          Mở bản nháp mới từ phiên bản này
        </Button>
      </div>
      {publishVersion.isError ? <ErrorNote error={publishVersion.error} /> : null}
      {createVersion.isError ? <ErrorNote error={createVersion.error} /> : null}
    </div>
  );
}

/**
 * Form đưa phiên bản đang xem vào một ngân hàng đích.
 *
 * Side-effect: gửi `POST /api/question-banks/{bankId}/entries` qua mutation.
 *
 * @param props - Định danh câu và phiên bản đang xem.
 * @returns Phần tử form đưa vào ngân hàng.
 */
function AddToBankPanel({ questionId, version }: { questionId: string; version: QuestionVersionView }) {
  const banksQuery = useBanks();
  const addEntry = useAddBankEntry();
  const [bankId, setBankId] = useState('');
  const selectedBankId = bankId || (banksQuery.data?.[0]?.id ?? '');
  const onSubmit = (event: FormEvent) => {
    event.preventDefault();
    if (selectedBankId === '') return;
    addEntry.mutate({ bankId: selectedBankId, questionVersionId: version.versionId });
  };
  return (
    <Card>
      <CardHeader>
        <CardTitle>Đưa phiên bản {version.versionNumber} vào ngân hàng</CardTitle>
      </CardHeader>
      <CardContent>
        <form className="flex flex-col gap-2" onSubmit={onSubmit}>
          <Label htmlFor="entry-bank">Ngân hàng đích</Label>
          <div className="flex gap-2">
            <NativeSelect id="entry-bank" value={selectedBankId} onChange={(event) => setBankId(event.target.value)}>
              {(banksQuery.data ?? []).map((bank) => (
                <option key={bank.id} value={bank.id}>
                  {bank.name}
                </option>
              ))}
            </NativeSelect>
            <Button type="submit" disabled={addEntry.isPending || selectedBankId === ''}>
              Đưa vào ngân hàng
            </Button>
          </div>
          <p className="text-sm text-muted-foreground">Câu thuộc ngân hàng: {questionId}</p>
          {addEntry.isError ? <ErrorNote error={addEntry.error} /> : null}
          {addEntry.isSuccess ? <p className="text-sm text-muted-foreground">Đã đưa phiên bản vào ngân hàng.</p> : null}
        </form>
      </CardContent>
    </Card>
  );
}
