
export type ScrollerHandle = { scrollToMessage: (messageId: string, options?: { behavior?: ScrollBehavior }) => boolean };
export interface MessageQuoteView {
  id: string;
  seq: number;
  userIdFrom: string;
  excerpt: string;
  unavailable: boolean;
}

export interface MessageView {
  id: string;
  conversationId: string;
  userIdFrom: string;
  fullMessage: string;
  createdAt: string;
  seq: number;
  clientMessageId: string | null;
  replyToMessageId?: string | null;
  replyTo?: MessageQuoteView | null;
  /**
   * Dấu vết xoá mềm (Lớp B, issue #33): tin đã xoá chỉ còn xuất hiện
   * trong cửa sổ của người có thẩm quyền kiểm duyệt; phía học sinh
   * không bao giờ nhận được tin có hai trường này khác null.
   */
  deletedAt?: string | null;
  deletedBy?: string | null;
  /**
   * Dấu vết THU HỒI (vòng đời tin nhắn theo vai, issue #33): đường đọc thường trả nội dung rỗng kèm hai trường này để
   * hiện dòng "tin nhắn đã được thu hồi"; chỉ đường truy vết có thẩm quyền mới thấy nội dung gốc.
   */
  recalledAt?: string | null;
  recalledBy?: string | null;
  localStatus?: 'sending' | 'failed';
}
export interface MessageSearchResultView {
  id: string;
  conversationId: string;
  userIdFrom: string;
  seq: number;
  excerpt: string;
  createdAt: string;
}
export interface ConversationStatePatch {
  pinned?: boolean;
  archived?: boolean;
  muted?: boolean;
}

export interface ConversationStateView {
  conversationId: string;
  pinned: boolean;
  pinnedAt: string | null;
  archived: boolean;
  archivedAt: string | null;
  muted: boolean;
}

export interface PersonSummary {
  userId: string;
  displayName: string;
  username: string;
}
export type ContactRelationship = 'contact' | 'pending_incoming' | 'pending_outgoing' | 'blocked' | 'none';

/**
 * Trạng thái hiện diện ở TẦNG HIỂN THỊ của tính năng nhắn tin (issue #608): bản demo có đủ bốn trạng thái — trực tuyến,
 * vắng mặt (away), đang bận (busy), ngoại tuyến — với bản đồ màu và nhãn riêng. Dữ kiện hiện diện hiện hành phía server
 * chỉ phát trực tuyến/ngoại tuyến (xem `presenceStatusOf` ở utils), nên hai trạng thái vắng mặt/bận mới chỉ tồn tại ở
 * tầng hiển thị: khi dữ kiện mang được chúng, mọi bề mặt chat tự hiện đúng màu và nhãn mà không phải sửa lại component.
 */
export type PresenceStatus = 'online' | 'away' | 'busy' | 'offline';
export interface MessagingCourse {
  courseId: string;
  fullName: string;
  shortName: string;
}
export interface RosterMember extends PersonSummary {
  role: 'teacher' | 'student';
  relationship: ContactRelationship;
  /**
   * GV của khoá nhắn riêng trực tiếp được với thành viên này (đang
   * ghi danh) không cần kết bạn — ngoại lệ chốt 25/10/2026 trên
   * issue #33; API kèm cờ này thì hiện nút "Nhắn tin" tại dòng.
   */
  canDirectMessage?: boolean;
}
export interface ContactRequestView {
  userId: string;
  createdAt: string;
  displayName?: string;
  username?: string;
}
export interface ConversationView {
  id: string;
  type: number;
  name: string | null;
  /**
   * Phân loại cấu trúc (lát MSG-T1, issue #33): `core_course`/`course` = chat cấp khoá, `core_group`/`groups` = chat
   * nhóm con, `core_class`/`class` = chat cấp lớp (MSG-T2); null ở hội thoại cá nhân và nhóm tự do. Phân loại CHỈ theo
   * ba trường này, không đoán theo tên.
   */
  component?: string | null;
  itemType?: string | null;
  itemId?: string | null;
  members: string[];
  memberProfiles?: Record<string, PersonSummary>;
  /**
   * Số tin chưa đọc THEO VŨ TRỤ CHUẨN (issue #592): server trả 0 cho hội thoại đã tắt tiếng dù tin chưa đọc vẫn còn,
   * nên mọi số đếm suy từ danh sách này (huy hiệu đầu trang, chip lọc, huy hiệu hàng) khớp với huy hiệu thanh bên mà
   * không cần lọc lại ở client; hội thoại 1–1 bị ẩn do chặn không có trong danh sách nên cũng không đóng góp số.
   */
  unread?: number;
  lastReadSeq?: number;
  lastMessageSeq?: number;
  lastMessage?: MessageView | null;
  enabled?: boolean;
  pinnedAt?: string | null;
  archivedAt?: string | null;
  muted?: boolean;
  createdBy?: string | null;
  canModerate?: boolean;
  canManage?: boolean;
}
export interface ConversationDetailView extends ConversationView {
  messages: MessageView[];
  hasMoreBefore?: boolean;
  memberReadMarkers?: { userId: string; lastReadSeq: number }[];
}

export type ConversationTreeRoleView = 'teaching' | 'learning' | 'staff';

export interface ConversationTreeGroupView {
  groupId: string;
  name: string;
  myRole: ConversationTreeRoleView;
  conversationId: string | null;
  unread: number;
  pinnedAt: string | null;
}

export interface ConversationTreeFreeGroupView {
  conversationId: string;
  name: string;
  myRole: ConversationTreeRoleView;
  unread: number;
  pinnedAt: string | null;
}

export interface ConversationTreeCourseView {
  courseId: string;
  name: string;
  myRole: ConversationTreeRoleView;
  conversationId: string | null;
  unread: number;
  pinnedAt: string | null;
  groups: ConversationTreeGroupView[];
}

export interface ConversationTreeClassView {
  classId: string;
  name: string;
  myRole: ConversationTreeRoleView;
  conversationId: string | null;
  unread: number;
  pinnedAt: string | null;
  courses: ConversationTreeCourseView[];
}

export interface ConversationGroupTreeView {
  classes: ConversationTreeClassView[];
  coursesOutsideClasses: ConversationTreeCourseView[];
  freeGroups: ConversationTreeFreeGroupView[];
  /**
   * Server tính theo ĐÚNG cổng tạo nhóm tự do của API cho tenant hiện
   * hành (chỉ quản trị viên hệ thống hoặc chủ trung tâm mới true) —
   * nút tạo nhóm hiện theo cờ này, không tự suy từ vai trên cây.
   */
  canCreateFreeGroup: boolean;
}

export type MemberPanelContext =
  | { kind: 'class'; id: string; name: string; parentName?: string }
  | { kind: 'course'; id: string; name: string; parentName?: string; subgroup?: boolean }
  | { kind: 'free'; name: string };

export interface MemberPanelEntry extends PersonSummary {
  role: 'teacher' | 'student' | 'admin' | 'member';
  relationship: ContactRelationship;
  canDirectMessage: boolean;
  isCreator?: boolean;
}

export type PeoplePickerContext =
  | { kind: 'contacts' }
  | { kind: 'class'; id: string; name: string }
  | { kind: 'course'; id: string; name: string };
