import { type RouteConfig, relative } from '@react-router/dev/routes';

const { prefix, route } = relative(import.meta.dirname);

/**
 * Khu route của feature guardians: khu vực phụ huynh theo từng
 * con (chuẩn đường dẫn #221, lát R2 — đối tượng chính của khu vực
 * nằm trong path, không định danh bằng ?student=; link cũ dạng
 * /guardian?student=<id> được chính trang guardian chuyển hướng
 * thay thế sang path theo con) và trang quản lý liên kết giám hộ
 * dưới tiền tố /admin.
 */
export default prefix('guardian', [
  route('', 'routes/guardian.tsx'),
  route('students/:studentId', 'routes/guardian-student.tsx'),
]) satisfies RouteConfig;

export const adminGuardianLinksRoutes = [
  route('admin/guardian-links', 'routes/admin-guardian-links.tsx'),
] satisfies RouteConfig;
