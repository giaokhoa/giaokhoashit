import { type RouteConfig, relative } from '@react-router/dev/routes';

const { route } = relative(import.meta.dirname);

/**
 * Khu route của ngân hàng câu hỏi và đề. Bản phát hành 07/11 (issue #79) chỉ đăng ký bề mặt cá nhân thuộc vòng
 * dạy–học: danh sách câu hỏi, ngữ liệu, tạo chuyên mục, lịch sử dùng câu và danh sách đề cá nhân. Các route ngân
 * hàng hệ thống (`system-bank*`) và trang danh sách ma trận (`matrices`) thuộc tính năng đã hoãn nên không còn
 * được đăng ký — module của chúng còn trong feature nhưng không được nạp cho tới khi tính năng được mở lại.
 */
export default [
  route('questions', 'routes/questions.tsx'),
  route('questions/stimuli', 'routes/question-stimuli.tsx'),
  route('questions/categories/new', 'routes/question-category-new.tsx'),
  route('question-usages/:id', 'routes/question-usage.tsx'),
  route('exams', 'routes/exams.tsx'),
] satisfies RouteConfig;
