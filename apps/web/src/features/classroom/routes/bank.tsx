/**
 * Màn hình ngân hàng câu hỏi cá nhân của giáo viên (issue #102).
 *
 * Flow: danh sách ngân hàng cá nhân tải từ `apps/server`; giáo viên tạo ngân hàng mới và soạn câu ngay trong
 * màn hình này — đúng hai loại câu của đợt này (`single_choice` có các lựa chọn và đúng một đáp án đúng kèm lời
 * giải; `essay_text` có tiêu chí chấm). Câu vừa tạo được ghi vào cache và liệt kê tại chỗ để mở sang màn sửa/
 * phát hành; server chưa có endpoint danh sách câu nên các câu của phiên trước được mở theo mã ở trang chính.
 *
 * Bất biến hiển thị: form trắc nghiệm luôn giữ ít nhất hai lựa chọn và chỉ cho chọn một đáp án đúng trong số
 * các lựa chọn hiện có; việc kiểm tra nghiệp vụ cuối cùng vẫn do server quyết khi nhận dữ kiện.
 */
import { useState, type FormEvent } from 'react';
import { Link } from 'react-router';
import { Button, buttonVariants } from '@lms/ui/components/button';
import { Card, CardContent, CardHeader, CardTitle } from '@lms/ui/components/card';
import { Input } from '@lms/ui/components/input';
import { Label } from '@lms/ui/components/label';
import { NativeSelect } from '@lms/ui/components/native-select';
import { Textarea } from '@lms/ui/components/textarea';
import { PageShell } from '#components/page-shell';
import type { QuestionContentInput } from '../api/client';
import { EmptyNote, ErrorNote, LoadingNote, SessionGate } from '../components/states';
import { useClassroomSession } from '../hooks/use-session';
import { useBanks, useCreateBank, useCreateQuestion } from '../queries/hooks';
import type { QuestionView } from '../types/server';

/**
 * Màn hình ngân hàng: bọc cổng phiên quanh danh sách ngân hàng, form tạo ngân hàng và form soạn câu.
 *
 * @returns Phần tử của màn hình ngân hàng.
 */
export default function ClassroomBank() {
  const session = useClassroomSession();
  const [createdQuestions, setCreatedQuestions] = useState<QuestionView[]>([]);
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
            <BankListSection />
            <CreateQuestionSection
              onCreated={(question) =>
                setCreatedQuestions((current) => [question, ...current.filter((item) => item.id !== question.id)])
              }
            />
            {createdQuestions.length > 0 ? (
              <Card>
                <CardHeader>
                  <CardTitle>Câu vừa soạn trong phiên này</CardTitle>
                </CardHeader>
                <CardContent>
                  <ul className="flex flex-col gap-2">
                    {createdQuestions.map((question) => {
                      const firstVersion = question.versions[0];
                      return (
                        <li key={question.id} className="flex flex-wrap items-center justify-between gap-2">
                          <span className="font-medium">{firstVersion ? firstVersion.stem : question.id}</span>
                          <Link
                            className={buttonVariants({ variant: 'outline', size: 'sm' })}
                            to={`/classroom/questions/${question.id}`}
                          >
                            Sửa và phát hành
                          </Link>
                        </li>
                      );
                    })}
                  </ul>
                </CardContent>
              </Card>
            ) : null}
          </div>
        </PageShell>
      )}
    </SessionGate>
  );
}

/**
 * Khối danh sách ngân hàng cá nhân kèm form tạo ngân hàng mới.
 *
 * Side-effect: tạo ngân hàng gửi `POST /api/question-banks`; danh sách tự tải lại sau khi tạo.
 *
 * @returns Phần tử khối ngân hàng.
 */
function BankListSection() {
  const banksQuery = useBanks();
  const createBank = useCreateBank();
  const [name, setName] = useState('');
  const onSubmit = (event: FormEvent) => {
    event.preventDefault();
    createBank.mutate(name.trim(), { onSuccess: () => setName('') });
  };
  return (
    <Card>
      <CardHeader>
        <CardTitle>Ngân hàng câu hỏi cá nhân</CardTitle>
      </CardHeader>
      <CardContent className="flex flex-col gap-4">
        {banksQuery.isPending ? <LoadingNote label="Đang tải danh sách ngân hàng…" /> : null}
        {banksQuery.isError ? <ErrorNote error={banksQuery.error} onRetry={() => void banksQuery.refetch()} /> : null}
        {banksQuery.data && banksQuery.data.length === 0 ? (
          <EmptyNote>Bạn chưa có ngân hàng nào. Hãy tạo ngân hàng đầu tiên để bắt đầu soạn câu.</EmptyNote>
        ) : null}
        <ul className="flex flex-col gap-2">
          {(banksQuery.data ?? []).map((bank) => (
            <li key={bank.id} className="flex flex-col">
              <span className="font-medium">{bank.name}</span>
              <span className="text-sm text-muted-foreground">Mã ngân hàng: {bank.id}</span>
            </li>
          ))}
        </ul>
        <form className="flex flex-col gap-2" onSubmit={onSubmit}>
          <Label htmlFor="bank-name">Tên ngân hàng mới</Label>
          <div className="flex gap-2">
            <Input
              id="bank-name"
              value={name}
              onChange={(event) => setName(event.target.value)}
              placeholder="Ví dụ: Ngân hàng Toán cá nhân"
              required
            />
            <Button type="submit" disabled={createBank.isPending || name.trim() === ''}>
              Tạo ngân hàng
            </Button>
          </div>
          {createBank.isError ? <ErrorNote error={createBank.error} /> : null}
        </form>
      </CardContent>
    </Card>
  );
}

