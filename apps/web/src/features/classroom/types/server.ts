/**
 * Kiểu dữ kiện của các bề mặt `apps/server` mà màn hình nghiệp vụ của bản phát hành 07/11 tiêu thụ (issue #102).
 *
 * Các kiểu ở đây chép đúng hình thân đáp ứng JSON của server: tổ chức dạy học (khóa, lớp, thành viên), ngân hàng
 * câu hỏi và câu hỏi, đề, lần giao và lượt làm, cùng hình chiếu chấm/công bố. Server là nơi quyết định quyền và
 * tạo hình chiếu cho từng vai — phía web chỉ trình bày đúng các trường server trả, điển hình là hình chiếu bài
 * làm cho học viên không hề có trường đáp án/lời giải/tiêu chí chấm nên màn hình không thể lộ chúng.
 *
 * Hai điểm chờ tích hợp với lát chấm/công bố (PR #86 chưa merge khi viết file này): dòng bài nộp có thêm các
 * trường điểm và `GET /api/attempts/{id}` trả thêm hình chiếu kết quả của học viên sau công bố. Các trường đó
 * khai báo không bắt buộc ở đây để cùng một màn hình chạy đúng với cả hai hình đáp ứng; khi PR #86 merge, lượt
 * kiểm chứng tích hợp đối chiếu lại hình đáp ứng thật với các kiểu này.
 */

/** Đáp ứng `GET /api/me`: danh tính đã kiểm chứng từ token, vai realm và các alias Organization là thành viên. */
export interface MeView {
  sub: string;
  preferredUsername: string | null;
  name: string | null;
  email: string | null;
  realmRoles: string[];
  organizations: string[];
}

/** Một khóa học trong danh sách của trung tâm. */
export interface CourseView {
  id: string;
  name: string;
}

/** Một lớp học; `teacherSub` là định danh của giáo viên phụ trách hiện hành, rỗng khi chưa phân công. */
export interface TeachingClassView {
  id: string;
  name: string;
  courseId: string | null;
  teacherSub: string | null;
}

/** Một dòng ghi danh đang hiệu lực trong lớp. */
export interface MemberView {
  studentSub: string;
  enrolledBySub: string;
  enrolledAt: string;
}

/** Một ngân hàng tài nguyên (ngân hàng câu hỏi cá nhân của giáo viên ở đợt này). */
export interface BankView {
  id: string;
  name: string;
  workspaceKind: string;
  ownerType: string;
  ownerUserSub: string | null;
  custodyKind: string;
  createdAt: string;
  updatedAt: string;
}

/** Một mục trong ngân hàng: một phiên bản câu được đưa vào ngân hàng đích. */
export interface BankEntryView {
  id: string;
  bankId: string;
  questionId: string;
  questionVersionId: string;
  createdAt: string;
}

/** Một lựa chọn của câu trắc nghiệm, dùng cả khi soạn (gửi lên) và khi đọc phiên bản. */
export interface ChoiceView {
  id: string;
  text: string;
}

/**
 * Một phiên bản của câu hỏi. Các trường `correctChoiceId`, `solution`, `rubric` chỉ có mặt khi người gọi có
 * quyền xem đáp án — server lược hẳn trường khỏi JSON khi không có quyền, nên phía web khai báo không bắt buộc
 * và không tự suy ra chúng.
 */
export interface QuestionVersionView {
  versionId: string;
  versionNumber: number;
  status: string;
  lockedAt: string | null;
  contentHash: string | null;
  revision: number;
  questionType: string;
  stem: string;
  choices: ChoiceView[];
  correctChoiceId?: string | null;
  solution?: string | null;
  rubric?: string | null;
}

/** Một câu hỏi cùng toàn bộ các phiên bản của nó. */
export interface QuestionView {
  id: string;
  bankId: string | null;
  versions: QuestionVersionView[];
  createdAt: string;
  updatedAt: string;
}

/** Một mục của phiên bản đề: câu được ghim theo `questionVersionId` kèm điểm và nội dung snapshot đã đóng băng. */
export interface ExamItemView {
  id: string;
  questionVersionId: string;
  questionType: string;
  position: number;
  score: number;
  content: unknown;
}

/** Một đề cùng phiên bản đang đọc; `status`/`lockedAt`/`contentHash` cho biết phiên bản đã phát hành hay chưa. */
export interface ExamView {
  id: string;
  title: string;
  versionId: string;
  versionNumber: number;
  status: string;
  lockedAt: string | null;
  contentHash: string | null;
  lockVersion: number;
  items: ExamItemView[];
}

