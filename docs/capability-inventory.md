# Kiểm kê capability và nhóm resource

Kiểm kê toàn bộ capability của engine quyết quyền tại head. Nguồn
đếm là sổ đăng ký trong mã:
`apps/api/src/modules/access/domain/capabilities.ts`
(`CAPABILITY_DEFINITIONS`), đối chiếu với mọi điểm gác trong
`apps/api/src` và `apps/worker/src`.

## 1. Kết quả đếm

| Đại lượng | Số đếm tại head | Đối chiếu |
| --- | --- | --- |
| Capability đã đăng ký | **124** | Khớp con số trong đặc tả (124) — không có diff |
| Nhóm resource (diện `area` — phần trước dấu `:` của tên capability) | **38** | Khớp con số trong đặc tả (38) — không có diff |
| Tên capability trùng lặp | 0 | 124 tên phân biệt |
| Component khai báo trong sổ đăng ký | 37 | Khác số nhóm resource: `core_course` gánh hai diện `course` và `category`; `core_message` gánh `site` (một phần) và `moderation` — nhóm resource tính theo diện `area`, không tính theo component |
| Role mặc định được seed | 7 | `manager`, `coursecreator`, `editingteacher`, `teacher`, `student`, `guest`, `user` (archetype kiểu Moodle) |
| Capability đã đăng ký nhưng **chưa có điểm gác nào trong mã** | 6 | Xem mục 5 |

Số capability theo nhóm resource: `site` 3, `tenant` 2, `user` 5,
`role` 3, `course` 8, `files` 2, `customfield` 1, `category` 1,
`class` 8, `group` 1, `cohort` 2, `enrol` 1, `completion` 2,
`grade` 4, `guardian` 1, `question` 7, `matrix` 6, `exam` 7,
`quiz` 8, `assign` 5, `curriculum` 2, `moderation` 1, `calendar` 4,
`badges` 4, `competency` 5, `tag` 2, `comment` 3, `rating` 3,
`notes` 3, `search` 2, `webservice` 2, `backup` 2, `analytics` 2,
`log` 1, `reportbuilder` 2, `contentbank` 4, `payment` 3,
`privacy` 2. Tổng: 124.

## 2. Loại resource và bản thể dữ liệu

Capability luôn được xét **tại một context**; loại resource của
capability suy từ cấp context khai báo trong sổ đăng ký và bản thể
mà context đó neo vào:

| Cấp context | Giá trị | Bản thể neo (instance) | Ghi chú |
| --- | --- | --- | --- |
| Hệ thống | 10 | — (context gốc duy nhất) | Quyền nền platform/site |
| Tenant | 20 | `tenants` | Mỗi tenant đúng một context tenant; neo của cổng membership |
| User | 30 | `users` | Context của một người dùng (hồ sơ, kế hoạch học tập, giám hộ) |
| Danh mục khoá | 40 | Danh mục khoá học | Cha của context khoá |
| Lớp | 45 | Lớp học | Lớp là thực thể cha hạng nhất; khoá nằm trong lớp |
| Khoá | 50 | Khoá học | Cấp khai báo phổ biến nhất (ngân hàng, điểm, tệp, hoạt động…) |
| Module | 70 | Hoạt động trong khoá (quiz, bài tập…) | Capability của hoạt động neo tại context module |
| Block | 80 | Block | Khai trong hằng số cấp; hiện chưa có capability nào khai cấp 80 |

Bản thể DB của tầng quyền (ba kho của `packages/db`, truy cập qua
repository `access` của `@lms/db`): bảng `capabilities` (đồng bộ từ
sổ đăng ký trong mã), `context` (cây context, path vật chất hoá),
`role`, `role_assignments` (gán vai tại context), `role_capabilities`
(ghi đè theo vai + capability + context, gồm cả dòng seed mặc định
theo archetype tại context hệ thống). Dữ kiện cổng tenant đọc thêm
`tenants` và `tenant_memberships`.

Phân biệt loại resource trong kiểm kê: **resource nghiệp vụ theo
context** (khoá, lớp, module, bank — quyền tính trên cây context)
khác **resource hệ thống** (cấu hình site, webservice, backup… —
quyền tại context hệ thống/tenant), và khác **scope OAuth của MCP**
(`lms.questions.*`, `lms.banks.contribute` — không phải capability;
mỗi tool MCP vẫn kiểm capability sau khi qua scope).

