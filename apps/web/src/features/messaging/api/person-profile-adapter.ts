/**
 * Lớp ADAPTER dữ kiện hồ sơ người cho bề mặt chat (issue #591 —
 * đợt dựng giao diện giống hệt demo trước, nối backend sau).
 *
 * Bối cảnh: panel thông tin của bản demo bày hồ sơ người đầy đủ (giới thiệu, email, điện thoại, website, công ty, quốc
 * gia, múi giờ, địa điểm, trạng thái sẵn sàng, các thẻ), nhưng hợp đồng dữ kiện người dùng phía chat hiện tại chỉ có
 * định danh, tên hiển thị và tên đăng nhập (`PersonSummary`). Mọi trường backend chưa có PHẢI đi qua lớp adapter này —
 * component không tự chứa dữ kiện tạm và không bịa giá trị: trường nào chưa có nguồn thì adapter trả `null` (hoặc danh
 * sách rỗng), và panel KHÔNG dựng hàng của trường đó (đúng luật hiển thị của bản demo, căn ở issue #608 — trước đây
 * panel hiện dấu gạch ngang ở vị trí của trường, đã bỏ).
 *
 * Đợt backend (#592) đã thay nguồn của MỘT trường: email nay lấy từ endpoint hồ sơ `GET /messages/users/:id/profile`
 * (dữ kiện thật từ bảng người dùng) và được chèn đè lên hồ sơ adapter ở nơi gọi; các trường còn lại (giới thiệu, điện
 * thoại, website, công ty, quốc gia, múi giờ, địa điểm, trạng thái sẵn sàng, các thẻ, dòng vai trò ở chat 1–1) KHÔNG có
 * cột/bảng nguồn trong hệ nên vẫn giữ qua adapter với giá trị rỗng — xem thân PR #592 để biết lý do từng trường. Đợt
 * này chỉ thay nguồn dữ kiện của các hàm trong file này; chữ ký và hình dạng trả về giữ nguyên để component không phải
 * sửa (ngoại trừ việc chèn đè email từ query hồ sơ ở nơi gọi).
 */
import type { PersonSummary } from '../types/types';

/**
 * Hình dạng hồ sơ người mà panel thông tin tiêu thụ. Các trường đánh dấu chờ backend là các trường hợp đồng hiện tại
 * chưa có; chúng mang giá trị `null`/rỗng cho tới khi đợt backend cấp nguồn thật qua adapter này.
 */
export interface PersonProfileView {
  userId: string;
  displayName: string;
  username: string;
  roleLine: string | null;
  bio: string | null;
  email: string | null;
  phone: string | null;
  website: string | null;
  company: string | null;
  country: string | null;
  timezone: string | null;
  location: string | null;
  availability: string | null;
  tags: string[];
}

/**
 * Dựng hồ sơ hiển thị cho panel thông tin từ tóm tắt người dùng hiện có. Chỉ ba trường định danh/tên là dữ kiện thật;
 * mọi trường hồ sơ mở rộng trả `null`/rỗng (xem TSDoc của file). Riêng email: từ đợt backend #592, nơi gọi chèn đè giá
 * trị từ endpoint hồ sơ lên kết quả của hàm này khi query đã về — hàm này vẫn giữ `null` làm giá trị chờ để panel không
 * hiện email giả trong lúc tải.
 */
export function getPersonProfile(person: PersonSummary, roleLine?: string | null): PersonProfileView {
  return {
    userId: person.userId,
    displayName: person.displayName,
    username: person.username,
    roleLine: roleLine ?? null,
    bio: null,
    email: null,
    phone: null,
    website: null,
    company: null,
    country: null,
    timezone: null,
    location: null,
    availability: null,
    tags: [],
  };
}

/**
 * Một tệp đã chia sẻ trong hội thoại — hình dạng cho khối tệp của panel thông tin. Đính kèm tệp là điểm CHƯA có phán
 * quyết về chức năng và backend chưa có dữ kiện đính kèm nào, nên nguồn hiện tại luôn rỗng; đợt backend chỉ thay nguồn
 * của hàm này khi điểm đính kèm được phán quyết và thi công.
 */
export interface SharedFileView {
  id: string;
  name: string;
  sizeLabel?: string;
}

/**
 * Danh sách tệp đã chia sẻ của một hội thoại cho panel thông tin — hiện luôn rỗng vì chưa có nguồn dữ kiện (xem {@link
 * SharedFileView}); panel chỉ hiện khối tệp khi danh sách có phần tử, đúng hành vi của bản demo (điểm G9).
 */
export function getSharedFiles(conversationId: string): SharedFileView[] {
  // Đợt UI chưa có nguồn dữ kiện tệp: tham số chỉ giữ chữ ký cho đợt backend #592 thay nguồn, kết quả luôn rỗng.
  void conversationId;
  return [];
}

/**
 * Mẫu chip trả lời nhanh phía trên ô soạn — CHỈ LÀ HÌNH DẠNG (issue #591): chip trả lời nhanh là điểm chưa có phán
 * quyết về chức năng và không có nguồn dữ kiện nào, nên các chip này chỉ hiện đúng hình dạng của demo ở trạng thái vô
 * hiệu, không chèn nội dung nào vào ô soạn. Khi điểm này có phán quyết, nguồn mẫu được thay tại đây.
 */
export const QUICK_REPLY_SHAPE_SAMPLES: readonly string[] = ['Xin chào', 'Cảm ơn bạn', 'Đã rõ'];
