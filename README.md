# GoatSports Social Service

Mạng xã hội thể thao: bảng tin, bài viết "tìm người chơi", bạn bè, theo dõi, nhắn tin realtime và kiểm duyệt nội dung.

## Chức năng chính

- **Bài viết**: đăng, sửa, ảnh, nhắc tên, hashtag, thích, bình luận, chia sẻ (bài chia sẻ là một bài mới trỏ về bài gốc),
  lưu bài. Quyền xem PUBLIC / FRIENDS, chặn có hiệu lực hai chiều.
- **Bảng tin**: Khám phá được xếp hạng cá nhân hóa bởi ai-service (AI lỗi thì theo thứ tự mới nhất), Đang theo dõi,
  lọc theo môn và chủ đề.
- **Kèo tìm người chơi** (`playerCall`): kèo cá nhân hoặc kèo CLB, lọc kèo còn mở, sắp theo giờ chơi.
- **Quan hệ**: kết bạn, theo dõi người dùng và sân, chặn; gợi ý theo dõi dựa trên ghép trận, CLB, tin nhắn, bạn chung.
- **Nhắn tin realtime** qua WebSocket (STOMP): chat 1-1, nhóm, nhóm CLB / giải / kèo; trạng thái đã gửi / đã nhận / đã xem,
  chống trùng tin bằng `clientMessageId`, ảnh đính kèm riêng tư; trạng thái online (presence).
- **Kiểm duyệt**: báo cáo bài / bình luận, AI gợi ý phân loại (chỉ gợi ý, không tự ẩn), xử lý ẩn / gỡ / cảnh cáo,
  tác giả kháng nghị.

## API chính

| Đường dẫn | Mô tả |
|---|---|
| `/api/v1/social/posts` | Bảng tin, bài viết, bình luận, cảm xúc, chia sẻ, lưu, kèo |
| `/api/v1/social/friends`, `/follows/users`, `/follows/venues`, `/blocks` | Quan hệ người dùng |
| `/api/v1/social/conversations`, `/presence` | Hội thoại, tin nhắn, trạng thái online |
| `/api/v1/social/reports` | Báo cáo và kháng nghị |
| `/api/v1/admin/social`, `/api/v1/admin/stats` | Kiểm duyệt, thống kê cộng đồng |
| `/internal/conversations`, `/internal/posts` | API nội bộ cho club, ai, storage |

## Cấu trúc

```
src/main/java/iuh/fit/socialservice/
├── domain/          # entity, enum, quy tắc thuần (vd. PostMentions, FollowSuggestionRanker)
├── application/     # service, port, DTO
├── infrastructure/  # MongoDB, JPA, Kafka, WebSocket, Feign, job, demo seeder
└── presentation/    # REST + STOMP controller
shared-kernel/       # @AuditLog, Feign interceptor dùng chung
```

## Tích hợp

- **MongoDB** (bài viết, bình luận, tin nhắn), **PostgreSQL** (Liquibase).
- **Kafka phát**: `social.events` (thông báo cộng đồng), `user-activity` (hành vi cho cá nhân hóa), `user-log`.
  **Kafka nhận**: `user-deleted`.
- **Feign**: ai-service (xếp hạng bảng tin, kiểm duyệt), club-service, storage-service.

## Chạy và kiểm thử

```bash
mvn -f shared-kernel/pom.xml clean install -DskipTests
mvn spring-boot:run -Dspring-boot.run.profiles=dev     # cần application-dev.properties (bị .gitignore)
mvn test
```

## CI/CD

`.gitlab-ci.yml`: test → `jib:build` lên Docker Hub → gọi Jenkins job `GoatSports-CD`.
