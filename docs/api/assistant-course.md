# Assistant MVP cho đồ án

Assistant dùng một quyền theo tài khoản và lấy chủ sở hữu từ Bearer JWT. Công tắc Profile vừa cấp quyền truy cập Assistant vừa đồng ý cho Google Gemini xử lý dữ liệu Sleep/Water liên quan, câu hỏi và ngữ cảnh cần thiết.

`GET /api/assistant/mvp/settings` trả `{personalDataAllowed: boolean}`. `PUT` nhận `{personalDataAllowed: boolean, consentVersion?: string}`. Khi bật, gửi `consentVersion: "gemini-health-v1"` sau khi người dùng thấy mô tả về Google Gemini. Bản lưu cấp quyền cũ thiếu phiên bản được xem như tắt. Backend tăng generation khi ghi cài đặt. Khi tắt, tất cả API Assistant ngoài settings từ chối truy cập, kể cả lịch sử và voice. TTL 7 ngày vẫn áp dụng với lịch sử.

Các đường dẫn trong bảng tương đối với `/api/assistant/mvp/conversations`. Khi quyền bật, tạo hội thoại không cần chấp thuận lần hai. Các trường tương thích trong payload cũ không cấp quyền.

| Method và đường dẫn | Request | Kết quả |
| --- | --- | --- |
| `GET ?page=0` | 20 hội thoại mỗi trang | `{items: [{id, updatedAt}], hasMore}` |
| `POST /` | Không yêu cầu body | 201 và hội thoại mới. |
| `GET /{id}` | — | Hội thoại đang còn hạn. |
| `POST /{id}/turns` | `{turnId, text}`, UUID và chuỗi 1–300 ký tự | Hội thoại sau khi thêm lượt. Retry cùng UUID và nội dung trả lại hội thoại đã lưu. |
| `PUT /{id}/permission` | `{allowed: boolean}` | Trả hội thoại nếu giá trị trùng với quyền tài khoản hiện tại. `409 ASSISTANT_ACCOUNT_SETTING_REQUIRED` nếu cần đổi quyền. Không xóa lượt đã lưu. |
| `DELETE /{id}` | — | 204. |

`POST /` và `GET /{id}` trả `{id, updatedAt, personalAllowed, turns}`. `personalAllowed` phản ánh quyền tài khoản ở thời điểm đọc. Mỗi lượt có `{turnId, text, outcome, parts}`. Mỗi phần có `{status, text, source}`, với nguồn dạng `{id, version, title, route, from, to}`. Ngày ISO theo phạm vi của dữ liệu. Nguồn app-help không có ngày. Văn bản phản hồi là tiếng Việt.

Gemini quyết định truy xuất READY FAQ, đọc Sleep/Water hoặc kết hợp cả hai. Backend kiểm tra JWT, generation của quyền và revision hội thoại trước khi cung cấp bằng chứng hoặc gửi yêu cầu mới. Ngữ cảnh tóm tắt tối đa bốn chủ đề từ nguồn đã xác minh trong 30 phút. Backend đọc lại số liệu cần thiết theo lượt, không mặc định gửi lịch sử hoặc đưa thông tin định danh Auth/Profile vào payload.

401 áp dụng cho JWT thiếu hoặc không hợp lệ. 403 áp dụng khi quyền tắt. 404 áp dụng cho hội thoại ngoại lai hoặc hết hạn. 409 áp dụng khi quyền thay đổi trong lượt, UUID bị tái dùng với nội dung khác hoặc ghi đồng thời. 503 áp dụng khi kho lưu trữ hoặc deployment cấu hình provider chưa khả dụng. Tình trạng lỗi provider không thay đổi công tắc tài khoản.

Các projection Sleep/Water chỉ dùng dịch vụ chủ quản. Gemini có thể yêu cầu giờ ngủ/thức hoặc từng lượng nước đã ghi khi cần. Dữ liệu không có bản ghi không được tính bằng 0. Các nguồn công khai và dữ liệu xác minh được kiểm tra trước khi trả lời. Ngày thống kê dùng `Asia/Ho_Chi_Minh`.

