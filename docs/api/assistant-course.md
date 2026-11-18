# Assistant MVP cho đồ án

Đặt `ASSISTANT_GEMINI_API_KEY` và `ASSISTANT_VECTOR_MONGO_URI` để dùng [Gemini với corpus READY](assistant-ready-faq-live.md) trên mọi Spring profile. Mỗi request cần Bearer access JWT. Backend dùng JWT subject để xác định chủ hội thoại. Có thể bật hội thoại local với key trống bằng `vitaloom.assistant.course.enabled=true`, hoặc đặt thuộc tính đó thành `false` để tắt API hội thoại.

Các đường dẫn dưới đây tương đối với `/api/assistant/mvp/conversations`.

| Method và đường dẫn | Request | Kết quả |
| --- | --- | --- |
| `GET ?page=0` | 20 hội thoại mỗi trang | `{items: [{id, updatedAt}], hasMore}` |
| `POST /` | `{accepted: true}` | 201 và hội thoại mới. Quyền cá nhân mặc định tắt. |
| `GET /{id}` | — | Hội thoại đang còn hạn. |
| `POST /{id}/turns` | `{turnId, text, geminiAccepted?}`, UUID và chuỗi 1–300 ký tự | Hội thoại sau khi thêm lượt. Retry cùng UUID và nội dung trả lại hội thoại đã lưu. |
| `PUT /{id}/permission` | `{allowed: boolean}` | Hội thoại sau cập nhật. Chuyển từ bật sang tắt xóa toàn bộ lượt trong hội thoại. |
| `DELETE /{id}` | — | 204. |

Hội thoại trả `{id, updatedAt, personalAllowed, turns}`. Mỗi lượt có `{turnId, text, outcome, parts}`. Mỗi phần có `{status, text, source}`, với nguồn dạng `{id, version, title, route, from, to}`. Ngày ISO theo phạm vi của dữ liệu. Nguồn app-help không có ngày. Văn bản phản hồi là tiếng Việt.

401 cho JWT thiếu hoặc không hợp lệ, 403 cho tài khoản ngoài allowlist hoặc khi chưa chấp nhận lưu, 404 cho hội thoại ngoại lai/hết hạn, 409 cho UUID bị tái dùng với nội dung khác, đạt 50 lượt hoặc ghi đồng thời, 503 cho lỗi lưu trữ Assistant. Một lỗi đọc dữ liệu cá nhân trong hội thoại có trạng thái `DEPENDENCY_UNAVAILABLE` và phần hướng dẫn có sẵn vẫn được trả về. Các trạng thái còn lại gồm `ANSWERED`, `PARTIAL`, `PERMISSION_REQUIRED`, `NO_DATA`, `UNVERIFIED_DATA`, `INSUFFICIENT_EVIDENCE` và `SAFETY`.

MongoDB áp dụng TTL 7 ngày từ lúc tạo. API chặn ngay khi hết hạn, còn MongoDB xóa bản ghi theo lịch TTL của nó. Ghi theo revision để lượt đang xử lý không ghi đè thao tác xóa hoặc thay đổi quyền đã ghi thành công. Ngữ cảnh hỏi tiếp chỉ dùng loại dữ liệu của lượt ngay trước trong vòng 30 phút, rồi truy vấn lại. Lịch sử và dữ liệu cá nhân lưu và xử lý trên Backend local.

## Phạm vi dữ liệu

- KB nhỏ gồm ba hướng dẫn đã đối chiếu với route Giấc ngủ/Nước. Mục đích tra cứu dùng alias tiếng Việt và tiếng Anh, sau đó so khớp cosine trên vector từ khóa cục bộ. Liên kết nguồn do server định nghĩa. Atlas và mô hình embedding nằm ngoài cấu hình demo.
- Sleep MAIN đọc ngày đơn và Recent đọc cửa sổ 7 ngày qua dịch vụ chủ quản. Supplement về xuất bản insight và A04 để ngoài demo.
- Water đọc ngày hoặc tuần từ dữ liệu đã lưu, dùng `Asia/Ho_Chi_Minh`. Ngày không có bản ghi được ghi rõ, các ngày tương lai không xuất hiện trong danh sách. Phần Water có dữ liệu mang `PARTIAL` vì ngày ghi gốc, đồng bộ đầy đủ và lịch sử mục tiêu chưa được xác minh.
- [Assistant sandbox](assistant-sandbox.md) là đường chạy Gemini riêng với một fixture tổng hợp cố định.

## Thiết lập fixture cũ

Phần dưới ghi lại cấu hình thử nghiệm cũ. Luồng hội thoại READY hiện dùng [cấu hình Gemini và VectorDB](assistant-ready-faq-live.md).

