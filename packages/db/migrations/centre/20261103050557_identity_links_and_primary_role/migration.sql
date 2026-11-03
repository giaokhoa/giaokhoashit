-- Nền danh tính theo trung tâm (issue #5) cho kho trung tâm:
--
-- 1. Bảng `identity_links`: liên kết danh tính Keycloak → user cục bộ
--    của kho. Khoá liên kết là cặp (issuer, sub) — `issuer` là issuer
--    cố định đã đăng ký trong sổ control plane của không gian sở hữu
--    kho, `subject` là claim `sub` của token. Email KHÔNG nằm trong
--    bảng này và không bao giờ làm khoá: đổi email không đổi liên kết,
--    trùng email ở hai issuer là hai liên kết khác nhau. Hai ràng buộc
--    duy nhất chốt bất biến của hợp đồng: một cặp (issuer, subject)
--    chỉ trỏ về đúng một user, và một user chỉ có tối đa một liên kết
--    cho mỗi issuer. `status` (`active` | `revoked`) và `version`
--    (tăng mỗi lần thu hồi/đồng bộ) là trường mà việc thu hồi và đồng
--    bộ danh tính ở các issue sau cần. Bảng mang `tenant_id` và đứng
--    trong khuôn RLS `tenant_isolation` fail-closed như
--    `tenant_memberships`: ngoài ngữ cảnh platform, một tenant chỉ
--    thấy liên kết của chính mình. Bảng này KHÔNG thay
--    `user_oauth_links` — bảng cũ phục vụ các nhà cung cấp OAuth cũ,
--    mang email NOT NULL và không phải danh tính Keycloak đã xác minh.
--
-- 2. `tenant_memberships` học thêm `primary_role`: vai chính DUY NHẤT
--    của membership đang hoạt động, thuộc tập đóng
--    admin | teacher | member (ánh xạ 1-1 sang realm role
--    tenant-admin | tenant-teacher | tenant-member). Một cột đơn nên
--    "hơn một vai chính" là không thể biểu diễn; ràng buộc CHECK chốt
--    tập giá trị VÀ cấm membership `active` thiếu vai (NULL chỉ còn
--    được ở membership chưa active). Các membership active lịch sử
--    thiếu vai được lấp `member` trong chính migration này trước khi
--    ràng buộc có hiệu lực; mọi đường ghi được hỗ trợ từ nay đều bắt
--    buộc ghi vai chính khi tạo/kích hoạt membership. Cột `version`
--    phục vụ việc thu hồi và đồng bộ về sau như ở bảng liên kết.
--    Bảng `identity_links` còn mang khoá ngoại `tenant_id` tới
--    `tenants.id` (bên cạnh khoá ngoại `user_id`): liên kết không
--    được trỏ tới tenant không tồn tại, và xoá tenant cuốn theo
--    liên kết của nó — nhưng tạo liên kết vẫn không sinh membership
--    nào (principal platform có liên kết mà không là thành viên).
--    Index dẫn đầu bằng `tenant_id` bao phủ FK một cột và tránh quét
--    toàn bảng khi tenant bị xoá theo `ON DELETE CASCADE`.
CREATE TABLE "identity_links" (
	"id" uuid PRIMARY KEY DEFAULT uuidv7(),
	"tenant_id" uuid NOT NULL,
	"user_id" uuid NOT NULL,
	"issuer" text NOT NULL,
	"subject" text NOT NULL,
	"status" text DEFAULT 'active' NOT NULL,
	"version" integer DEFAULT 1 NOT NULL,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL,
	"updated_at" timestamp with time zone DEFAULT now() NOT NULL,
	CONSTRAINT "identity_links_issuer_subject_unique" UNIQUE("issuer","subject"),
	CONSTRAINT "identity_links_user_issuer_unique" UNIQUE("user_id","issuer"),
	CONSTRAINT "identity_links_status_check" CHECK ("status" IN ('active', 'revoked'))
);
--> statement-breakpoint
ALTER TABLE "identity_links" ADD CONSTRAINT "identity_links_user_id_users_id_fkey" FOREIGN KEY ("user_id") REFERENCES "users"("id") ON DELETE CASCADE;
--> statement-breakpoint
ALTER TABLE "identity_links" ADD CONSTRAINT "identity_links_tenant_id_tenants_id_fkey" FOREIGN KEY ("tenant_id") REFERENCES "tenants"("id") ON DELETE CASCADE;
--> statement-breakpoint
CREATE INDEX "identity_links_tenant_id_idx" ON "identity_links" ("tenant_id");
--> statement-breakpoint
ALTER TABLE "identity_links" ENABLE ROW LEVEL SECURITY;
--> statement-breakpoint
ALTER TABLE "identity_links" FORCE ROW LEVEL SECURITY;
--> statement-breakpoint
CREATE POLICY "tenant_isolation" ON "identity_links" USING (current_setting('app.platform', true) = 'on' OR "tenant_id" = NULLIF(current_setting('app.tenant_id', true), '')::uuid) WITH CHECK (current_setting('app.platform', true) = 'on' OR "tenant_id" = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
--> statement-breakpoint
ALTER TABLE "tenant_memberships" ADD COLUMN "primary_role" text;
--> statement-breakpoint
-- Khôi phục vai tường minh mà @lms/enrol đã staging trên baseline.
-- tenant_config tồn tại từ baseline và chịu cùng RLS tenant_isolation;
-- chỉ giá trị thuộc tập đóng mới được chuyển sang membership.
UPDATE "tenant_memberships" AS tm
SET "primary_role" = tc."value" #>> '{}'
FROM "tenant_config" AS tc
WHERE tc."tenant_id" = tm."tenant_id"
  AND tc."key" = '__migration_tenant_membership_primary_role__:' || tm."user_id"::text
  AND tm."primary_role" IS NULL
  AND tc."value" #>> '{}' IN ('admin', 'teacher', 'member');
--> statement-breakpoint
-- Dọn mọi khoá staging sau khi đã chuyển vai; dòng staging không có
-- membership tương ứng cũng bị bỏ, không trở thành dữ liệu cấu hình lâu dài.
DELETE FROM "tenant_config"
WHERE left("key", length('__migration_tenant_membership_primary_role__:')) = '__migration_tenant_membership_primary_role__:';
--> statement-breakpoint
-- Lấp member chỉ cho membership active lịch sử không có vai staging.
-- Membership chưa active được giữ NULL; hai CHECK độc lập bên dưới
-- lần lượt khoá tập giá trị và buộc active phải có vai.
UPDATE "tenant_memberships" SET "primary_role" = 'member' WHERE "status" = 'active' AND "primary_role" IS NULL;
--> statement-breakpoint
ALTER TABLE "tenant_memberships" ADD CONSTRAINT "tenant_memberships_primary_role_value_check" CHECK ("primary_role" IS NULL OR "primary_role" IN ('admin', 'teacher', 'member'));
--> statement-breakpoint
ALTER TABLE "tenant_memberships" ADD CONSTRAINT "tenant_memberships_active_primary_role_check" CHECK ("status" <> 'active' OR "primary_role" IS NOT NULL);
--> statement-breakpoint
ALTER TABLE "tenant_memberships" ADD COLUMN "version" integer DEFAULT 1 NOT NULL;
