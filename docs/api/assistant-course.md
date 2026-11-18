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

Để dùng Vector Search riêng với Mongo của ứng dụng, chạy:

```powershell
.\mvnw.cmd -DskipTests package
.\tools\assistant-vector-bootstrap.ps1
```

Chạy lại bootstrap sau khi volume vector bị xóa. Script chuẩn bị mạng dùng chung, khởi động Compose VectorDB, chờ health check rồi khởi động Compose chính. Sau đó script tạo index nếu thiếu và dùng Backend với khóa Gemini trong môi trường hoặc `.env` để ingest. Script chỉ báo PASS sau khi cả ba FAQ đều có vector 768 chiều và tìm được qua index. Tiến trình Backend dùng để bootstrap tự dừng sau khi kiểm tra.

Nếu chuyển từ Compose gộp, làm theo [hướng dẫn giữ volume cũ](../../README.md#2-start-local-infrastructure) trước khi chạy bootstrap. Reset volume Compose chính vẫn giữ dữ liệu vector.

Khi chạy Backend để chat, đặt `ASSISTANT_VECTOR_MONGO_URI`, ví dụ `mongodb://127.0.0.1:27183/assistant_vector?directConnection=true&serverSelectionTimeoutMS=2000`, và dùng cùng model embedding với bootstrap. Tài khoản và quyền thử Gemini cấu hình theo phần sandbox ở trên.

Backend đọc `ASSISTANT_GEMINI_CHAT_MODEL` (mặc định `gemini-3.1-flash-lite`) và `ASSISTANT_GEMINI_EMBEDDING_MODEL` (mặc định `gemini-embedding-2`). Nếu cần chọn `gemini-embedding-001`, đặt biến embedding và ingest lại. Backend dùng định dạng theo model và lọc generation riêng để chỉ tìm trong các vector tạo từ cùng model. Khi Google trả 429, đường chat trả fallback và lần chạy smoke dừng nếu không xác minh được Gemini. Backend không đổi model hay tier tự động.

Sandbox nhận ba FAQ và câu diễn đạt lại đã duyệt trong [bản duyệt FAQ](../../../vitaloom-business-docs-and-tools/docs/development-workflow/tasks/assistant-v1-content-tool-review.md). Backend nhận diện câu cho phép trước, dùng câu chuẩn hoặc paraphrase thuộc bộ mẫu để truy vấn Atlas, rồi xác minh chunk và nguồn trên primary.

Mẫu T01 nhận câu `Nhật ký ngủ tổng hợp hôm nay ghi bao nhiêu phút?`. Để chạy mẫu cần tài khoản sandbox, xác nhận từng lượt, quyền đọc trong hội thoại và một bản ghi MAIN DURATION 423 phút tổng hợp hôm nay theo giờ Việt Nam, với cảm nhận để trống. Backend chỉ chấp nhận một lần gọi `getSleepRecords` với hai đầu khoảng ngày bằng ngày mẫu. Backend xác minh bản ghi theo JWT rồi chỉ gửi projection tổng hợp cố định. Kết quả gồm nguồn `synthetic-sleep-journal@1`. Tài khoản khác và dữ liệu ngoài mẫu tiếp tục theo luồng nội bộ.

## Local READY FAQ chat

See [Assistant READY FAQ with Gemini](assistant-ready-faq-live.md) for the two Backend settings and current authentication, consent and retrieval behavior. The READY response includes server-attributed FAQ IDs, titles and versions. The similarity threshold remains 0.75.

## Ghi nhật ký bằng giọng nói

`POST /api/assistant/mvp/voice-turns` nhận Bearer access JWT và multipart form gồm `audio`, `turnId` (UUID), `consentAccepted=true`, `consentVersion=assemblyai-us-v1`. Audio tối đa 1 MiB. Mobile giới hạn 19 giây. Backend từ chối xử lý bản chép lời nếu AssemblyAI báo audio vượt 20 giây. Hỗ trợ M4A/MP4, WebM và WAV. Mỗi lượt trả HTTP 200 và một hội thoại mới theo cấu trúc trên, với bản chép lời và kết quả ghi. Khi bản chép lời thiếu thông tin bắt buộc, hội thoại vẫn hiển thị yêu cầu và lý do chưa ghi. Audio rỗng hoặc kiểu không hỗ trợ trả 400 hoặc 415. Audio vượt giới hạn dung lượng trả 413. Lượt chưa đồng ý hoặc dùng phiên bản đồng ý cũ trả 403. Audio không có bản chép lời dùng được trả 422. STT hoặc kho hội thoại không khả dụng trả 503.

Backend gửi audio tới AssemblyAI US để chép lời tiếng Việt bằng `universal-3-5-pro` với `universal-2` dự phòng. Backend yêu cầu AssemblyAI xóa transcript và audio liên kết khi đã nhận được ID lượt chép lời. Nếu không thể xác nhận xóa, dữ liệu phía nhà cung cấp chịu chính sách lưu giữ của tài khoản AssemblyAI. Gemini chỉ nhận bản chép lời hiện tại và mô tả hai công cụ nội bộ. Backend dùng JWT subject để tạo bản ghi qua Water và Sleep Journal v2, xác minh tham số và thực thi tối đa một công cụ tạo mới. Bản chép lời đi vào lịch sử hội thoại có thời hạn 7 ngày và có thể xóa từ Assistant.

- `recordWater` nhận lượng nước có đơn vị và thời điểm nếu người dùng nêu rõ. Mặc định thời điểm nhận lượt và dùng `Asia/Ho_Chi_Minh` cho giờ địa phương. Ví dụ “Nhập lượng nước 250ml tại thời điểm hiện tại cho tôi”.
- `recordSleep` cần cảm nhận Tốt, Bình thường hoặc Mệt mỏi. Nêu thời lượng và ngày thức dậy hoặc hai mốc giờ có ngày. Ví dụ “Nhập 8 tiếng ngủ cho ngày 17/11, cảm giác tốt” hoặc “Nhập cho tôi thời gian ngủ từ 11h20 tối hôm qua đến 6h sáng hôm nay, cảm giác tốt”. Ngày không có năm dùng năm hiện tại. Giới hạn tuần, trùng ngày, trùng khoảng giờ và các kiểm tra khác của Sleep Journal v2 tiếp tục áp dụng.

Trong thời hạn 7 ngày, gửi lại cùng tài khoản, UUID và audio trả hội thoại đã lưu mà không gọi lại Gemini hoặc ghi thêm bản ghi. Khi lượt đang xử lý, hội thoại đã bị xóa hoặc UUID trùng với audio khác, Backend trả 409. Nếu kết quả ghi chưa xác định, ứng dụng cần kiểm tra nhật ký trước khi tạo lượt mới.

Để bật trong môi trường thử nghiệm, đặt `ASSISTANT_VOICE_ENABLED=true`, `ASSEMBLYAI_API_KEY` và `ASSISTANT_GEMINI_API_KEY` ở Backend, rồi khởi động lại. Đặt `EXPO_PUBLIC_ASSISTANT_VOICE=true` khi build Mobile và dùng dev build có module ghi âm. Bản Mobile cần có thông báo đồng ý mới để gửi lượt thoại. [AssemblyAI US và chính sách lưu dữ liệu](https://www.assemblyai.com/docs/data-retention-and-model-training)
