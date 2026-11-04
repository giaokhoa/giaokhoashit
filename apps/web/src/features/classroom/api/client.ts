/**
 * Tầng gọi API thuần của feature classroom tới `apps/server` (issue #102).
 *
 * Mọi hàm ở đây đi qua một lõi `request` duy nhất: làm mới token bằng `updateToken(30)` trên instance adapter
 * dùng chung (`#lib/kc-adapter`, cùng instance mà feature auth khởi tạo ở điểm vào), đính
 * `Authorization: Bearer`, gọi đường tương đối để tầng web phía server định tuyến sang `apps/server` và gắn
 * header ngữ cảnh trang từ hostname — trình duyệt không bao giờ tự đặt `X-LMS-Organization-Alias`. Đáp ứng lỗi
 * được chuẩn hoá thành `ClassroomApiError` mang status + mã lỗi của server để tầng hiển thị ánh xạ theo hợp
 * đồng. File này không phụ thuộc React; các hook React Query ở tầng `queries/` bọc các hàm này.
 */
import { getKeycloakAdapter } from '#lib/kc-adapter';
import type {
  AssignmentView,
  AttachmentLinkView,
  AttachmentPosition,
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
  UploadSessionView,
} from '../types/server';
import { ClassroomApiError } from './errors';

/**
 * Lõi gọi `apps/server` cho mọi endpoint của feature.
 *
 * Flow: kiểm tra adapter đã xác thực (chưa thì ném 401 để tầng hiển thị dẫn về đăng nhập) → `updateToken(30)`
 * làm mới token khi sắp hết hạn → fetch đường tương đối kèm Bearer → đáp ứng lỗi thì đọc thân `{code, message}`
 * thành `ClassroomApiError`; đáp ứng 204 trả `undefined`; còn lại parse JSON theo kiểu đích.
 *
 * @param path - Đường tương đối bắt đầu bằng `/api/`, khớp các họ đường proxy đã mở cho `apps/server`.
 * @param init - Method và thân JSON (đã stringify ở hàm gọi) khi cần.
 * @returns Dữ kiện JSON đã parse theo kiểu `T`.
 */
async function request<T>(path: string, init?: { method?: string; body?: string | FormData }): Promise<T> {
  const response = await fetchAuthed(path, init);
  if (response.status === 204) {
    return undefined as T;
  }
  return (await response.json()) as T;
}

/**
 * Lõi fetch có xác thực dùng chung cho đáp ứng JSON, thân `FormData` và đáp ứng byte (Blob): cùng một bước
 * kiểm tra phiên và làm mới token như `request`. Khi thân là `FormData` thì KHÔNG đặt `Content-Type` — theo
 * tài liệu MDN về FormData, trình duyệt phải tự đặt header này kèm boundary của multipart, đặt tay sẽ làm
 * mất boundary. Access token chỉ đi trong header `Authorization`, không bao giờ nằm trong URL.
 *
 * @param path - Đường tương đối bắt đầu bằng `/api/`.
 * @param init - Method, thân (JSON đã stringify hoặc `FormData`) và `Accept` khi cần.
 * @returns Đáp ứng đã qua kiểm tra lỗi (lỗi đã ném thành `ClassroomApiError`).
 */
async function fetchAuthed(
  path: string,
  init?: { method?: string; body?: string | FormData; accept?: string },
): Promise<Response> {
  const keycloak = getKeycloakAdapter();
  if (!keycloak.authenticated) {
    throw new ClassroomApiError(401, 'UNAUTHENTICATED', 'Chưa đăng nhập.');
  }
  await keycloak.updateToken(30);
  // Nhận diện thân FormData theo giao diện (có hàm `append`) thay cho `instanceof`: các realm khác nhau
  // (iframe, môi trường test) mang các lớp FormData khác nhau dù cùng một giao diện.
  const isForm =
    typeof init?.body === 'object' && init.body !== null && typeof (init.body as FormData).append === 'function';
  const response = await fetch(path, {
    method: init?.method ?? 'GET',
    headers: {
      Accept: init?.accept ?? 'application/json',
      ...(init?.body && !isForm ? { 'Content-Type': 'application/json' } : {}),
      Authorization: `Bearer ${keycloak.token ?? ''}`,
    },
    body: init?.body,
  });
  if (!response.ok) {
    throw await toApiError(response);
  }
  return response;
}