/** Một lần giao: một phiên bản đề đã phát hành ghim cho một lớp trong cửa sổ `opensAt`–`dueAt`. */
export interface AssignmentView {
  id: string;
  classId: string;
  examResourceId: string;
  examVersionId: string;
  assignedBySub: string;
  opensAt: string;
  dueAt: string;
}

/** Một mục bài trong hình chiếu làm bài cho học viên: chỉ có thân câu và các lựa chọn, không có đáp án. */
export interface AttemptItemView {
  examItemId: string;
  type: string;
  position: number;
  points: number;
  prompt: string;
  choices: { choiceId: string; text: string }[];
}

/** Một câu trả lời đã lưu của lượt làm; `answer` là `{"choiceId": ...}` hoặc `{"text": ...}` tuỳ loại câu. */
export interface AttemptAnswerView {
  examItemId: string;
  answer: unknown;
}

/** Một lượt làm bài của học viên, kèm các mục và câu trả lời đã lưu. */
export interface AttemptView {
  attemptId: string;
  assignmentId: string;
  status: string;
  startedAt: string | null;
  submittedAt: string | null;
  submitMode: string | null;
  opensAt: string;
  dueAt: string;
  items: AttemptItemView[];
  answers: AttemptAnswerView[];
}

/** Đáp ứng của thao tác nộp bài. */
export interface SubmitView {
  attemptId: string;
  status: string;
  submittedAt: string | null;
  submitMode: string | null;
}

/**
 * Một dòng trong bảng bài nộp của một lần giao. Bốn trường điểm cuối dòng do lát chấm/công bố bổ sung; trước
 * khi lát đó merge, server chỉ trả các trường trạng thái nên các trường điểm khai báo không bắt buộc.
 */
export interface SubmissionRowView {
  studentSub: string;
  status: string | null;
  startedAt: string | null;
  submittedAt: string | null;
  submitMode: string | null;
  removed: boolean;
  totalScore?: number | null;
  maxScore?: number | null;
  provisional?: boolean;
  published?: boolean;
}

/** Đáp ứng của thao tác chấm một lượt làm. */
export interface GradeResultView {
  attemptId: string;
  status: string;
  totalScore: number;
  maxScore: number;
  provisional: boolean;
}

/** Đáp ứng của thao tác công bố kết quả theo lần giao. */
export interface PublicationView {
  assignmentId: string;
  publishedBySub: string;
  publishedAt: string;
}

/** Kết quả từng mục trong hình chiếu kết quả của học viên sau công bố: điểm và nhận xét, không có đáp án. */
export interface StudentResultItemView {
  examItemId: string;
  score: number | null;
  maxScore: number;
  comment: string | null;
}

/** Kết quả của chính học viên trong một lượt đã công bố. */
export interface StudentResultView {
  items: StudentResultItemView[];
  overallComment: string | null;
  totalScore: number;
  maxScore: number;
}

/**
 * Hình chiếu `GET /api/attempts/{id}` cho học viên theo hợp đồng của lát chấm/công bố: `result` chỉ khác rỗng
 * khi lượt đã công bố. Trước khi lát đó merge, đáp ứng thật là hình làm bài nên `result` vắng mặt — màn kết quả
 * hiểu vắng mặt là chưa công bố.
 */
export interface StudentAttemptView {
  attemptId: string;
  status: string;
  result?: StudentResultView | null;
}

/** Điểm hiện có của một mục trong hình chiếu chấm cho giáo viên phụ trách. */
export interface TeacherItemGradeView {
  score: number | null;
  maxScore: number;
  source: string | null;
  comment: string | null;
}

/** Một mục trong hình chiếu chấm: kèm đáp án đúng/tiêu chí chấm, câu trả lời và điểm hiện có của mục. */
export interface TeacherAttemptItemView {
  examItemId: string;
  questionType: string;
  order: number;
  stem: string;
  choices: { id: string; text: string }[];
  correctChoiceId: string | null;
  rubric: string | null;
  maxScore: number;
  answer: Record<string, unknown> | null;
  grade: TeacherItemGradeView;
}

/** Hình chiếu `GET /api/attempts/{id}` cho giáo viên phụ trách, phục vụ màn chấm theo mục. */
export interface TeacherAttemptView {
  attemptId: string;
  assignmentId: string;
  studentSub: string;
  status: string;
  items: TeacherAttemptItemView[];
  totalScore: number | null;
  maxScore: number;
  provisional: boolean;
  overallComment: string | null;
}
