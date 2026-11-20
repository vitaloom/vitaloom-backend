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

Text và bản chép lời đi qua cùng bộ xử lý hội thoại. Spring AI gọi các công cụ do Gemini chọn để tra READY FAQ, đọc Sleep/Water, điều hướng hoặc ghi một bản ghi Water hay Main Sleep. Backend kiểm tra JWT, generation của quyền, chủ sở hữu và revision hội thoại trước khi thực thi hoặc gửi dữ liệu. Backend kiểm tra an toàn và nguồn hỗ trợ trước khi công bố nội dung do Gemini soạn. Hướng dẫn sức khỏe cần đoạn trích đã duyệt và liên quan. So sánh sức khỏe cá nhân cần cả bằng chứng công khai và dữ liệu nhật ký mới đọc.

Nếu ý định ghi chưa rõ hoặc còn thiếu tham số, Assistant có thể hỏi lại bằng văn bản trong `parts` với `outcome: "ANSWERED"`. Người dùng có thể tiếp tục bằng text hoặc voice trong cùng hội thoại. Backend gửi tối đa năm lượt trước cho Gemini, theo thứ tự và đúng vai trò của người dùng và Assistant. Cửa sổ chỉ gồm các lượt trong 30 phút, cùng ngày tại `Asia/Ho_Chi_Minh` và cùng generation quyền. Nội dung hội thoại gồm phần văn bản đã hiển thị của các câu trả lời có nguồn. Backend lọc thông tin định danh và xác thực khỏi ngữ cảnh. Khi cần số liệu nhật ký, Assistant đọc bằng chứng mới trong lượt hiện tại.

Xác minh ý định ghi dùng tối đa ba lượt theo cùng giới hạn thời gian và quyền. Backend loại các lượt có nguồn khỏi ngữ cảnh xác minh ghi và dừng tại hành động ghi đã thực thi. Các lượt lịch sử mang `WRITE_CLARIFICATION` và `PARTIAL` vẫn đọc được.

Gemini chuẩn hóa lượng nước, ngày giờ, thời lượng và cảm nhận từ lời người dùng. Backend xác minh ý định, đặt biên nhận theo tài khoản và UUID lượt, rồi cho phép tối đa một thao tác tạo mới. Biên nhận lưu phản hồi từ dịch vụ chủ quản trước khi trả kết quả công cụ cho Gemini. Cùng UUID, hội thoại và đầu vào có thể nhận lại kết quả đã lưu, kể cả khi Gemini gặp lỗi sau khi ghi hoặc lượt hội thoại chưa lưu được. Khi kết quả chưa xác định, Backend giữ biên nhận để lần gửi lại cùng UUID không lặp thao tác ghi. Hãy kiểm tra nhật ký trước khi gửi yêu cầu mới cho cùng bản ghi.

401 áp dụng cho JWT thiếu hoặc không hợp lệ. 403 áp dụng khi quyền tắt. 404 áp dụng cho hội thoại ngoại lai hoặc hết hạn. 409 áp dụng khi quyền thay đổi trong lượt, UUID bị tái dùng với nội dung khác hoặc ghi đồng thời. 503 áp dụng khi kho lưu trữ hoặc deployment cấu hình provider chưa khả dụng. Tình trạng lỗi provider không thay đổi công tắc tài khoản.

Nếu thao tác ghi có thể đã tới dịch vụ chủ quản mà Backend chưa xác nhận được kết quả, 503 mang mã `ASSISTANT_WRITE_UNCERTAIN`. Hãy kiểm tra nhật ký trước khi gửi yêu cầu mới. Gửi lại cùng UUID có thể nhận 409 trong hai phút chờ biên nhận hoặc nhận kết quả đã lưu, tùy thời điểm.

Các projection Sleep/Water chỉ dùng dịch vụ chủ quản. Gemini có thể yêu cầu giờ ngủ/thức hoặc từng lượng nước đã ghi khi cần. Dữ liệu không có bản ghi không được tính bằng 0. Các nguồn công khai và dữ liệu xác minh được kiểm tra trước khi trả lời. Ngày thống kê dùng `Asia/Ho_Chi_Minh`.

Xem [điều kiện kích hoạt Gemini và Atlas](assistant-ready-faq-live.md) và [sandbox fixture](assistant-sandbox.md) dành cho thử nghiệm tổng hợp.