/**
 * Đọc thân lỗi `{code, message}` của server thành `ClassroomApiError`; thân không đọc được thì giữ mã rỗng và
 * thông điệp trống để tầng hiển thị dùng thông điệp dự phòng theo status.
 *
 * @param response - Đáp ứng lỗi của fetch.
 * @returns Lỗi có cấu trúc tương ứng.
 */
async function toApiError(response: Response): Promise<ClassroomApiError> {
  let code: string | null = null;
  let message = '';
  try {
    const body = (await response.json()) as { code?: unknown; message?: unknown };
    if (typeof body.code === 'string') code = body.code;
    if (typeof body.message === 'string') message = body.message;
  } catch {
    // Thân lỗi không phải JSON: giữ code/message rỗng, tầng hiển thị dùng thông điệp theo status.
  }
  return new ClassroomApiError(response.status, code, message);
}

/**
 * Stringify thân request một lần tại điểm gọi để lõi `request` không phải đoán kiểu dữ kiện.
 *
 * @param value - Dữ kiện gửi lên server.
 * @returns Chuỗi JSON của dữ kiện.
 */
function json(value: unknown): string {
  return JSON.stringify(value);
}

/** @returns Danh tính của người gọi theo `GET /api/me`. */
export function fetchMe(): Promise<MeView> {
  return request<MeView>('/api/me');
}

/** @returns Toàn bộ khóa của trung tâm ở trang hiện tại. */
export function fetchCourses(): Promise<CourseView[]> {
  return request<CourseView[]>('/api/courses');
}

/**
 * Tạo một khóa mới trong trung tâm.
 *
 * @param name - Tên khóa.
 * @returns Khóa vừa tạo.
 */
export function createCourse(name: string): Promise<CourseView> {
  return request<CourseView>('/api/courses', { method: 'POST', body: json({ name }) });
}

/** @returns Toàn bộ lớp của trung tâm ở trang hiện tại. */
export function fetchClasses(): Promise<TeachingClassView[]> {
  return request<TeachingClassView[]>('/api/classes');
}

/**
 * Tạo một lớp mới, có thể gắn vào một khóa.
 *
 * @param input - Tên lớp và định danh khóa (rỗng khi lớp không thuộc khóa nào).
 * @returns Lớp vừa tạo.
 */
export function createClass(input: { name: string; courseId?: string | null }): Promise<TeachingClassView> {
  return request<TeachingClassView>('/api/classes', {
    method: 'POST',
    body: json({ name: input.name, courseId: input.courseId ?? null }),
  });
}

/**
 * Đặt giáo viên phụ trách hiện hành của lớp.
 *
 * @param classId - Định danh lớp.
 * @param teacherSub - Định danh (sub) của giáo viên.
 * @returns Lớp sau khi phân công.
 */
export function setClassTeacher(classId: string, teacherSub: string): Promise<TeachingClassView> {
  return request<TeachingClassView>(`/api/classes/${classId}/teacher`, {
    method: 'PUT',
    body: json({ teacherSub }),
  });
}

/**
 * @param classId - Định danh lớp.
 * @returns Các dòng ghi danh đang hiệu lực của lớp.
 */
export function fetchMembers(classId: string): Promise<MemberView[]> {
  return request<MemberView[]>(`/api/classes/${classId}/members`);
}

/**
 * Ghi danh một học viên vào lớp; học viên không là thành viên của trung tâm được ghi danh với tư cách khách.
 *
 * @param classId - Định danh lớp.
 * @param studentSub - Định danh (sub) của học viên.
 * @returns Dòng ghi danh vừa tạo.
 */
export function addMember(classId: string, studentSub: string): Promise<MemberView> {
  return request<MemberView>(`/api/classes/${classId}/members`, {
    method: 'POST',
    body: json({ studentSub }),
  });
}

/**
 * Gỡ ghi danh của một học viên khỏi lớp (gỡ mềm phía server).
 *
 * @param classId - Định danh lớp.
 * @param studentSub - Định danh (sub) của học viên.
 */
export function removeMember(classId: string, studentSub: string): Promise<void> {
  return request<void>(`/api/classes/${classId}/members/${studentSub}`, { method: 'DELETE' });
}