Xem [điều kiện kích hoạt Gemini và Atlas](assistant-ready-faq-live.md) và [sandbox fixture](assistant-sandbox.md) dành cho thử nghiệm tổng hợp.

## Ghi nhật ký bằng giọng nói

`POST /api/assistant/mvp/voice-turns` yêu cầu Bearer JWT và quyền Assistant đang bật. Multipart form gửi `audio`, `turnId` (UUID), `consentAccepted=true`, và `consentVersion=assemblyai-us-v1` để xác nhận thông báo về việc gửi bản ghi âm cho AssemblyAI. Audio tối đa 1 MiB. Mobile giới hạn 19 giây. Backend từ chối bản chép lời nếu AssemblyAI báo audio vượt 20 giây. Hỗ trợ M4A/MP4, WebM và WAV. Mỗi lượt thành công trả HTTP 200 và một hội thoại mới với bản chép lời và kết quả ghi. Audio rỗng hoặc kiểu không hỗ trợ trả 400 hoặc 415. Audio vượt giới hạn dung lượng trả 413. Tài khoản tắt Assistant hoặc thiếu xác nhận AssemblyAI hiện hành nhận 403. Audio không có bản chép lời dùng được trả 422. STT, provider hoặc kho hội thoại không khả dụng trả 503.

Backend gửi audio tới AssemblyAI US để chép lời tiếng Việt bằng `universal-3-5-pro` với `universal-2` dự phòng. Backend yêu cầu AssemblyAI xóa transcript và audio liên kết khi đã nhận được ID lượt chép lời. Nếu không thể xác nhận xóa, dữ liệu phía nhà cung cấp chịu chính sách lưu giữ của tài khoản AssemblyAI. Gemini chỉ nhận bản chép lời hiện tại và mô tả hai công cụ nội bộ. Backend dùng JWT subject để tạo bản ghi qua Water và Sleep Journal v2, xác minh tham số và thực thi tối đa một công cụ tạo mới. Bản chép lời đi vào lịch sử hội thoại có thời hạn 7 ngày và có thể xóa từ Assistant.

- `recordWater` nhận lượng nước có đơn vị và thời điểm nếu người dùng nêu rõ. Mặc định thời điểm nhận lượt và dùng `Asia/Ho_Chi_Minh` cho giờ địa phương. Ví dụ “Nhập lượng nước 250ml tại thời điểm hiện tại cho tôi”.
- `recordSleep` cần cảm nhận Tốt, Bình thường hoặc Mệt mỏi. Nêu thời lượng và ngày thức dậy hoặc hai mốc giờ có ngày. Ví dụ “Nhập 8 tiếng ngủ cho ngày 17/11, cảm giác tốt” hoặc “Nhập cho tôi thời gian ngủ từ 11h20 tối hôm qua đến 6h sáng hôm nay, cảm giác tốt”. Ngày không có năm dùng năm hiện tại. Giới hạn tuần, trùng ngày, trùng khoảng giờ và các kiểm tra khác của Sleep Journal v2 tiếp tục áp dụng.

Trong thời hạn 7 ngày, gửi lại cùng tài khoản, UUID và audio trả hội thoại đã lưu mà không gọi lại Gemini hoặc ghi thêm bản ghi. Khi lượt đang xử lý, hội thoại đã bị xóa hoặc UUID trùng với audio khác, Backend trả 409. Nếu kết quả ghi chưa xác định, ứng dụng cần kiểm tra nhật ký trước khi tạo lượt mới.

Để bật trong môi trường thử nghiệm, đặt `ASSISTANT_VOICE_ENABLED=true`, `ASSEMBLYAI_API_KEY` và `ASSISTANT_GEMINI_API_KEY` ở Backend, rồi khởi động lại. Đặt `EXPO_PUBLIC_ASSISTANT_VOICE=true` khi build Mobile và dùng dev build có module ghi âm. Bản Mobile cần có thông báo đồng ý mới để gửi lượt thoại. [AssemblyAI US và chính sách lưu dữ liệu](https://www.assemblyai.com/docs/data-retention-and-model-training)
