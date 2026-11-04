-- Môn học là dữ liệu tham chiếu của hệ thống: danh mục do Chương trình giáo dục phổ thông quy định, giống hệt nhau ở
-- mọi trung tâm và mọi môi trường, không thuộc về người dùng hay trung tâm nào — nên bảng không có khoá phân vùng,
-- không bật RLS. Role runtime đọc được bảng nhờ default privileges mà migration V3 đã đặt cho mọi bảng mới trong
-- schema public. Danh mục nạp bằng chính versioned migration này (Flyway xếp "reference data updates" vào đúng loại
-- việc của versioned migration): áp đúng một lần theo thứ tự version, checksum giữ cho nội dung không bị sửa lén;
-- cần sửa danh mục thì viết migration versioned mới trong dải V80–V89, không sửa lại file này.
--
-- Mô hình danh mục: mỗi môn ở mỗi cấp là một mục riêng vì Chương trình giáo dục phổ thông tách theo đúng cách đó —
-- Đạo đức (tiểu học), Giáo dục công dân (THCS) và Giáo dục kinh tế và pháp luật (THPT) là ba mục; Tiếng Việt (tiểu
-- học) tách khỏi Ngữ văn; Tin học và Công nghệ là một môn ghép ở tiểu học rồi tách thành Tin học và Công nghệ từ
-- THCS; Lịch sử và Địa lí gộp ở tiểu học và THCS, lên THPT tách Lịch sử (bắt buộc theo Thông tư 13/2022/TT-BGDĐT)
-- và Địa lí (lựa chọn); Hoạt động trải nghiệm ở tiểu học tách khỏi Hoạt động trải nghiệm, hướng nghiệp ở THCS/THPT;
-- Nghệ thuật là một mục ở tiểu học và THCS, lên THPT tách thành hai môn lựa chọn Âm nhạc và Mĩ thuật. Nội dung giáo
-- dục của địa phương là mục riêng từ THCS; ở tiểu học nội dung này tích hợp trong các môn học và Hoạt động trải
-- nghiệm nên không có mục riêng. Nguồn: Chương trình giáo dục phổ thông ban hành kèm Thông tư 32/2018/TT-BGDĐT
-- (phần Kế hoạch giáo dục và các bảng tổng hợp kế hoạch giáo dục từng cấp), sửa đổi bởi Thông tư 13/2022/TT-BGDĐT.
--
-- Mọi id đúc một lần dạng UUIDv7 và ghi literal tại đây để danh mục khớp tuyệt đối giữa các môi trường; lát này
-- không sinh id lúc chạy.

create table subject (
    id uuid primary key,
    slug varchar(120) not null unique,
    name varchar(255) not null,
    school_level varchar(20) not null
        check (school_level in ('PRIMARY', 'LOWER_SECONDARY', 'UPPER_SECONDARY')),
    grade_from smallint not null check (grade_from between 1 and 12),
    grade_to smallint not null check (grade_to between 1 and 12),
    category varchar(25) not null
        check (category in ('REQUIRED', 'ELECTIVE', 'OPTIONAL', 'EDUCATIONAL_ACTIVITY')),
    display_order integer not null,
    check (grade_from <= grade_to)
);

insert into subject (id, slug, name, school_level, grade_from, grade_to, category, display_order) values
    ('01a1a795-b218-7e0f-a494-79ab16a9e33d', 'tieng-viet-tieu-hoc', 'Tiếng Việt', 'PRIMARY', 1, 5, 'REQUIRED', 1),
    ('01a1a795-b219-7c62-9997-221e1e8020aa', 'toan-tieu-hoc', 'Toán', 'PRIMARY', 1, 5, 'REQUIRED', 2),
    ('01a1a795-b21a-782c-83ef-79fcb1b431a0', 'dao-duc-tieu-hoc', 'Đạo đức', 'PRIMARY', 1, 5, 'REQUIRED', 3),
    ('01a1a795-b21b-71f8-a0f7-c2bfc9f06be7', 'ngoai-ngu-1-tieu-hoc', 'Ngoại ngữ 1', 'PRIMARY', 3, 5, 'REQUIRED', 4),
    ('01a1a795-b21c-7a8f-b3ce-d38cf69609c8', 'tu-nhien-va-xa-hoi-tieu-hoc', 'Tự nhiên và Xã hội', 'PRIMARY', 1, 3, 'REQUIRED', 5),
    ('01a1a795-b21d-7d33-acea-36e45db78a05', 'lich-su-va-dia-li-tieu-hoc', 'Lịch sử và Địa lí', 'PRIMARY', 4, 5, 'REQUIRED', 6),
    ('01a1a795-b21e-7122-a507-f4441965c75a', 'khoa-hoc-tieu-hoc', 'Khoa học', 'PRIMARY', 4, 5, 'REQUIRED', 7),
    ('01a1a795-b21f-783a-97e7-48e5663509ae', 'tin-hoc-va-cong-nghe-tieu-hoc', 'Tin học và Công nghệ', 'PRIMARY', 3, 5, 'REQUIRED', 8),
    ('01a1a795-b220-7543-b644-7222b01230a9', 'giao-duc-the-chat-tieu-hoc', 'Giáo dục thể chất', 'PRIMARY', 1, 5, 'REQUIRED', 9),
    ('01a1a795-b221-788d-aadc-fd251032d34a', 'nghe-thuat-tieu-hoc', 'Nghệ thuật', 'PRIMARY', 1, 5, 'REQUIRED', 10),
    ('01a1a795-b222-7a73-a66e-0710dde3c2a9', 'hoat-dong-trai-nghiem-tieu-hoc', 'Hoạt động trải nghiệm', 'PRIMARY', 1, 5, 'EDUCATIONAL_ACTIVITY', 11),
    ('01a1a795-b223-7693-a93f-f5b51e15e10d', 'tieng-dan-toc-thieu-so-tieu-hoc', 'Tiếng dân tộc thiểu số', 'PRIMARY', 1, 5, 'OPTIONAL', 12),
    ('01a1a795-b224-7003-bff5-2a82ebc2a83e', 'ngoai-ngu-1-tieu-hoc-tu-chon', 'Ngoại ngữ 1', 'PRIMARY', 1, 2, 'OPTIONAL', 13);