/** @returns Các ngân hàng câu hỏi cá nhân của người gọi. */
export function fetchBanks(): Promise<BankView[]> {
  return request<BankView[]>('/api/question-banks');
}

/**
 * Tạo một ngân hàng câu hỏi cá nhân mới.
 *
 * @param name - Tên ngân hàng.
 * @returns Ngân hàng vừa tạo.
 */
export function createBank(name: string): Promise<BankView> {
  return request<BankView>('/api/question-banks', { method: 'POST', body: json({ name }) });
}

/**
 * Đưa một phiên bản câu vào một ngân hàng đích.
 *
 * @param bankId - Định danh ngân hàng đích.
 * @param questionVersionId - Định danh phiên bản câu.
 * @returns Mục ngân hàng vừa tạo.
 */
export function addBankEntry(bankId: string, questionVersionId: string): Promise<BankEntryView> {
  return request<BankEntryView>(`/api/question-banks/${bankId}/entries`, {
    method: 'POST',
    body: json({ questionVersionId }),
  });
}

/**
 * Gỡ một mục khỏi ngân hàng.
 *
 * @param bankId - Định danh ngân hàng.
 * @param entryId - Định danh mục trong ngân hàng.
 */
export function removeBankEntry(bankId: string, entryId: string): Promise<void> {
  return request<void>(`/api/question-banks/${bankId}/entries/${entryId}`, { method: 'DELETE' });
}

/** Thân tạo/sửa nội dung một phiên bản câu: đúng hai loại câu của đợt này. */
export interface QuestionContentInput {
  questionType: 'single_choice' | 'essay_text';
  stem: string;
  choices: { id: string; text: string }[];
  correctChoiceId: string | null;
  solution: string | null;
  rubric: string | null;
}

/**
 * Tạo một câu hỏi mới trong ngân hàng cá nhân kèm phiên bản nháp đầu tiên.
 *
 * @param bankId - Định danh ngân hàng chứa câu.
 * @param content - Nội dung phiên bản đầu tiên.
 * @returns Câu vừa tạo cùng các phiên bản của nó.
 */
export function createQuestion(bankId: string, content: QuestionContentInput): Promise<QuestionView> {
  return request<QuestionView>('/api/questions', {
    method: 'POST',
    body: json({ bankId, ...content, usageLimits: null }),
  });
}

/**
 * @param questionId - Định danh câu hỏi.
 * @returns Câu hỏi cùng toàn bộ các phiên bản.
 */
export function fetchQuestion(questionId: string): Promise<QuestionView> {
  return request<QuestionView>(`/api/questions/${questionId}`);
}

/**
 * Sửa nội dung một phiên bản nháp; `revision` là số sửa đổi hiện tại để server chống ghi đè lạc hậu (409 khi
 * lệch).
 *
 * @param questionId - Định danh câu hỏi.
 * @param versionId - Định danh phiên bản nháp.
 * @param input - Nội dung mới kèm `revision` hiện tại.
 * @returns Phiên bản sau khi sửa.
 */
export function updateQuestionVersion(
  questionId: string,
  versionId: string,
  input: QuestionContentInput & { revision: number },
): Promise<QuestionView['versions'][number]> {
  return request(`/api/questions/${questionId}/versions/${versionId}`, { method: 'PUT', body: json(input) });
}

/**
 * Mở một phiên bản nháp mới từ một phiên bản sẵn có của câu.
 *
 * @param questionId - Định danh câu hỏi.
 * @param fromVersionId - Định danh phiên bản nguồn.
 * @returns Phiên bản nháp vừa mở.
 */
export function createQuestionVersion(
  questionId: string,
  fromVersionId: string,
): Promise<QuestionView['versions'][number]> {
  return request(`/api/questions/${questionId}/versions`, {
    method: 'POST',
    body: json({ fromVersionId }),
  });
}

/**
 * Phát hành một phiên bản câu: server đóng băng nội dung và tính `contentHash`.
 *
 * @param questionId - Định danh câu hỏi.
 * @param versionId - Định danh phiên bản.
 * @returns Phiên bản đã phát hành.
 */