## 3. Cách đọc bảng kiểm kê (Phụ lục A)

- **Loại**: `read`/`write` khai trong sổ đăng ký (`capType`).
- **Cấp**: cấp context khai báo (`contextLevel`).
- **Archetype ALLOW mặc định**: các archetype role được seed
  `CAP_ALLOW` tại context hệ thống (`ALL_STAFF` trong mã = manager,
  coursecreator, editingteacher, teacher). Đây là mặc định khởi
  điểm, không phải kết quả quyết quyền cuối: ghi đè tại context con
  và gán vai thật quyết định kết quả cuối.
- **Điểm gác trong mã**: các file (ngoài sổ đăng ký và file spec)
  nhắc tới capability — gồm cổng controller (decorator
  `@RequireCapability`, ghi chú "cổng controller") và các điểm kiểm
  tra ở tầng service qua `PermissionEvaluator`
  (`hasCapability`/`requireCapability` và các biến thể gán vai
  thật). Phần lớn capability được gác ở **tầng service tại context
  của đối tượng nghiệp vụ**, không phải ở controller: chỉ 41 điểm
  decorator tồn tại trên toàn API, tập trung ở `access`,
  `users`, `site-config`, `custom-fields`, `search`, `auth`.

## 4. Các điểm kiểm tra đặc thù

- **Danh sách và lọc từng đối tượng:** evaluator có đường phân giải
  theo lô `resolveCapabilitiesForContexts` phục vụ ảnh chụp quyền
  (`UsersService.getMyAccess`) với bất biến tương đương
  `hasCapability` từng cặp; các danh sách nghiệp vụ (ngân hàng,
  khoá, lớp) lọc theo capability tại context của từng đối tượng/bank
  trong service tương ứng, không chỉ gác ở route.
- **Tệp:** `files:view`/`files:upload` được kiểm tại context khoá
  trong tầng files; nội dung ngân hàng hệ thống đi qua kho riêng
  (`system-bank`) với cùng engine.
- **Batch:** các đường ghi theo lô (vd nhập câu hỏi hàng loạt qua
  MCP `create_questions_batch`, duyệt đóng góp ngân hàng) kiểm
  capability tại context của bank đích cho cả lô và từng đích ghi.
- **Realtime:** gateway nhắn tin xác thực socket bằng phiên đã
  thiết lập (`messaging-socket-auth`) và các thao tác trong phòng
  vẫn qua service có gác capability (vd `moderation:manage` cho xoá
  cứng/gỡ hàng loạt); kênh thông báo phụ huynh loại trừ sự kiện điểm
  ở tầng digest.
- **Worker:** worker **không tự quyết capability**: các processor
  nền chạy trên vết actor đã được API gác khi ghi outbox/job (điển
  hình: quyết toán thanh toán ở
  `apps/worker/src/worker-payment-settlement.service.ts` ghi rõ
  không kiểm lại capability của actor, tin vào cổng `enrol:manage`
  phía API). Đây là quy ước hiện tại được kiểm kê nguyên trạng: kiểm
  tra nghiệp vụ nằm trong giao dịch ghi ở phía API.

## 5. Capability đã đăng ký nhưng chưa có điểm gác

Sáu capability dưới đây chỉ tồn tại trong sổ đăng ký, **không file
nào trong `apps/api`/`apps/worker`/`apps/web` kiểm tra chúng** tại
head. Chúng vẫn được seed, vẫn xuất hiện trong ảnh chụp quyền và
vẫn được tính khi phân giải quyền — nhưng không cổng nghiệp vụ nào
dùng chúng làm điều kiện:

| Capability | Loại | Cấp | Component |
| --- | --- | --- | --- |
| `tag:manage` | write | 10 | `core_tag` |
| `search:use` | read | 10 | `core_search` |
| `webservice:createtoken` | write | 10 | `core_webservice` |
| `reportbuilder:view` | read | 10 | `core_reportbuilder` |
| `payment:pay` | write | 10 | `core_payment` |
| `privacy:request` | write | 30 | `core_privacy` |

