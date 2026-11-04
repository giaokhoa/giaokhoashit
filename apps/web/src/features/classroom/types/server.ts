/**
 * Kiểu dữ kiện của các bề mặt `apps/server` mà màn hình nghiệp vụ của bản phát hành 07/11 tiêu thụ (issue #102).
 *
 * Các kiểu ở đây chép đúng hình thân đáp ứng JSON của server: tổ chức dạy học (khóa, lớp, thành viên), ngân hàng
 * câu hỏi và câu hỏi, đề, lần giao và lượt làm, cùng hình chiếu chấm/công bố. Server là nơi quyết định quyền và
 * tạo hình chiếu cho từng vai — phía web chỉ trình bày đúng các trường server trả, điển hình là hình chiếu bài
 * làm cho học viên không hề có trường đáp án/lời giải/tiêu chí chấm nên màn hình không thể lộ chúng.
 *
 * Hình đáp ứng của lát chấm/công bố dưới đây đã đối chiếu với server sau khi lát đó merge (PR #86): dòng bài
 * nộp luôn mang đủ bốn trường điểm (điểm tổng rỗng khi lượt chưa có điểm), và đáp ứng đọc một lượt làm luôn
 * mang trường `result` — rỗng cho tới khi lượt được công bố, có dữ kiện điểm/nhận xét sau công bố.
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

/** Vị trí gắn một tệp đính kèm trong nội dung một phiên bản câu; `answer` và `solution` là vị trí mang đáp án. */
export type AttachmentPosition = 'content' | 'choice' | 'answer' | 'solution' | 'document';

/**
 * Một phiên tải lên tệp đính kèm. `status` là trạng thái xử lý của hợp đồng tệp: `uploading` khi phiên mới
 * mở, `ready` khi byte đã qua kiểm tra và gắn được (hoặc đã gắn xong), `rejected` khi phiên hết hạn/bị huỷ.
 * Các trường mô tả ảnh chỉ có sau khi byte đã qua kiểm tra; `checksumSha256` là dấu của đúng bản ảnh đã xử lý
 * mà người tải lên xác nhận trước khi gắn.
 */
export interface UploadSessionView {
  sessionId: string;
  questionId: string;
  versionId: string;
  position: AttachmentPosition;
  blockId: string | null;
  displayName: string;
  status: 'uploading' | 'ready' | 'rejected';
  contentType: string | null;
  sizeBytes: number | null;
  width: number | null;
  height: number | null;
  checksumSha256: string | null;
  blobId: string | null;
  linkId: string | null;
  expiresAt: string;
}

/** Một liên kết tệp đính kèm đã gắn vào một phiên bản câu, kèm dấu của blob bất biến mà nó trỏ tới. */
export interface AttachmentLinkView {
  linkId: string;
  questionId: string;
  versionId: string;
  position: AttachmentPosition;
  blockId: string | null;
  blobId: string;
  displayName: string;
  contentType: string;
  sizeBytes: number;
  checksumSha256: string;
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

/**
 * Một lượt làm bài của học viên, kèm các mục và câu trả lời đã lưu. Trường `result` luôn có mặt trong đáp
 * ứng: rỗng ở mọi đáp ứng làm bài và ở lượt chưa công bố; chỉ đáp ứng đọc một lượt đã công bố mới mang dữ
 * kiện kết quả trong đó.
 */
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
  result: StudentResultView | null;
}

/** Đáp ứng của thao tác nộp bài. */
export interface SubmitView {
  attemptId: string;
  status: string;
  submittedAt: string | null;
  submitMode: string | null;
}

/**
 * Một dòng trong bảng bài nộp của một lần giao: trạng thái lượt của học viên kèm phần điểm do lát chấm/công
 * bố tính. Cả bốn trường điểm luôn có mặt: `totalScore` rỗng khi lượt chưa có điểm để cộng (đang làm hoặc
 * chưa bắt đầu — dòng chưa bắt đầu mang trạng thái `NOT_STARTED` do server tự ghi), `provisional` bật cho
 * tới khi lượt chấm xong, `published` bật khi lượt đã công bố.
 */
export interface SubmissionRowView {
  studentSub: string;
  status: string;
  startedAt: string | null;
  submittedAt: string | null;
  submitMode: string | null;
  removed: boolean;
  totalScore: number | null;
  maxScore: number;
  provisional: boolean;
  published: boolean;
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
 * Điểm hiện có của một mục trong hình chiếu chấm cho giáo viên phụ trách. Cả điểm, điểm tối đa và nguồn điểm
 * đều luôn có mặt khi mục đã có điểm; nhận xét có thể rỗng.
 */
export interface TeacherItemGradeView {
  score: number;
  maxScore: number;
  source: string;
  comment: string | null;
}

/**
 * Một mục trong hình chiếu chấm: kèm đáp án đúng/tiêu chí chấm, câu trả lời và điểm hiện có của mục. Trường
 * `grade` rỗng khi mục chưa được chấm — mục tự luận trước khi giáo viên chấm, hoặc mọi mục khi lượt còn đang
 * làm; mục trắc nghiệm có điểm tự động ngay khi lượt được nộp.
 */
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
  grade: TeacherItemGradeView | null;
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