Cờ boolean tùy chọn `geminiAccepted` xác nhận cho phép Gemini xử lý câu hỏi mẫu ở lượt hiện tại. Backend yêu cầu tài khoản sandbox đã cấu hình, cờ bật thử nghiệm và câu hỏi trùng khớp với fixture của máy chủ sau khi chuẩn hóa Unicode NFC, khoảng trắng hai đầu và chữ hoa/thường. Payload hiện có tiếp tục dùng luồng FAQ nội bộ và dịch vụ dữ liệu theo chủ sở hữu.

Profile mặc định hỗ trợ đường thử với `ASSISTANT_RAG_SANDBOX_ENABLED=true`, `ASSISTANT_SANDBOX_ACCOUNT_ID` và `ASSISTANT_GEMINI_API_KEY` ở Backend. Dùng Atlas Local biệt lập và collection `assistant_sandbox_faq`. Chỉ đặt `ASSISTANT_RAG_INGEST=true` khi cần ingest fixture. Backend dùng Gemini Embedding 2 (768 chiều), truy vấn index cosine có bộ lọc metadata và đối chiếu fixture hiện hành trên primary trước khi dựng prompt. Lượt thành công trích dẫn `app-help-sleep-journal@1` với route `/sleep/journal`. Khi tìm kiếm không khả dụng hoặc phản hồi provider không hợp lệ, Mobile hiển thị thông báo kèm câu trả lời fixture nội bộ với trạng thái `DEPENDENCY_UNAVAILABLE`.

Backend trả lượt đã lưu trước khi dispatch theo hội thoại và UUID lượt. Mỗi lần thử giữ claim 24 giờ trong phạm vi hội thoại. Claim đang chờ trả 409 để ứng dụng thử lại cùng UUID mà không gửi lại sang Gemini. Nếu hội thoại hết hạn hoặc đổi revision trong khi xử lý, Backend từ chối ghi câu trả lời cũ.

Sau khi được duyệt dùng sandbox, chạy `tools/assistant-course-smoke.ps1 -GeminiRag -KeepRunning` với khóa từ môi trường riêng. Xem [runbook local](../../../vitaloom-business-docs-and-tools/docs/development-workflow/tasks/assistant-course-mvp.md).

Khi chạy Backend để chat, đặt `ASSISTANT_VECTOR_MONGO_URI`, ví dụ `mongodb://127.0.0.1:27183/assistant_vector?directConnection=true&serverSelectionTimeoutMS=2000`, và dùng cùng model embedding với bootstrap. Tài khoản và quyền thử Gemini cấu hình theo phần sandbox ở trên.

Backend đọc `ASSISTANT_GEMINI_CHAT_MODEL` (mặc định `gemini-3.1-flash-lite`) và `ASSISTANT_GEMINI_EMBEDDING_MODEL` (mặc định `gemini-embedding-2`). Nếu cần chọn `gemini-embedding-001`, đặt biến embedding và ingest lại. Backend dùng định dạng theo model và lọc generation riêng để chỉ tìm trong các vector tạo từ cùng model. Khi Google trả 429, đường chat trả fallback và lần chạy smoke dừng nếu không xác minh được Gemini. Backend không đổi model hay tier tự động.

Sandbox nhận ba FAQ và câu diễn đạt lại đã duyệt trong [bản duyệt FAQ](../../../vitaloom-business-docs-and-tools/docs/development-workflow/tasks/assistant-v1-content-tool-review.md). Backend nhận diện câu cho phép trước, dùng câu chuẩn hoặc paraphrase thuộc bộ mẫu để truy vấn Atlas, rồi xác minh chunk và nguồn trên primary.

Mẫu T01 nhận câu `Nhật ký ngủ tổng hợp hôm nay ghi bao nhiêu phút?`. Để chạy mẫu cần tài khoản sandbox, xác nhận từng lượt, quyền đọc trong hội thoại và một bản ghi MAIN DURATION 423 phút tổng hợp hôm nay theo giờ Việt Nam, với cảm nhận để trống. Backend chỉ chấp nhận một lần gọi `getSleepRecords` với hai đầu khoảng ngày bằng ngày mẫu. Backend xác minh bản ghi theo JWT rồi chỉ gửi projection tổng hợp cố định. Kết quả gồm nguồn `synthetic-sleep-journal@1`. Tài khoản khác và dữ liệu ngoài mẫu tiếp tục theo luồng nội bộ.

## Local READY FAQ chat

See [Assistant READY FAQ with Gemini](assistant-ready-faq-live.md) for the two Backend settings and current authentication, consent and retrieval behavior. The READY response includes server-attributed FAQ IDs, titles and versions. The similarity threshold remains 0.75.