/**
 * Form soạn một câu mới vào một ngân hàng đã chọn. Đổi loại câu thì hiển thị đúng trường của loại đó: trắc
 * nghiệm có danh sách lựa chọn sửa được, một đáp án đúng và lời giải; tự luận có tiêu chí chấm.
 *
 * Side-effect: gửi `POST /api/questions` qua mutation; câu vừa tạo được đẩy lên danh sách của phiên qua
 * `onCreated` và ghi vào cache của câu.
 *
 * @param props - Hàm nhận câu vừa tạo để màn hình liệt kê tại chỗ.
 * @returns Phần tử form soạn câu.
 */
function CreateQuestionSection({ onCreated }: { onCreated: (question: QuestionView) => void }) {
  const banksQuery = useBanks();
  const createQuestion = useCreateQuestion();
  const [bankId, setBankId] = useState('');
  const [questionType, setQuestionType] = useState<'single_choice' | 'essay_text'>('single_choice');
  const [stem, setStem] = useState('');
  const [choices, setChoices] = useState<{ id: string; text: string }[]>([
    { id: 'a', text: '' },
    { id: 'b', text: '' },
  ]);
  const [correctChoiceId, setCorrectChoiceId] = useState('a');
  const [solution, setSolution] = useState('');
  const [rubric, setRubric] = useState('');
  const selectedBankId = bankId || (banksQuery.data?.[0]?.id ?? '');

  const onSubmit = (event: FormEvent) => {
    event.preventDefault();
    if (selectedBankId === '') return;
    const content: QuestionContentInput =
      questionType === 'single_choice'
        ? {
            questionType,
            stem: stem.trim(),
            choices: choices.map((choice) => ({ id: choice.id, text: choice.text.trim() })),
            correctChoiceId,
            solution: solution.trim() === '' ? null : solution.trim(),
            rubric: null,
          }
        : {
            questionType,
            stem: stem.trim(),
            choices: [],
            correctChoiceId: null,
            solution: null,
            rubric: rubric.trim() === '' ? null : rubric.trim(),
          };
    createQuestion.mutate({ bankId: selectedBankId, content }, { onSuccess: onCreated });
  };

  return (
    <Card>
      <CardHeader>
        <CardTitle>Soạn câu mới</CardTitle>
      </CardHeader>
      <CardContent>
        <form className="flex flex-col gap-4" onSubmit={onSubmit}>
          <div className="flex flex-col gap-2">
            <Label htmlFor="question-bank">Ngân hàng chứa câu</Label>
            <NativeSelect
              id="question-bank"
              value={selectedBankId}
              onChange={(event) => setBankId(event.target.value)}
            >
              {(banksQuery.data ?? []).map((bank) => (
                <option key={bank.id} value={bank.id}>
                  {bank.name}
                </option>
              ))}
            </NativeSelect>
          </div>
          <div className="flex flex-col gap-2">
            <Label htmlFor="question-type">Loại câu</Label>
            <NativeSelect
              id="question-type"
              value={questionType}
              onChange={(event) => setQuestionType(event.target.value as 'single_choice' | 'essay_text')}
            >
              <option value="single_choice">Trắc nghiệm một đáp án</option>
              <option value="essay_text">Tự luận</option>
            </NativeSelect>
          </div>
          <div className="flex flex-col gap-2">
            <Label htmlFor="question-stem">Thân câu hỏi</Label>
            <Textarea
              id="question-stem"
              value={stem}
              onChange={(event) => setStem(event.target.value)}
              rows={3}
              required
            />
          </div>
          {questionType === 'single_choice' ? (
            <fieldset className="flex flex-col gap-2">
              <legend className="text-sm font-medium">Các lựa chọn và đáp án đúng</legend>
              {choices.map((choice, index) => (
                <div key={choice.id} className="flex items-center gap-2">
                  <input
                    type="radio"
                    name="correct-choice"
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
              <div>
                <Button
                  type="button"
                  variant="outline"
                  size="sm"
                  onClick={() =>
                    setChoices((current) => [...current, { id: `c${current.length + 1}`, text: '' }])
                  }
                >
                  Thêm lựa chọn
                </Button>
              </div>
              <div className="flex flex-col gap-2">
                <Label htmlFor="question-solution">Lời giải</Label>
                <Textarea
                  id="question-solution"
                  value={solution}
                  onChange={(event) => setSolution(event.target.value)}
                  rows={2}
                />
              </div>
            </fieldset>
          ) : (
            <div className="flex flex-col gap-2">
              <Label htmlFor="question-rubric">Tiêu chí chấm</Label>
              <Textarea
                id="question-rubric"
                value={rubric}
                onChange={(event) => setRubric(event.target.value)}
                rows={3}
              />
            </div>
          )}
          <div>
            <Button type="submit" disabled={createQuestion.isPending || selectedBankId === '' || stem.trim() === ''}>
              Tạo câu
            </Button>
          </div>
          {createQuestion.isError ? <ErrorNote error={createQuestion.error} /> : null}
        </form>
      </CardContent>
    </Card>
  );
}