export function publishQuestionVersion(
  questionId: string,
  versionId: string,
): Promise<QuestionView['versions'][number]> {
  return request(`/api/questions/${questionId}/versions/${versionId}/publish`, { method: 'POST' });
}

/** Đường gốc của họ tệp đính kèm của một phiên bản câu. */
function attachmentsBase(questionId: string, versionId: string): string {
  return `/api/questions/${questionId}/versions/${versionId}/attachments`;
}

/**
 * Mở một phiên tải lên tệp đính kèm: phiên khoá đích (phiên bản, vị trí gắn) và giới hạn ngay từ đầu; các
 * bước sau không đổi được đích của phiên.
 *
 * @param questionId - Định danh câu hỏi.
 * @param versionId - Định danh phiên bản nháp đích.
 * @param input - Vị trí gắn, định danh khối khi gắn vào một khối nội dung, và tên hiển thị của tệp.
 * @returns Phiên vừa mở ở trạng thái `uploading`.
 */
export function openAttachmentSession(
  questionId: string,
  versionId: string,
  input: { position: AttachmentPosition; blockId?: string | null; displayName: string },
): Promise<UploadSessionView> {
  return request<UploadSessionView>(`${attachmentsBase(questionId, versionId)}/sessions`, {
    method: 'POST',
    body: json({ position: input.position, blockId: input.blockId ?? null, displayName: input.displayName }),
  });
}

/**
 * Tải byte của một ảnh vào phiên đã mở bằng `FormData`; server kiểm tra nội dung thật của tệp (không tin
 * loại do client khai) và trả phiên ở trạng thái `ready` kèm dấu của bản ảnh đã xử lý khi đạt.
 *
 * @param questionId - Định danh câu hỏi.
 * @param versionId - Định danh phiên bản nháp đích.
 * @param sessionId - Định danh phiên tải lên.
 * @param file - Tệp ảnh người dùng đã chọn.
 * @returns Phiên sau bước kiểm tra và xử lý.
 */
export function uploadAttachmentContent(
  questionId: string,
  versionId: string,
  sessionId: string,
  file: File,
): Promise<UploadSessionView> {
  const form = new FormData();
  form.append('file', file);
  return request<UploadSessionView>(`${attachmentsBase(questionId, versionId)}/sessions/${sessionId}/content`, {
    method: 'POST',
    body: form,
  });
}

/**
 * Đọc byte của bản ảnh đã xử lý trong phiên để xem trước trước khi gắn: đây là đúng bản sẽ được chốt khi
 * hoàn tất, hiển thị qua blob URL ở tầng giao diện.
 *
 * @param questionId - Định danh câu hỏi.
 * @param versionId - Định danh phiên bản nháp đích.
 * @param sessionId - Định danh phiên tải lên.
 * @returns Blob ảnh đã xử lý của phiên.
 */
export async function fetchAttachmentPreview(
  questionId: string,
  versionId: string,
  sessionId: string,
): Promise<Blob> {
  const response = await fetchAuthed(`${attachmentsBase(questionId, versionId)}/sessions/${sessionId}/preview`, {
    accept: 'image/*',
  });
  return response.blob();
}

/**
 * Hoàn tất phiên tải lên: server kiểm lại quyền tại thời điểm gắn và chốt blob bất biến từ đúng byte đã
 * kiểm tra. Gọi lại với cùng đích là an toàn (trả lại đúng liên kết đã tạo); gọi với đích khác bị từ chối.
 *
 * @param questionId - Định danh câu hỏi.
 * @param versionId - Định danh phiên bản nháp đích.
 * @param sessionId - Định danh phiên tải lên.
 * @returns Liên kết tệp vừa gắn vào bản nháp.
 */
export function completeAttachmentSession(
  questionId: string,
  versionId: string,
  sessionId: string,
): Promise<AttachmentLinkView> {
  return request<AttachmentLinkView>(`${attachmentsBase(questionId, versionId)}/sessions/${sessionId}/complete`, {
    method: 'POST',
    body: json({}),
  });
}

/**
 * @param questionId - Định danh câu hỏi.
 * @param versionId - Định danh phiên bản.
 * @returns Các liên kết tệp đính kèm của phiên bản mà người gọi được thấy (tệp đáp án bị lược khi không có
 * quyền xem đáp án).
 */
