-- Cấu trúc nội dung theo khối của phiên bản câu hỏi (hợp đồng nội dung phiên bản 2 của feat câu hỏi): mỗi phần
-- văn bản của một phiên bản (thân câu, từng lựa chọn, lời giải, tiêu chí chấm) là một dãy khối có thứ tự, gồm
-- khối văn bản thường và khối công thức có định danh ổn định trong phiên bản. Khối công thức có đúng hai dạng ở
-- mức dữ kiện: có-nguồn (source-backed — nguồn lưu tách trong cột riêng tư math_source) và chỉ-có-ảnh
-- (render-only — không có nguồn ở phía nhận). Cờ nguồn gốc hệ thống và các trường của ảnh render do server
-- quản lý; cột image_blob_id trỏ sang blob của nền tệp ở migration V92 (khoá ngoại gắn tại đó vì bảng blob
-- được tạo sau). Dữ kiện cá nhân gắn với chủ sở hữu qua owner_sub như các bảng khác của feat câu hỏi; lớp Row
-- Level Security riêng nằm ở migration V91 phía PostgreSQL.

create table question_version_block (
    id uuid primary key,
    question_version_id uuid not null references question_version (id),
    owner_sub varchar(255) not null,
    part varchar(32) not null,
    choice_id varchar(255),
    position integer not null,
    kind varchar(16) not null,
    text_content text,
    formula_form varchar(16),
    math_source text,
    system_origin boolean not null default false,
    render_state varchar(16),
    image_blob_id uuid,
    image_width integer,
    image_height integer,
    baseline_offset integer,
    description text,
    source_revision integer not null default 0,
    created_at timestamp with time zone not null default current_timestamp,
    updated_at timestamp with time zone not null default current_timestamp,
    constraint question_version_block_part check (part in ('stem', 'choice', 'solution', 'rubric')),
    constraint question_version_block_kind check (kind in ('text', 'formula')),
    constraint question_version_block_form check (formula_form is null or formula_form in ('source-backed', 'render-only')),
    constraint question_version_block_render_state check (
        render_state is null or render_state in ('pending', 'ready', 'failed')),
    constraint question_version_block_shape check (
        (kind = 'text' and formula_form is null and math_source is null)
        or (kind = 'formula' and formula_form is not null
            and ((formula_form = 'source-backed' and math_source is not null)
                or (formula_form = 'render-only' and math_source is null))))
);
