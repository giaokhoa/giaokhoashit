/**
 * Hook React Query của feature classroom (issue #102): bọc các hàm gọi API thuần ở tầng `api/` và giữ cache
 * nhất quán sau mỗi mutation.
 *
 * Nguyên tắc vô hiệu hoá: mutation đổi dữ kiện của họ nào thì vô hiệu hoá đúng khoá của họ đó (định danh chi
 * tiết khi có), để các màn hình đang mở tự tải lại bản mới nhất từ server thay vì tin dữ kiện cũ — khớp với xử
 * lý 409 của hợp đồng (không tự ghi đè, luôn đọc lại bản server giữ). Các thao tác trên một lượt làm (lưu, nộp,
 * chấm) ghi thẳng đáp ứng vào cache của lượt khi server trả đủ hình chiếu.
 */
import { useMutation, useQuery, useQueryClient, type UseMutationResult, type UseQueryResult } from '@tanstack/react-query';
import {
  addBankEntry,
  addMember,
  completeAttachmentSession,
  createAssignment,
  createBank,
  createClass,
  createCourse,
  createExam,
  createExamVersion,
  createQuestion,
  createQuestionVersion,
  fetchAssignments,
  fetchAttemptResult,
  fetchAttachments,
  fetchBanks,
  fetchClasses,
  fetchCourses,
  fetchExam,
  fetchMe,
  fetchMembers,
  fetchQuestion,
  fetchSubmissions,
  fetchTeacherAttempt,
  gradeAttempt,
  publishExamVersion,
  publishQuestionVersion,
  publishResults,
  removeBankEntry,
  removeMember,
  saveAnswers,
  setClassTeacher,
  startAttempt,
  submitAttempt,
  updateExamVersion,
  updateQuestionVersion,
  type QuestionContentInput,
} from '../api/client';
import type {
  AssignmentView,
  AttachmentLinkView,
  AttemptView,
  BankEntryView,
  BankView,
  CourseView,
  ExamView,
  GradeResultView,
  MemberView,
  MeView,
  PublicationView,
  QuestionView,
  SubmissionRowView,
  SubmitView,
  TeacherAttemptView,
  TeachingClassView,
} from '../types/server';
import { classroomKeys } from './keys';

/** @returns Truy vấn danh tính của người gọi. */
export function useMe(): UseQueryResult<MeView> {
  return useQuery({ queryKey: classroomKeys.me, queryFn: fetchMe });
}

/** @returns Truy vấn danh sách khóa của trung tâm. */
export function useCourses(): UseQueryResult<CourseView[]> {
  return useQuery({ queryKey: classroomKeys.courses, queryFn: fetchCourses });
}

/** @returns Truy vấn danh sách lớp của trung tâm. */
export function useClasses(): UseQueryResult<TeachingClassView[]> {
  return useQuery({ queryKey: classroomKeys.classes, queryFn: fetchClasses });
}

/**
 * @param classId - Định danh lớp.
 * @returns Truy vấn danh sách thành viên đang hiệu lực của lớp.
 */
export function useMembers(classId: string): UseQueryResult<MemberView[]> {
  return useQuery({ queryKey: classroomKeys.members(classId), queryFn: () => fetchMembers(classId) });
}

/** @returns Truy vấn danh sách ngân hàng cá nhân của người gọi. */
export function useBanks(): UseQueryResult<BankView[]> {
  return useQuery({ queryKey: classroomKeys.banks, queryFn: fetchBanks });
}

/**
 * @param questionId - Định danh câu hỏi.
 * @returns Truy vấn một câu hỏi cùng các phiên bản của nó.
 */
export function useQuestion(questionId: string): UseQueryResult<QuestionView> {
  return useQuery({ queryKey: classroomKeys.question(questionId), queryFn: () => fetchQuestion(questionId) });
}

/**
 * @param examId - Định danh đề.
 * @returns Truy vấn một đề cùng phiên bản hiện hành của nó.
 */