Ghi nhận để các việc sau quyết định (gắn điểm gác thật hoặc bỏ
khỏi sổ đăng ký kèm cập nhật spec đếm); lượt kiểm kê này **không
đổi tên, không xoá** capability nào.

## Phụ lục A — Bảng kiểm kê 124 capability

| # | Capability | Loại | Cấp | Component | Archetype ALLOW mặc định | Điểm gác trong mã |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | `site:config` | write | 10 | core | manager | access/access.controller<br>site-config/site-config.controller · cổng controller |
| 2 | `tenant:manage` | write | 20 | core_tenant | manager | classes/classes.service<br>control-plane/control-plane.service<br>messaging/messaging-conversations.service<br>questions/question-banks.service<br>system-bank/system-bank.service<br>tenants/tenant-config.service<br>tenants/tenants.service |
| 3 | `tenant:viewall` | read | 20 | core_tenant | manager | tenants/tenant-config.service<br>tenants/tenants.service |
| 4 | `site:viewuseridentity` | read | 10 | core | manager, editingteacher, teacher | users/users.service |
| 5 | `user:create` | write | 10 | core_user | manager | users/users.controller · cổng controller |
| 6 | `user:update` | write | 10 | core_user | manager | auth/auth.controller<br>search/search.service<br>users/users.controller<br>users/users.service · cổng controller |
| 7 | `user:delete` | write | 10 | core_user | manager | users/users.controller · cổng controller |
| 8 | `user:viewdetails` | read | 30 | core_user | manager, coursecreator, editingteacher, teacher, user | search/search.service<br>users/users.controller · cổng controller |
| 9 | `user:loginas` | write | 10 | core_user | manager | auth/auth.service |
| 10 | `role:manage` | write | 10 | core_role | manager | access/access.controller · cổng controller |
| 11 | `role:assign` | write | 10 | core_role | manager | access/access.controller · cổng controller |
| 12 | `role:override` | write | 10 | core_role | manager | access/access.controller · cổng controller |
| 13 | `course:view` | read | 50 | core_course | manager, coursecreator, editingteacher, teacher, student, user, guest | calendar/calendar.service<br>courses/courses.service<br>integrations/integrations.service<br>reports/reports.service<br>search/search.service |
| 14 | `files:view` | read | 50 | core_files | manager, coursecreator, editingteacher, teacher, student | files/files.service<br>questions/question-banks.service |
| 15 | `files:upload` | write | 50 | core_files | manager, coursecreator, editingteacher, teacher, student | files/files.service<br>questions/question-banks.service |
| 16 | `customfield:manage` | write | 10 | core_customfield | manager | custom-fields/custom-fields.controller · cổng controller |
| 17 | `category:manage` | write | 40 | core_course | manager | courses/courses.service |
| 18 | `course:create` | write | 40 | core_course | manager, coursecreator | classes/classes.service<br>courses/courses.service |
| 19 | `course:update` | write | 50 | core_course | manager, coursecreator, editingteacher | books/books.service<br>classes/classes.service<br>courses/courses.service<br>curriculum/curriculum.service |
| 20 | `course:delete` | write | 50 | core_course | manager | courses/courses.service |
| 21 | `course:viewhiddencourses` | read | 50 | core_course | manager, coursecreator, editingteacher, teacher | courses/courses.service |
| 22 | `course:viewhiddenactivities` | read | 50 | core_course | manager, editingteacher, teacher | courses/courses.service |
| 23 | `course:manageactivities` | write | 50 | core_course | manager, editingteacher | classes/classes.service<br>content/content.service<br>courses/courses.service |
| 24 | `course:managegroups` | write | 50 | core_group | manager, editingteacher | groups/groups.service<br>messaging/messaging-conversations.service |
| 25 | `class:create` | write | 40 | core_class | manager, coursecreator, editingteacher | classes/classes.service |
| 26 | `class:view` | read | 45 | core_class | manager | classes/classes.service |
| 27 | `class:update` | write | 45 | core_class | manager | classes/classes.service |
| 28 | `class:delete` | write | 45 | core_class | manager | classes/classes.service |
| 29 | `class:manageroster` | write | 45 | core_class | manager | classes/classes.service |
| 30 | `class:assignwork` | write | 45 | core_class | manager | classes/classes.service |
| 31 | `class:grade` | write | 45 | core_class | manager | classes/classes.service |
| 32 | `class:share` | write | 45 | core_class | manager | classes/classes.service |
| 33 | `group:accessallgroups` | read | 50 | core_group | manager, coursecreator, editingteacher, teacher | calendar/calendar.service<br>groups/groups.service |
| 34 | `cohort:manage` | write | 10 | core_cohort | manager | classes/classes.service<br>content-bank/content-bank.service<br>groups/groups.service |
| 35 | `cohort:view` | read | 10 | core_cohort | manager, coursecreator, editingteacher, teacher | classes/classes.service<br>groups/groups.service |
| 36 | `enrol:manage` | write | 50 | core_enrol | manager, editingteacher | enrol/enrol.service |
| 37 | `completion:manage` | write | 50 | core_completion | manager, editingteacher | completion/completion.service |
| 38 | `completion:markothers` | write | 50 | core_completion | manager, editingteacher, teacher | completion/completion.service |
| 39 | `grade:manage` | write | 50 | core_grades | manager, editingteacher | grades/grades.service |
| 40 | `grade:edit` | write | 50 | core_grades | manager, editingteacher, teacher | classes/classes.service<br>grades/grades.service<br>integrations/integrations.service |
| 41 | `grade:viewall` | read | 50 | core_grades | manager, editingteacher, teacher | classes/classes.service<br>dashboard/dashboard.service<br>grades/grades.service<br>reports/reports.service |
| 42 | `grade:publish` | write | 50 | core_grades | manager, editingteacher, teacher | grades/grades.service |
| 43 | `guardian:manage` | write | 30 | core_guardian | manager, editingteacher, teacher | guardians/guardians.service |
| 44 | `question:managecategory` | write | 50 | core_question | manager, editingteacher | questions/question-banks.service<br>questions/questions.service |
| 45 | `question:add` | write | 50 | core_question | manager, editingteacher, teacher | questions/question-banks.service<br>questions/questions.service<br>system-bank/system-bank.service |
| 46 | `question:editall` | write | 50 | core_question | manager, editingteacher | questions/question-banks.service<br>questions/questions.service |
| 47 | `question:useall` | read | 50 | core_question | manager, editingteacher, teacher | questions/question-banks.service<br>questions/questions.service<br>quiz/quiz-shared.service<br>system-bank/system-bank.service |
| 48 | `question:copy` | read | 50 | core_question | manager, editingteacher, teacher | questions/question-banks.service |
| 49 | `question:share` | write | 50 | core_question | manager, editingteacher, teacher | questions/question-banks.service |
| 50 | `question:moderate` | write | 50 | core_question | manager, editingteacher | questions/question-banks.service |
| 51 | `matrix:use` | read | 50 | core_matrix | manager, coursecreator, editingteacher, teacher | questions/question-banks.service<br>quiz/matrix-banks.service<br>quiz/quiz-shared.service |
| 52 | `matrix:add` | write | 50 | core_matrix | manager, coursecreator, editingteacher, teacher | questions/question-banks.service<br>quiz/matrix-banks.service<br>quiz/quiz-shared.service |
| 53 | `matrix:edit` | write | 50 | core_matrix | manager, coursecreator, editingteacher, teacher | questions/question-banks.service<br>quiz/matrix-banks.service<br>quiz/quiz-shared.service |
| 54 | `matrix:copy` | read | 50 | core_matrix | manager, editingteacher, teacher | questions/question-banks.service<br>quiz/matrix-banks.service |
| 55 | `matrix:share` | write | 50 | core_matrix | manager, editingteacher, teacher | questions/question-banks.service<br>quiz/matrix-banks.service |
| 56 | `matrix:moderate` | write | 50 | core_matrix | manager, editingteacher | questions/question-banks.service<br>quiz/matrix-banks.service |
| 57 | `exam:use` | read | 50 | core_exam | manager, editingteacher | exams/exams.service |
| 58 | `exam:add` | write | 50 | core_exam | manager, editingteacher | exams/exams.service |
| 59 | `exam:edit` | write | 50 | core_exam | manager, editingteacher | exams/exams.service |
| 60 | `exam:copy` | read | 50 | core_exam | manager, editingteacher | exams/exams.service |
| 61 | `exam:share` | write | 50 | core_exam | manager, editingteacher | exams/exams.service |
| 62 | `exam:moderate` | write | 50 | core_exam | manager, editingteacher | exams/exams.service |
| 63 | `exam:deploy` | write | 50 | core_exam | manager, editingteacher | exams/exams.service |
| 64 | `quiz:view` | read | 70 | mod_quiz | manager, editingteacher, teacher, student | classes/classes.service<br>quiz/quiz-shared.service |
| 65 | `quiz:attempt` | write | 70 | mod_quiz | student | paper-grading/paper-grading.service<br>quiz/quiz-shared.service |
| 66 | `quiz:manage` | write | 70 | mod_quiz | manager, editingteacher | quiz/quiz-shared.service |
| 67 | `quiz:grade` | write | 70 | mod_quiz | manager, editingteacher, teacher | classes/classes.service<br>quiz/quiz-shared.service |
| 68 | `quiz:gradepaper` | write | 70 | mod_quiz | manager, editingteacher, teacher | paper-grading/paper-grading.service<br>quiz/quiz-shared.service |
| 69 | `quiz:viewreports` | read | 70 | mod_quiz | manager, editingteacher, teacher | classes/classes.service<br>quiz/quiz-shared.service |
| 70 | `quiz:preview` | write | 70 | mod_quiz | manager, editingteacher, teacher | quiz/quiz-shared.service |
| 71 | `quiz:manageoverrides` | write | 70 | mod_quiz | manager, editingteacher | quiz/quiz-shared.service |
| 72 | `assign:view` | read | 70 | mod_assign | manager, editingteacher, teacher, student | assignments/assignments.service |
| 73 | `assign:submit` | write | 70 | mod_assign | student | assignments/assignments.service |
| 74 | `assign:manage` | write | 70 | mod_assign | manager, editingteacher | assignments/assignments.service |
| 75 | `assign:grade` | write | 70 | mod_assign | manager, editingteacher, teacher | assignments/assignments.service<br>classes/classes.service |
| 76 | `assign:viewallsubmissions` | read | 70 | mod_assign | manager, editingteacher, teacher | assignments/assignments.service<br>classes/classes.service |
| 77 | `curriculum:view` | read | 10 | core_curriculum | manager, coursecreator, editingteacher, teacher | curriculum/curriculum.service |
| 78 | `curriculum:manage` | write | 10 | core_curriculum | manager | curriculum/curriculum.service<br>system-bank/system-bank.service |
| 79 | `site:sendmessage` | write | 30 | core_message | manager, coursecreator, editingteacher, teacher, student, user | messaging/messaging-messages.service |
| 80 | `moderation:manage` | write | 50 | core_message | manager, editingteacher, teacher | messaging/messaging-messages.service |
| 81 | `calendar:manageentries` | write | 50 | core_calendar | manager, coursecreator, editingteacher, teacher | calendar/calendar.service |
| 82 | `calendar:managegroupentries` | write | 50 | core_calendar | manager, editingteacher | calendar/calendar.service |
| 83 | `calendar:manageownentries` | write | 30 | core_calendar | manager, coursecreator, editingteacher, teacher, student, user | calendar/calendar.service |
| 84 | `calendar:managesubscriptions` | write | 10 | core_calendar | manager | calendar/calendar.service |
| 85 | `badges:createbadge` | write | 50 | core_badges | manager, coursecreator, editingteacher | badges/badges.service |
| 86 | `badges:configurecriteria` | write | 50 | core_badges | manager, editingteacher | badges/badges.service |
| 87 | `badges:awardbadge` | write | 50 | core_badges | manager, editingteacher, teacher | badges/badges.service |
| 88 | `badges:revokebadge` | write | 50 | core_badges | manager | badges/badges.service |
| 89 | `competency:competencymanage` | write | 10 | core_competency | manager | competencies/competencies.service |
| 90 | `competency:planmanage` | write | 30 | core_competency | manager, user | competencies/competencies.service |
| 91 | `competency:planview` | read | 30 | core_competency | manager, coursecreator, editingteacher, teacher, user | competencies/competencies.service |
| 92 | `competency:usercompetencyreview` | write | 10 | core_competency | manager, editingteacher, teacher | competencies/competencies.service |
| 93 | `competency:coursecompetencymanage` | write | 50 | core_competency | manager, editingteacher | competencies/competencies.service |
| 94 | `tag:edit` | write | 50 | core_tag | manager, coursecreator, editingteacher, teacher, student, user | social/tags.service |
| 95 | `tag:manage` | write | 10 | core_tag | manager |  |
| 96 | `comment:view` | read | 50 | core_comment | manager, coursecreator, editingteacher, teacher, student, user | social/comments.service |
| 97 | `comment:post` | write | 50 | core_comment | manager, coursecreator, editingteacher, teacher, student, user | social/comments.service |
| 98 | `comment:delete` | write | 50 | core_comment | manager, editingteacher, teacher | social/comments.service |
| 99 | `rating:view` | read | 50 | core_rating | manager, coursecreator, editingteacher, teacher, student, user | social/ratings.service |
| 100 | `rating:viewall` | read | 50 | core_rating | manager, editingteacher, teacher | social/ratings.service |
| 101 | `rating:rate` | write | 50 | core_rating | manager, coursecreator, editingteacher, teacher, student, user | social/ratings.service |
| 102 | `notes:create` | write | 50 | core_notes | manager, editingteacher, teacher | social/notes.service |
| 103 | `notes:view` | read | 50 | core_notes | manager, editingteacher, teacher | social/notes.service |
| 104 | `notes:manage` | write | 50 | core_notes | manager | social/notes.service |
| 105 | `search:use` | read | 10 | core_search | manager, coursecreator, editingteacher, teacher, student, user |  |
| 106 | `search:manage` | write | 10 | core_search | manager | search/search.controller · cổng controller |
| 107 | `webservice:manage` | write | 10 | core_webservice | manager | integrations/integrations.service |
| 108 | `webservice:createtoken` | write | 10 | core_webservice | manager |  |
| 109 | `backup:backupcourse` | write | 50 | core_backup | manager, editingteacher | backup/backup.service |
| 110 | `backup:restorecourse` | write | 50 | core_backup | manager, coursecreator, editingteacher | backup/backup.service |
| 111 | `analytics:viewinsights` | read | 50 | core_analytics | manager, editingteacher, teacher | analytics/analytics.service |
| 112 | `analytics:manage` | write | 10 | core_analytics | manager | analytics/analytics.service |
| 113 | `log:view` | read | 50 | report_log | manager, editingteacher, teacher | event-log/event-log.service |
| 114 | `reportbuilder:manage` | write | 10 | core_reportbuilder | manager, editingteacher, teacher | reports/reports.service |
| 115 | `reportbuilder:view` | read | 10 | core_reportbuilder | manager, coursecreator, editingteacher, teacher, student, user |  |
| 116 | `contentbank:viewcontent` | read | 50 | core_contentbank | manager, coursecreator, editingteacher, teacher, student | content-bank/content-bank.service<br>search/search.service |
| 117 | `contentbank:upload` | write | 50 | core_contentbank | manager, editingteacher, teacher | content-bank/content-bank.service |
| 118 | `contentbank:editcontent` | write | 50 | core_contentbank | manager, editingteacher | content-bank/content-bank.service |
| 119 | `contentbank:reviewcontent` | write | 10 | core_contentbank | manager, editingteacher | content-bank/content-bank.service |
| 120 | `payment:manageaccounts` | write | 10 | core_payment | manager | payments/payments.service |
| 121 | `payment:managecoupons` | write | 10 | core_payment | manager | payments/payments.service |
| 122 | `payment:pay` | write | 10 | core_payment | manager, coursecreator, editingteacher, teacher, student, user |  |
| 123 | `privacy:request` | write | 30 | core_privacy | manager, coursecreator, editingteacher, teacher, student, user |  |
| 124 | `privacy:manage` | write | 10 | core_privacy | manager | privacy/privacy.service |