export function fetchAttachments(questionId: string, versionId: string): Promise<AttachmentLinkView[]> {
  return request<AttachmentLinkView[]>(attachmentsBase(questionId, versionId));
}

/**
 * Đọc byte của một tệp đính kèm đã gắn qua đường có xác thực; tầng giao diện hiển thị bằng blob URL, không
 * có URL công khai hay URL ký nào cho tệp.
 *
 * @param questionId - Định danh câu hỏi.
 * @param versionId - Định danh phiên bản chứa liên kết.
 * @param linkId - Định danh liên kết tệp.
 * @returns Blob byte của tệp.
 */
export async function fetchAttachmentContent(
  questionId: string,
  versionId: string,
  linkId: string,
): Promise<Blob> {
  const response = await fetchAuthed(`${attachmentsBase(questionId, versionId)}/${linkId}/content`, {
    accept: 'image/*',
  });
  return response.blob();
}

/**
 * Tạo một đề mới ở không gian cá nhân của giáo viên kèm phiên bản nháp đầu tiên.
 *
 * @param title - Tiêu đề đề.
 * @returns Đề vừa tạo.
 */
export function createExam(title: string): Promise<ExamView> {
  return request<ExamView>('/api/exams', {
    method: 'POST',
    body: json({ title, workspace: 'personal', collectionId: null }),
  });
}

/**
 * @param examId - Định danh đề.
 * @param versionId - Định danh phiên bản cần đọc, bỏ qua khi đọc phiên bản hiện hành.
 * @returns Đề cùng phiên bản tương ứng.
 */
export function fetchExam(examId: string, versionId?: string): Promise<ExamView> {
  const query = versionId ? `?versionId=${encodeURIComponent(versionId)}` : '';
  return request<ExamView>(`/api/exams/${examId}${query}`);
}

/**
 * Ghi nội dung một phiên bản đề nháp: tiêu đề và danh sách mục (phiên bản câu + điểm từng mục).
 * `lockVersion` là số khoá hiện tại để server chống ghi đè lạc hậu (409 khi lệch).
 *
 * @param examId - Định danh đề.
 * @param versionId - Định danh phiên bản nháp.
 * @param input - Tiêu đề, `lockVersion` hiện tại và các mục theo thứ tự hiển thị.
 * @returns Đề sau khi ghi.
 */
export function updateExamVersion(
  examId: string,
  versionId: string,
  input: { title: string; lockVersion: number; items: { questionVersionId: string; score: number }[] },
): Promise<ExamView> {
  return request<ExamView>(`/api/exams/${examId}/versions/${versionId}`, {
    method: 'PUT',
    body: json(input),
  });
}

/**
 * Mở một phiên bản nháp mới của đề từ một phiên bản nguồn.
 *
 * @param examId - Định danh đề.
 * @param sourceVersionId - Định danh phiên bản nguồn.
 * @returns Đề ở phiên bản nháp vừa mở.
 */
export function createExamVersion(examId: string, sourceVersionId: string): Promise<ExamView> {
  return request<ExamView>(`/api/exams/${examId}/versions`, {
    method: 'POST',
    body: json({ sourceVersionId }),
  });
}

/**
 * Phát hành một phiên bản đề: server đóng băng snapshot của từng câu trong đề tại thời điểm này.
 *
 * @param examId - Định danh đề.
 * @param versionId - Định danh phiên bản.
 * @returns Đề ở phiên bản đã phát hành.
 */
export function publishExamVersion(examId: string, versionId: string): Promise<ExamView> {
  return request<ExamView>(`/api/exams/${examId}/versions/${versionId}/publish`, { method: 'POST' });
}

/** @returns Các lần giao mà người gọi nhìn thấy theo vai của mình ở trang hiện tại. */
export function fetchAssignments(): Promise<AssignmentView[]> {
  return request<AssignmentView[]>('/api/assignments');
}

/**
 * Giao một phiên bản đề đã phát hành cho một lớp theo cửa sổ thời gian.
 *
 * @param input - Phiên bản đề, lớp đích và cửa sổ mở/đóng (chuỗi thời gian có múi giờ).
 * @returns Lần giao vừa tạo.
 */
