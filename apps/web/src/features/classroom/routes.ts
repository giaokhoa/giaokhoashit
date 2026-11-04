/**
 * Config route của feature classroom (issue #102).
 *
 * Toàn bộ màn hình của feature nằm NGOÀI shell layout, cùng khuôn với trang tenant của feature auth: các màn
 * hình này chạy trên phiên Keycloak của trang trung tâm, không chạy trên phiên của shell, nên chúng được gộp ở
 * tầng app qua khối `standaloneRoutes` dưới tiền tố `classroom`. Khối mặc định rỗng vì feature không có trang
 * nào trong shell.
 */
import { type RouteConfig, relative } from '@react-router/dev/routes';

const { route, index, prefix } = relative(import.meta.dirname);

export default [] satisfies RouteConfig;

/**
 * Các màn hình ba vai trò của bản phát hành 07/11 dưới tiền tố `/classroom`: trang chính, chi tiết lớp, ngân
 * hàng câu hỏi, sửa/phát hành câu, soạn/phát hành/giao đề, làm bài và chấm/công bố.
 */
export const standaloneRoutes = prefix('classroom', [
  index('routes/hub.tsx'),
  route('classes/:classId', 'routes/class-detail.tsx'),
  route('bank', 'routes/bank.tsx'),
  route('questions/:questionId', 'routes/question-edit.tsx'),
  route('exams/:examId', 'routes/exam-edit.tsx'),
  route('assignments/:assignmentId/attempt', 'routes/attempt.tsx'),
  route('assignments/:assignmentId/grade', 'routes/grade.tsx'),
]) satisfies RouteConfig;