export function useExam(examId: string): UseQueryResult<ExamView> {
  return useQuery({ queryKey: classroomKeys.exam(examId), queryFn: () => fetchExam(examId) });
}

/** @returns Truy vấn danh sách lần giao nhìn thấy theo vai của người gọi. */
export function useAssignments(): UseQueryResult<AssignmentView[]> {
  return useQuery({ queryKey: classroomKeys.assignments, queryFn: fetchAssignments });
}

/**
 * @param assignmentId - Định danh lần giao.
 * @returns Truy vấn bảng bài nộp của lần giao.
 */
export function useSubmissions(assignmentId: string): UseQueryResult<SubmissionRowView[]> {
  return useQuery({
    queryKey: classroomKeys.submissions(assignmentId),
    queryFn: () => fetchSubmissions(assignmentId),
  });
}

/**
 * @param attemptId - Định danh lượt làm.
 * @returns Truy vấn một lượt làm của học viên để đọc phần kết quả (trường `result` có dữ kiện điểm chỉ sau
 * công bố).
 */
export function useAttemptResult(attemptId: string): UseQueryResult<AttemptView> {
  return useQuery({
    queryKey: classroomKeys.attemptResult(attemptId),
    queryFn: () => fetchAttemptResult(attemptId),
  });
}

/**
 * @param attemptId - Định danh lượt làm.
 * @param enabled - Chỉ bật khi màn chấm thật sự cần hình chiếu này.
 * @returns Truy vấn hình chiếu chấm của lượt cho giáo viên phụ trách.
 */
export function useTeacherAttempt(attemptId: string, enabled: boolean): UseQueryResult<TeacherAttemptView> {
  return useQuery({
    queryKey: classroomKeys.teacherAttempt(attemptId),
    queryFn: () => fetchTeacherAttempt(attemptId),
    enabled,
  });
}

/** @returns Mutation tạo khóa; xong thì vô hiệu hoá danh sách khóa. */
export function useCreateCourse(): UseMutationResult<CourseView, Error, string> {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: createCourse,
    onSuccess: () => void queryClient.invalidateQueries({ queryKey: classroomKeys.courses }),
  });
}

/** @returns Mutation tạo lớp; xong thì vô hiệu hoá danh sách lớp. */
export function useCreateClass(): UseMutationResult<TeachingClassView, Error, { name: string; courseId?: string | null }> {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: createClass,
    onSuccess: () => void queryClient.invalidateQueries({ queryKey: classroomKeys.classes }),
  });
}

/** @returns Mutation đặt giáo viên phụ trách lớp; xong thì vô hiệu hoá danh sách lớp. */
export function useSetClassTeacher(): UseMutationResult<TeachingClassView, Error, { classId: string; teacherSub: string }> {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ classId, teacherSub }) => setClassTeacher(classId, teacherSub),
    onSuccess: () => void queryClient.invalidateQueries({ queryKey: classroomKeys.classes }),
  });
}

/** @returns Mutation ghi danh học viên; xong thì vô hiệu hoá danh sách thành viên của lớp. */
export function useAddMember(): UseMutationResult<MemberView, Error, { classId: string; studentSub: string }> {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ classId, studentSub }) => addMember(classId, studentSub),
    onSuccess: (_data, { classId }) => void queryClient.invalidateQueries({ queryKey: classroomKeys.members(classId) }),
  });
}

/** @returns Mutation gỡ ghi danh; xong thì vô hiệu hoá danh sách thành viên của lớp. */
export function useRemoveMember(): UseMutationResult<void, Error, { classId: string; studentSub: string }> {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ classId, studentSub }) => removeMember(classId, studentSub),
    onSuccess: (_data, { classId }) => void queryClient.invalidateQueries({ queryKey: classroomKeys.members(classId) }),
  });
}

/** @returns Mutation tạo ngân hàng cá nhân; xong thì vô hiệu hoá danh sách ngân hàng. */
export function useCreateBank(): UseMutationResult<BankView, Error, string> {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: createBank,
    onSuccess: () => void queryClient.invalidateQueries({ queryKey: classroomKeys.banks }),
  });
}