export function createAssignment(input: {
  examVersionId: string;
  classId: string;
  opensAt: string;
  dueAt: string;
}): Promise<AssignmentView> {
  return request<AssignmentView>('/api/assignments', { method: 'POST', body: json(input) });
}

/**
 * Bắt đầu lượt làm cho một lần giao, hoặc lấy lại đúng lượt đang làm dở của chính học viên (server bảo đảm mỗi
 * học viên một lượt cho mỗi lần giao).
 *
 * @param assignmentId - Định danh lần giao.
 * @returns Lượt làm kèm hình chiếu các mục (không có đáp án) và câu trả lời đã lưu.
 */
export function startAttempt(assignmentId: string): Promise<AttemptView> {
  return request<AttemptView>(`/api/assignments/${assignmentId}/attempts`, { method: 'POST' });
}

/**
 * Lưu câu trả lời của lượt làm; server chỉ nhận khi lượt còn trong cửa sổ và chưa nộp.
 *
 * @param attemptId - Định danh lượt làm.
 * @param answers - Danh sách cặp mục–câu trả lời theo hình `{"choiceId": ...}` hoặc `{"text": ...}`.
 * @returns Lượt làm sau khi lưu.
 */
export function saveAnswers(
  attemptId: string,
  answers: { examItemId: string; answer: unknown }[],
): Promise<AttemptView> {
  return request<AttemptView>(`/api/attempts/${attemptId}/answers`, {
    method: 'PUT',
    body: json({ answers }),
  });
}

/**
 * Nộp lượt làm; thao tác có tính idempotent phía server.
 *
 * @param attemptId - Định danh lượt làm.
 * @returns Trạng thái lượt sau khi nộp.
 */
export function submitAttempt(attemptId: string): Promise<SubmitView> {
  return request<SubmitView>(`/api/attempts/${attemptId}/submit`, { method: 'POST' });
}

/**
 * Đọc một lượt làm của học viên (cùng endpoint với hình làm bài): trường `result` của đáp ứng rỗng cho tới
 * khi lượt được công bố, có dữ kiện điểm/nhận xét sau công bố.
 *
 * @param attemptId - Định danh lượt làm.
 * @returns Lượt làm kèm phần kết quả (rỗng khi chưa công bố).
 */
export function fetchAttemptResult(attemptId: string): Promise<AttemptView> {
  return request<AttemptView>(`/api/attempts/${attemptId}`);
}

/**
 * Đọc hình chiếu chấm của lượt cho giáo viên phụ trách (đáp án đúng, tiêu chí chấm, điểm hiện có).
 *
 * @param attemptId - Định danh lượt làm.
 * @returns Hình chiếu chấm đầy đủ của lượt.
 */
export function fetchTeacherAttempt(attemptId: string): Promise<TeacherAttemptView> {
  return request<TeacherAttemptView>(`/api/attempts/${attemptId}`);
}

/**
 * @param assignmentId - Định danh lần giao.
 * @returns Bảng bài nộp của lần giao, kèm phần điểm của lát chấm/công bố (luôn có mặt; điểm tổng rỗng khi
 * lượt chưa có điểm).
 */
export function fetchSubmissions(assignmentId: string): Promise<SubmissionRowView[]> {
  return request<SubmissionRowView[]>(`/api/assignments/${assignmentId}/submissions`);
}

/**
 * Chấm một lượt làm: điểm và nhận xét theo từng mục tự luận, kèm nhận xét chung của lượt.
 *
 * @param attemptId - Định danh lượt làm.
 * @param input - Các mục chấm tay và nhận xét chung.
 * @returns Kết quả chấm gồm tổng điểm và cờ điểm tạm.
 */
export function gradeAttempt(
  attemptId: string,
  input: { items: { examItemId: string; score: number; comment: string | null }[]; comment: string | null },
): Promise<GradeResultView> {
  return request<GradeResultView>(`/api/attempts/${attemptId}/grade`, {
    method: 'POST',
    body: json(input),
  });
}

/**
 * Công bố kết quả của một lần giao cho học viên.
 *
 * @param assignmentId - Định danh lần giao.
 * @returns Biên nhận công bố.
 */
export function publishResults(assignmentId: string): Promise<PublicationView> {
  return request<PublicationView>(`/api/assignments/${assignmentId}/publish-results`, { method: 'POST' });
}
