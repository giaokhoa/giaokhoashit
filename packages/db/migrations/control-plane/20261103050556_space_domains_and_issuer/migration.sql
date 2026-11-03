-- Nền danh tính theo trung tâm (issue #5): sổ đăng ký control plane học
-- thêm hai dữ kiện mà chuỗi danh tính chuẩn (Host đã xác minh → tenant →
-- realm → issuer cố định → liên kết (issuer, sub)) cần ở tầng dữ liệu:
--
-- 1. Cột `issuer_url` trên `space_registry`: issuer OIDC cố định của
--    không gian — mỗi trung tâm một realm Keycloak, realm platform
--    `giaokhoa` thuộc về dòng đăng ký của không gian platform. Cột để
--    trống (NULL) cho tới khi realm được cấp phát và issuer được chốt
--    vào sổ; bộ phân giải domain/issuer chỉ chọn một không gian khi
--    issuer đã có mặt, không suy issuer từ bất kỳ nguồn nào khác.
--    Issuer khác NULL là duy nhất giữa các không gian (chỉ số duy
--    nhất riêng phần dưới đây): hai không gian không được chia sẻ
--    một issuer, vì issuer là định danh của đúng một realm.
--
-- 2. Bảng `space_domains`: ánh xạ domain đã xác minh → không gian.
--    `domain` là tên host dạng canonical (chữ thường, không cổng, không
--    dấu chấm cuối) và DUY NHẤT TOÀN HỆ (ràng buộc UNIQUE trên cột):
--    một domain không bao giờ trỏ về hai không gian. `kind` phân biệt
--    domain chính (`primary`, tối đa một cho mỗi không gian — chốt
--    bằng chỉ số duy nhất riêng phần) và domain phụ (`alias`, không
--    giới hạn số lượng); alias và domain chính của cùng một không gian
--    phân giải về cùng một issuer vì issuer nằm trên dòng đăng ký của
--    không gian, không nằm trên dòng domain. `status` là trạng thái
--    cấp phát của domain: `pending` (đã đăng ký, chưa xác minh —
--    không phân giải), `verified` (đã xác minh quyền sở hữu — chỉ
--    trạng thái này được bộ phân giải chọn), `disabled` (đã tắt —
--    không phân giải). `verified_at` ghi mốc xác minh gần nhất.
ALTER TABLE "space_registry" ADD COLUMN "issuer_url" text;
--> statement-breakpoint
-- Một issuer chỉ thuộc về đúng một không gian: chỉ số duy nhất riêng
-- phần trên các giá trị khác NULL (NULL là "realm chưa cấp phát", vẫn
-- được nhiều dòng cùng mang). Tầng service còn kiểm tra trước để trả
-- lỗi xung đột 409 rõ ràng; chỉ số này là chốt chặn cho cuộc đua ghi
-- đồng thời mà kiểm tra ở tầng ứng dụng không bắt được.
CREATE UNIQUE INDEX "space_registry_issuer_url_unique" ON "space_registry" ("issuer_url") WHERE "issuer_url" IS NOT NULL;
--> statement-breakpoint
CREATE TABLE "space_domains" (
	"id" uuid PRIMARY KEY DEFAULT uuidv7(),
	"space_id" uuid NOT NULL,
	"domain" text NOT NULL,
	"kind" text DEFAULT 'alias' NOT NULL,
	"status" text DEFAULT 'pending' NOT NULL,
	"verified_at" timestamp with time zone,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL,
	"updated_at" timestamp with time zone DEFAULT now() NOT NULL,
	CONSTRAINT "space_domains_domain_unique" UNIQUE("domain"),
	CONSTRAINT "space_domains_kind_check" CHECK ("kind" IN ('primary', 'alias')),
	CONSTRAINT "space_domains_status_check" CHECK ("status" IN ('pending', 'verified', 'disabled'))
);
--> statement-breakpoint
ALTER TABLE "space_domains" ADD CONSTRAINT "space_domains_space_id_space_registry_id_fkey" FOREIGN KEY ("space_id") REFERENCES "space_registry"("id") ON DELETE RESTRICT;
--> statement-breakpoint
CREATE UNIQUE INDEX "space_domains_one_primary_per_space" ON "space_domains" ("space_id") WHERE "kind" = 'primary';