/** @returns Mutation đưa một phiên bản câu vào ngân hàng đích. */
export function useAddBankEntry(): UseMutationResult<BankEntryView, Error, { bankId: string; questionVersionId: string }> {
  return useMutation({
    mutationFn: ({ bankId, questionVersionId }) => addBankEntry(bankId, questionVersionId),
  });
}

/** @returns Mutation gỡ một mục khỏi ngân hàng. */
export function useRemoveBankEntry(): UseMutationResult<void, Error, { bankId: string; entryId: string }> {
  return useMutation({
    mutationFn: ({ bankId, entryId }) => removeBankEntry(bankId, entryId),
  });
}

/** @returns Mutation tạo câu hỏi trong ngân hàng; đáp ứng được ghi vào cache của câu để màn sửa đọc ngay. */
export function useCreateQuestion(): UseMutationResult<QuestionView, Error, { bankId: string; content: QuestionContentInput }> {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ bankId, content }) => createQuestion(bankId, content),
    onSuccess: (question) => queryClient.setQueryData(classroomKeys.question(question.id), question),
  });
}

/** @returns Mutation sửa một phiên bản nháp của câu; xong thì vô hiệu hoá câu để đọc lại đủ các phiên bản. */
export function useUpdateQuestionVersion(): UseMutationResult<
  QuestionView['versions'][number],
  Error,
  { questionId: string; versionId: string; input: QuestionContentInput & { revision: number } }
> {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ questionId, versionId, input }) => updateQuestionVersion(questionId, versionId, input),
    onSuccess: (_data, { questionId }) => void queryClient.invalidateQueries({ queryKey: classroomKeys.question(questionId) }),
  });
}

/** @returns Mutation mở phiên bản nháp mới của câu; xong thì vô hiệu hoá câu. */
export function useCreateQuestionVersion(): UseMutationResult<
  QuestionView['versions'][number],
  Error,
  { questionId: string; fromVersionId: string }
> {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ questionId, fromVersionId }) => createQuestionVersion(questionId, fromVersionId),
    onSuccess: (_data, { questionId }) => void queryClient.invalidateQueries({ queryKey: classroomKeys.question(questionId) }),
  });
}

/** @returns Mutation phát hành một phiên bản câu; xong thì vô hiệu hoá câu. */
export function usePublishQuestionVersion(): UseMutationResult<
  QuestionView['versions'][number],
  Error,
  { questionId: string; versionId: string }
> {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ questionId, versionId }) => publishQuestionVersion(questionId, versionId),
    onSuccess: (_data, { questionId }) => void queryClient.invalidateQueries({ queryKey: classroomKeys.question(questionId) }),
  });
}

/**
 * @param questionId - Định danh câu hỏi.
 * @param versionId - Định danh phiên bản.
 * @returns Query danh sách tệp đính kèm của phiên bản câu.
 */
export function useAttachments(questionId: string, versionId: string): UseQueryResult<AttachmentLinkView[]> {
  return useQuery({
    queryKey: classroomKeys.attachments(questionId, versionId),
    queryFn: () => fetchAttachments(questionId, versionId),
  });
}

/** @returns Mutation hoàn tất một phiên tải lên tệp đính kèm; xong thì vô hiệu hoá danh sách tệp của phiên bản. */
export function useCompleteAttachmentSession(): UseMutationResult<
  AttachmentLinkView,
  Error,
  { questionId: string; versionId: string; sessionId: string }
> {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ questionId, versionId, sessionId }) =>
      completeAttachmentSession(questionId, versionId, sessionId),
    onSuccess: (_data, { questionId, versionId }) =>
      void queryClient.invalidateQueries({ queryKey: classroomKeys.attachments(questionId, versionId) }),
  });
}

/** @returns Mutation tạo đề; đáp ứng được ghi vào cache của đề để màn soạn đọc ngay. */
export function useCreateExam(): UseMutationResult<ExamView, Error, string> {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: createExam,
    onSuccess: (exam) => queryClient.setQueryData(classroomKeys.exam(exam.id), exam),
  });
}

