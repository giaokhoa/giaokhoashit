-- Khối tổ chức dạy học tối thiểu: course chỉ là nhóm tổ chức, teaching_class là thực thể mang danh sách học viên
-- và giáo viên phụ trách, class_member là dòng ghi danh của một học viên vào một lớp. Cả ba bảng phân vùng bằng
-- partition_key chứa định danh Organization, đúng khuôn của tenant_record; khoá ngoại ghép kèm partition_key để
-- một hàng không tham chiếu được sang hàng của trung tâm khác ngay ở tầng cơ sở dữ liệu.

create table course (
    id uuid primary key,
    partition_key varchar(255) not null,
    name varchar(255) not null,
    created_by_sub varchar(255) not null,
    created_at timestamp with time zone not null default current_timestamp,
    updated_at timestamp with time zone not null default current_timestamp,
    unique (id, partition_key)
);

create table teaching_class (
    id uuid primary key,
    partition_key varchar(255) not null,
    course_id uuid,
    name varchar(255) not null,
    teacher_sub varchar(255),
    created_by_sub varchar(255) not null,
    created_at timestamp with time zone not null default current_timestamp,
    updated_at timestamp with time zone not null default current_timestamp,
    unique (id, partition_key),
    foreign key (course_id, partition_key) references course (id, partition_key)
);

create table class_member (
    id uuid primary key,
    partition_key varchar(255) not null,
    class_id uuid not null,
    student_sub varchar(255) not null,
    enrolled_by_sub varchar(255) not null,
    removed_at timestamp with time zone,
    created_at timestamp with time zone not null default current_timestamp,
    updated_at timestamp with time zone not null default current_timestamp,
    unique (class_id, partition_key, student_sub),
    foreign key (class_id, partition_key) references teaching_class (id, partition_key)
);
