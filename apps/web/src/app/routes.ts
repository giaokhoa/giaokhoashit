import { type RouteConfig, layout } from '@react-router/dev/routes';
import authRoutes, { standaloneRoutes as authStandaloneRoutes } from '../features/auth/routes';
import classesRoutes from '../features/classes/routes';
import { standaloneRoutes as classroomStandaloneRoutes } from '../features/classroom/routes';
import coursesRoutes from '../features/courses/routes';
import dashboardRoutes from '../features/dashboard/routes';
import examsRoutes from '../features/exams/routes';
import gradesRoutes from '../features/grades/routes';
import profileRoutes from '../features/profile/routes';
import questionEditorRoutes from '../features/question-editor/routes';
import questionsRoutes from '../features/questions/routes';
import settingsRoutes from '../features/settings/routes';

/**
 * Bản đồ route của app (React Router framework mode): file này chỉ còn vai trò GỘP — mọi trang đều nằm dưới một layout
 * không đường dẫn (`shell.tsx`) tự chọn khung công khai hay khung đã đăng nhập theo phiên, giữ đúng hành vi của router
 * cũ; từng khu route do feature sở hữu khai ở config riêng `features/<feature>/routes.ts` (dùng
 * `relative()`/`prefix()` của framework) và được trải vào layout tại đây theo đúng thứ tự khai báo gốc. Mỗi trang là
 * một route module riêng thuộc về feature của nó để framework tự chia chunk theo route. Trang tenant Keycloak của
 * feature auth nằm ngoài shell (khối `standaloneRoutes`). Các màn hình ba vai trò của bản phát hành 07/11
 * (feature classroom, issue #102) cũng nằm ngoài shell vì chúng chạy trên phiên Keycloak của trang trung tâm như
 * trang tenant, gộp qua khối `standaloneRoutes` của feature đó.
 *
 * Bề mặt của bản phát hành 07/11 (issue #79): chỉ các feature thuộc vòng dạy–học và khung danh tính được gộp tại đây.
 * Route của các feature đã hoãn (quản trị hệ thống và trung tâm, ma trận, ngân hàng hệ thống, tin nhắn, thông báo,
 * lịch, huy hiệu, kế hoạch học tập, phụ huynh, chương trình và kho học liệu, làm bài legacy, báo cáo) KHÔNG được
 * đăng ký — mã nguồn của chúng còn trong repo nhưng không được nạp vì không còn điểm gộp nào trỏ tới; gỡ hẳn thuộc
 * đợt gỡ `apps/api` về sau.
 */
export default [
  layout('shell.tsx', [
    ...dashboardRoutes,
    ...authRoutes,
    ...profileRoutes,
    ...settingsRoutes,
    ...coursesRoutes,
    ...classesRoutes,
    ...gradesRoutes,
    ...questionsRoutes,
    ...questionEditorRoutes,
    ...examsRoutes,
  ]),
  ...authStandaloneRoutes,
  ...classroomStandaloneRoutes,
] satisfies RouteConfig;