/** @returns Mutation ghi nội dung phiên bản đề nháp; đáp ứng được ghi vào cache của đề. */
export function useUpdateExamVersion(): UseMutationResult<
  ExamView,
  Error,
  { examId: string; versionId: string; input: { title: string; lockVersion: number; items: { questionVersionId: string; score: number }[] } }
> {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ examId, versionId, input }) => updateExamVersion(examId, versionId, input),
    onSuccess: (exam) => queryClient.setQueryData(classroomKeys.exam(exam.id), exam),
  });
}

/** @returns Mutation mở phiên bản nháp mới của đề; đáp ứng được ghi vào cache của đề. */
export function useCreateExamVersion(): UseMutationResult<ExamView, Error, { examId: string; sourceVersionId: string }> {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ examId, sourceVersionId }) => createExamVersion(examId, sourceVersionId),
    onSuccess: (exam) => queryClient.setQueryData(classroomKeys.exam(exam.id), exam),
  });
}

/** @returns Mutation phát hành một phiên bản đề; đáp ứng được ghi vào cache của đề. */
export function usePublishExamVersion(): UseMutationResult<ExamView, Error, { examId: string; versionId: string }> {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ examId, versionId }) => publishExamVersion(examId, versionId),
    onSuccess: (exam) => queryClient.setQueryData(classroomKeys.exam(exam.id), exam),
  });
}

/** @returns Mutation tạo lần giao; xong thì vô hiệu hoá danh sách lần giao. */
export function useCreateAssignment(): UseMutationResult<
  AssignmentView,
  Error,
  { examVersionId: string; classId: string; opensAt: string; dueAt: string }
> {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: createAssignment,
    onSuccess: () => void queryClient.invalidateQueries({ queryKey: classroomKeys.assignments }),
  });
}

/** @returns Mutation bắt đầu/tiếp tục lượt làm cho một lần giao (server trả đúng một lượt cho mỗi học viên). */
export function useStartAttempt(): UseMutationResult<AttemptView, Error, string> {
  return useMutation({ mutationFn: startAttempt });
}

/** @returns Mutation lưu câu trả lời của một lượt làm. */
export function useSaveAnswers(): UseMutationResult<AttemptView, Error, { attemptId: string; answers: { examItemId: string; answer: unknown }[] }> {
  return useMutation({
    mutationFn: ({ attemptId, answers }) => saveAnswers(attemptId, answers),
  });
}

/** @returns Mutation nộp một lượt làm; xong thì vô hiệu hoá hình chiếu kết quả của lượt để đọc lại khi công bố. */
export function useSubmitAttempt(): UseMutationResult<SubmitView, Error, string> {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: submitAttempt,
    onSuccess: (_data, attemptId) => void queryClient.invalidateQueries({ queryKey: classroomKeys.attemptResult(attemptId) }),
  });
}

/** @returns Mutation chấm một lượt làm; xong thì vô hiệu hoá hình chiếu chấm và bảng bài nộp của lần giao. */
export function useGradeAttempt(): UseMutationResult<
  GradeResultView,
  Error,
  { attemptId: string; assignmentId: string; input: { items: { examItemId: string; score: number; comment: string | null }[]; comment: string | null } }
> {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ attemptId, input }) => gradeAttempt(attemptId, input),
    onSuccess: (_data, { attemptId, assignmentId }) => {
      void queryClient.invalidateQueries({ queryKey: classroomKeys.teacherAttempt(attemptId) });
      void queryClient.invalidateQueries({ queryKey: classroomKeys.submissions(assignmentId) });
    },
  });
}

/** @returns Mutation công bố kết quả theo lần giao; xong thì vô hiệu hoá bảng bài nộp của lần giao. */
export function usePublishResults(): UseMutationResult<PublicationView, Error, string> {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: publishResults,
    onSuccess: (_data, assignmentId) => void queryClient.invalidateQueries({ queryKey: classroomKeys.submissions(assignmentId) }),
  });
}