insert into subject (id, slug, name, school_level, grade_from, grade_to, category, display_order) values
    ('01a1a795-b225-764f-acdb-967e74a0dee0', 'ngu-van-thcs', 'Ngữ văn', 'LOWER_SECONDARY', 6, 9, 'REQUIRED', 1),
    ('01a1a795-b226-71e9-a601-1d35ab2bc927', 'toan-thcs', 'Toán', 'LOWER_SECONDARY', 6, 9, 'REQUIRED', 2),
    ('01a1a795-b227-70f7-87c4-616becc4f5c1', 'ngoai-ngu-1-thcs', 'Ngoại ngữ 1', 'LOWER_SECONDARY', 6, 9, 'REQUIRED', 3),
    ('01a1a795-b228-7432-a681-ef0dbf2ceddb', 'giao-duc-cong-dan-thcs', 'Giáo dục công dân', 'LOWER_SECONDARY', 6, 9, 'REQUIRED', 4),
    ('01a1a795-b229-757f-b018-60ae68001d1a', 'lich-su-va-dia-li-thcs', 'Lịch sử và Địa lí', 'LOWER_SECONDARY', 6, 9, 'REQUIRED', 5),
    ('01a1a795-b22a-7371-8a7f-004ddcf56084', 'khoa-hoc-tu-nhien-thcs', 'Khoa học tự nhiên', 'LOWER_SECONDARY', 6, 9, 'REQUIRED', 6),
    ('01a1a795-b22b-73e2-9c5b-1127050c31a6', 'cong-nghe-thcs', 'Công nghệ', 'LOWER_SECONDARY', 6, 9, 'REQUIRED', 7),
    ('01a1a795-b22c-7962-920d-608bec086ee7', 'tin-hoc-thcs', 'Tin học', 'LOWER_SECONDARY', 6, 9, 'REQUIRED', 8),
    ('01a1a795-b22d-7b6e-87d3-b8a330eaf1ad', 'giao-duc-the-chat-thcs', 'Giáo dục thể chất', 'LOWER_SECONDARY', 6, 9, 'REQUIRED', 9),
    ('01a1a795-b22e-7ae8-a8fb-f7414aad4e11', 'nghe-thuat-thcs', 'Nghệ thuật', 'LOWER_SECONDARY', 6, 9, 'REQUIRED', 10),
    ('01a1a795-b22f-73da-8bc7-df5abe14840e', 'hoat-dong-trai-nghiem-huong-nghiep-thcs', 'Hoạt động trải nghiệm, hướng nghiệp', 'LOWER_SECONDARY', 6, 9, 'EDUCATIONAL_ACTIVITY', 11),
    ('01a1a795-b230-7c4d-b402-0de75604bca9', 'noi-dung-giao-duc-cua-dia-phuong-thcs', 'Nội dung giáo dục của địa phương', 'LOWER_SECONDARY', 6, 9, 'REQUIRED', 12),
    ('01a1a795-b231-7c9a-9e11-b1a0166dfcc1', 'tieng-dan-toc-thieu-so-thcs', 'Tiếng dân tộc thiểu số', 'LOWER_SECONDARY', 6, 9, 'OPTIONAL', 13),
    ('01a1a795-b232-7789-ae40-8f7a1f2dc9af', 'ngoai-ngu-2-thcs', 'Ngoại ngữ 2', 'LOWER_SECONDARY', 6, 9, 'OPTIONAL', 14);