## Lượt hội thoại bằng giọng nói

`POST /api/assistant/mvp/voice-turns` yêu cầu Bearer JWT và quyền Assistant đang bật. Multipart form gửi `audio`, `turnId` (UUID), `consentAccepted=true`, và `consentVersion=assemblyai-us-v1` để xác nhận thông báo về việc gửi bản ghi âm cho AssemblyAI. Trường tùy chọn `conversationId` (UUID) thêm lượt vào hội thoại hiện tại. Khi bỏ trường này, Backend mở hội thoại mới. Audio tối đa 1 MiB. Mobile giới hạn 19 giây. Backend từ chối bản chép lời nếu AssemblyAI báo audio vượt 20 giây. Hỗ trợ M4A/MP4, WebM và WAV. Lượt thành công trả HTTP 200 và hội thoại có bản chép lời cùng phản hồi Assistant. Audio rỗng hoặc kiểu không hỗ trợ trả 400 hoặc 415. Audio vượt giới hạn dung lượng trả 413. Tài khoản tắt Assistant hoặc thiếu xác nhận AssemblyAI hiện hành nhận 403. Hội thoại đích ngoại lai hoặc hết hạn trả 404. Audio không có bản chép lời dùng được trả 422. STT, provider hoặc kho hội thoại không khả dụng trả 503.

Backend gửi audio tới AssemblyAI US để chép lời tiếng Việt bằng `universal-3-5-pro` với `universal-2` dự phòng. Backend yêu cầu AssemblyAI xóa transcript và audio liên kết khi đã nhận được ID lượt chép lời. Nếu không thể xác nhận xóa, dữ liệu phía nhà cung cấp chịu chính sách lưu giữ của tài khoản AssemblyAI. Bản chép lời đi vào bộ xử lý hội thoại chung và lịch sử có thời hạn 7 ngày, có thể xóa từ Assistant.

## Ghi nhật ký từ Assistant

Ba công cụ tạo mới dùng JWT subject và ghi qua biên nhận của lượt trước khi gọi dịch vụ Water hoặc Sleep Journal v2.

- `recordWater` nhận `amountMl` nguyên từ 1 đến 2000 và `loggedAt` tùy chọn ở dạng ISO kèm múi giờ. Nếu người dùng muốn ghi bây giờ hoặc không nêu ngày giờ, Backend dùng thời điểm nhận lượt.
- `recordSleepDuration` nhận ngày thức dậy `sleepDate` ở dạng ISO, `durationMinutes` nguyên từ 1 đến 1440 và `feeling`. Ví dụ “Ghi giấc ngủ sáng nay, 6 tiếng rưỡi, cảm giác mệt mỏi”.
- `recordSleepRange` nhận `start` và `end` ở dạng `YYYY-MM-DDTHH:mm` tại `Asia/Ho_Chi_Minh` và `feeling`. Khoảng qua nửa đêm tính vào ngày thức dậy. Cả hai công cụ Sleep yêu cầu `COMFORTABLE`, `NORMAL` hoặc `TIRED`. Ngày không có năm dùng năm của lượt. Giới hạn tuần, trùng ngày, trùng khoảng giờ và các quy tắc khác của Sleep Journal v2 tiếp tục áp dụng.

Gửi lại cùng tài khoản, UUID, hội thoại và audio đã xử lý trả hội thoại đã lưu mà không gọi lại AssemblyAI/Gemini hoặc ghi thêm bản ghi. Khi retry, giữ nguyên `conversationId` đã gửi ở lần đầu, kể cả khi trường này bị bỏ trống. UUID dùng cho đầu vào hoặc hội thoại khác, lượt đồng thời hoặc lượt chưa xác định có thể trả 409.

Để bật trong môi trường thử nghiệm, đặt `ASSISTANT_VOICE_ENABLED=true`, `ASSEMBLYAI_API_KEY` và `ASSISTANT_GEMINI_API_KEY` ở Backend, rồi khởi động lại. Đặt `EXPO_PUBLIC_ASSISTANT_VOICE=true` khi build Mobile và dùng dev build có module ghi âm. Bản Mobile cần có thông báo đồng ý mới để gửi lượt thoại. [AssemblyAI US và chính sách lưu dữ liệu](https://www.assemblyai.com/docs/data-retention-and-model-training)
