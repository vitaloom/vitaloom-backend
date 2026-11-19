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

Text và bản chép lời đi qua cùng bộ xử lý hội thoại. Gemini có thể truy xuất READY FAQ, đọc Sleep/Water hoặc đề xuất ghi một bản ghi Water hay Main Sleep. Backend kiểm tra JWT, generation của quyền và revision hội thoại trước khi cung cấp dữ liệu, gửi yêu cầu mới hoặc ghi nhật ký. Phản hồi thành công của thao tác ghi dựa trên kết quả từ dịch vụ chủ quản.

Nếu ý định ghi chưa rõ hoặc còn thiếu tham số, lượt trả `outcome: "WRITE_CLARIFICATION"` và một câu hỏi trong `parts`. Người dùng có thể trả lời bằng text hoặc voice trong cùng hội thoại. Backend gửi tối đa ba lượt hỏi lại liên tiếp của cùng phiên bản quyền trong vòng 30 phút và trong cùng ngày tại `Asia/Ho_Chi_Minh`. Ngữ cảnh đọc dữ liệu gồm tối đa bốn chủ đề từ nguồn đã xác minh trong 30 phút. Backend đọc lại số liệu cần thiết theo lượt và loại thông tin định danh Auth/Profile khỏi payload.

Gemini xác định ý định ghi và trích nguyên văn tham số từ lời người dùng. Backend đối chiếu lượng nước, ngày giờ, thời lượng và cảm nhận với các phát ngôn liên quan. Hệ thống chỉ thực thi tối đa một thao tác tạo mới mỗi lượt. Cùng UUID và đầu vào sẽ trả kết quả đã lưu khi có thể, kể cả trường hợp nhật ký đã ghi nhưng chưa lưu được lượt hội thoại. Khi chưa biết kết quả thao tác ghi, gửi lại cùng UUID để lấy trạng thái. Kiểm tra nhật ký trước khi gửi một yêu cầu mới cho cùng bản ghi.

401 áp dụng cho JWT thiếu hoặc không hợp lệ. 403 áp dụng khi quyền tắt. 404 áp dụng cho hội thoại ngoại lai hoặc hết hạn. 409 áp dụng khi quyền thay đổi trong lượt, UUID bị tái dùng với nội dung khác hoặc ghi đồng thời. 503 áp dụng khi kho lưu trữ hoặc deployment cấu hình provider chưa khả dụng. Tình trạng lỗi provider không thay đổi công tắc tài khoản.

Các projection Sleep/Water chỉ dùng dịch vụ chủ quản. Gemini có thể yêu cầu giờ ngủ/thức hoặc từng lượng nước đã ghi khi cần. Dữ liệu không có bản ghi không được tính bằng 0. Các nguồn công khai và dữ liệu xác minh được kiểm tra trước khi trả lời. Ngày thống kê dùng `Asia/Ho_Chi_Minh`.

Xem [điều kiện kích hoạt Gemini và Atlas](assistant-ready-faq-live.md) và [sandbox fixture](assistant-sandbox.md) dành cho thử nghiệm tổng hợp.

## Lượt hội thoại bằng giọng nói

`POST /api/assistant/mvp/voice-turns` yêu cầu Bearer JWT và quyền Assistant đang bật. Multipart form gửi `audio`, `turnId` (UUID), `consentAccepted=true`, và `consentVersion=assemblyai-us-v1` để xác nhận thông báo về việc gửi bản ghi âm cho AssemblyAI. Trường tùy chọn `conversationId` (UUID) thêm lượt vào hội thoại hiện tại. Khi bỏ trường này, Backend mở hội thoại mới. Audio tối đa 1 MiB. Mobile giới hạn 19 giây. Backend từ chối bản chép lời nếu AssemblyAI báo audio vượt 20 giây. Hỗ trợ M4A/MP4, WebM và WAV. Lượt thành công trả HTTP 200 và hội thoại có bản chép lời cùng phản hồi Assistant. Audio rỗng hoặc kiểu không hỗ trợ trả 400 hoặc 415. Audio vượt giới hạn dung lượng trả 413. Tài khoản tắt Assistant hoặc thiếu xác nhận AssemblyAI hiện hành nhận 403. Hội thoại đích ngoại lai hoặc hết hạn trả 404. Audio không có bản chép lời dùng được trả 422. STT, provider hoặc kho hội thoại không khả dụng trả 503.

Backend gửi audio tới AssemblyAI US để chép lời tiếng Việt bằng `universal-3-5-pro` với `universal-2` dự phòng. Backend yêu cầu AssemblyAI xóa transcript và audio liên kết khi đã nhận được ID lượt chép lời. Nếu không thể xác nhận xóa, dữ liệu phía nhà cung cấp chịu chính sách lưu giữ của tài khoản AssemblyAI. Bản chép lời đi vào bộ xử lý hội thoại chung và lịch sử có thời hạn 7 ngày, có thể xóa từ Assistant.

## Ghi nhật ký từ Assistant

Hai công cụ tạo mới dùng JWT subject và gọi trực tiếp dịch vụ Water hoặc Sleep Journal v2.

- `recordWater` nhận lượng nước có đơn vị và thời điểm nếu người dùng nêu rõ. Mặc định thời điểm nhận lượt và dùng `Asia/Ho_Chi_Minh` cho giờ địa phương. Ví dụ “Nhập lượng nước 250ml tại thời điểm hiện tại cho tôi”.
- `recordSleep` cần cảm nhận Tốt, Bình thường hoặc Mệt mỏi. Nêu thời lượng và ngày thức dậy hoặc hai mốc giờ có ngày. Ví dụ “Nhập 8 tiếng ngủ cho ngày 17/11, cảm giác tốt” hoặc “Nhập cho tôi thời gian ngủ từ 11h20 tối hôm qua đến 6h sáng hôm nay, cảm giác tốt”. Ngày không có năm dùng năm hiện tại. Giới hạn tuần, trùng ngày, trùng khoảng giờ và các kiểm tra khác của Sleep Journal v2 tiếp tục áp dụng.

Gửi lại cùng tài khoản, UUID, hội thoại và audio đã xử lý trả hội thoại đã lưu mà không gọi lại AssemblyAI/Gemini hoặc ghi thêm bản ghi. Khi retry, giữ nguyên `conversationId` đã gửi ở lần đầu, kể cả khi trường này bị bỏ trống. UUID dùng cho đầu vào hoặc hội thoại khác, lượt đồng thời hoặc lượt chưa xác định có thể trả 409.

Để bật trong môi trường thử nghiệm, đặt `ASSISTANT_VOICE_ENABLED=true`, `ASSEMBLYAI_API_KEY` và `ASSISTANT_GEMINI_API_KEY` ở Backend, rồi khởi động lại. Đặt `EXPO_PUBLIC_ASSISTANT_VOICE=true` khi build Mobile và dùng dev build có module ghi âm. Bản Mobile cần có thông báo đồng ý mới để gửi lượt thoại. [AssemblyAI US và chính sách lưu dữ liệu](https://www.assemblyai.com/docs/data-retention-and-model-training)