insert into subject (id, slug, name, school_level, grade_from, grade_to, category, display_order) values
    ('01a1a795-b233-7625-9e5a-ef5ed1298ecb', 'ngu-van-thpt', 'Ngữ văn', 'UPPER_SECONDARY', 10, 12, 'REQUIRED', 1),
    ('01a1a795-b234-783a-9251-9edf5d812c97', 'toan-thpt', 'Toán', 'UPPER_SECONDARY', 10, 12, 'REQUIRED', 2),
    ('01a1a795-b235-721c-9bec-cf9517f4d497', 'ngoai-ngu-1-thpt', 'Ngoại ngữ 1', 'UPPER_SECONDARY', 10, 12, 'REQUIRED', 3),
    ('01a1a795-b236-7d1e-a31f-7e373eeda5b5', 'lich-su-thpt', 'Lịch sử', 'UPPER_SECONDARY', 10, 12, 'REQUIRED', 4),
    ('01a1a795-b237-77a0-9745-23f14f58aa6e', 'giao-duc-the-chat-thpt', 'Giáo dục thể chất', 'UPPER_SECONDARY', 10, 12, 'REQUIRED', 5),
    ('01a1a795-b238-794f-adca-b477116a152f', 'giao-duc-quoc-phong-va-an-ninh-thpt', 'Giáo dục quốc phòng và an ninh', 'UPPER_SECONDARY', 10, 12, 'REQUIRED', 6),
    ('01a1a795-b239-7813-b1a6-4d2080fe0ee4', 'hoat-dong-trai-nghiem-huong-nghiep-thpt', 'Hoạt động trải nghiệm, hướng nghiệp', 'UPPER_SECONDARY', 10, 12, 'EDUCATIONAL_ACTIVITY', 7),
    ('01a1a795-b23a-7500-9601-9752cd936017', 'noi-dung-giao-duc-cua-dia-phuong-thpt', 'Nội dung giáo dục của địa phương', 'UPPER_SECONDARY', 10, 12, 'REQUIRED', 8),
    ('01a1a795-b23b-79c6-97d8-84c129bb37f4', 'dia-li-thpt', 'Địa lí', 'UPPER_SECONDARY', 10, 12, 'ELECTIVE', 9),
    ('01a1a795-b23c-73f3-9bd7-2f0078f5146f', 'giao-duc-kinh-te-va-phap-luat-thpt', 'Giáo dục kinh tế và pháp luật', 'UPPER_SECONDARY', 10, 12, 'ELECTIVE', 10),
    ('01a1a795-b23d-767f-9c55-18c9e1df3903', 'vat-li-thpt', 'Vật lí', 'UPPER_SECONDARY', 10, 12, 'ELECTIVE', 11),
    ('01a1a795-b23e-7572-bf93-ea71ee6cc5ca', 'hoa-hoc-thpt', 'Hoá học', 'UPPER_SECONDARY', 10, 12, 'ELECTIVE', 12),
    ('01a1a795-b23f-70d4-9dd7-0490e37d7c1c', 'sinh-hoc-thpt', 'Sinh học', 'UPPER_SECONDARY', 10, 12, 'ELECTIVE', 13),
    ('01a1a795-b240-7fdc-a7bc-16f597434545', 'tin-hoc-thpt', 'Tin học', 'UPPER_SECONDARY', 10, 12, 'ELECTIVE', 14),
    ('01a1a795-b241-74e2-803e-506be226dbda', 'cong-nghe-thpt', 'Công nghệ', 'UPPER_SECONDARY', 10, 12, 'ELECTIVE', 15),
    ('01a1a795-b242-7df9-a8de-6e1111f4c86a', 'am-nhac-thpt', 'Âm nhạc', 'UPPER_SECONDARY', 10, 12, 'ELECTIVE', 16),
    ('01a1a795-b243-7626-8fca-21f5dc73f361', 'mi-thuat-thpt', 'Mĩ thuật', 'UPPER_SECONDARY', 10, 12, 'ELECTIVE', 17),
    ('01a1a795-b244-7d37-8f6c-5fbd8e9cc62c', 'tieng-dan-toc-thieu-so-thpt', 'Tiếng dân tộc thiểu số', 'UPPER_SECONDARY', 10, 12, 'OPTIONAL', 18),
    ('01a1a795-b245-70c6-b6bf-51b95cc5a06f', 'ngoai-ngu-2-thpt', 'Ngoại ngữ 2', 'UPPER_SECONDARY', 10, 12, 'OPTIONAL', 19);
