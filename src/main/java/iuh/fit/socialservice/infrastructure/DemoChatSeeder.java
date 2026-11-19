package iuh.fit.socialservice.infrastructure;

import iuh.fit.socialservice.domain.enums.MessageStatus;
import iuh.fit.socialservice.domain.enums.MessageType;
import iuh.fit.socialservice.infrastructure.persistence.entity.MessageEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Tin nhắn mẫu (dev / demo): mỗi người chơi demo có hai cuộc trò chuyện riêng với người chơi mô phỏng (rủ kèo, chốt
 * sân, hẹn giờ), và nhóm chat của các CLB demo có vài tin sinh hoạt. Tin cuối của một số cuộc chưa đọc để hộp thư có
 * chấm báo. Mỗi cuộc trò chuyện chỉ seed một lần: tin nhắn gửi thêm lúc demo không bị khởi động lại ghi đè.
 * Bật bằng {@code app.demo-data.simulation.enabled=true}.
 */
@Slf4j
@Component
@Order(23)
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.demo-data.simulation", name = "enabled", havingValue = "true")
public class DemoChatSeeder implements CommandLineRunner {
    private static final List<String> DEMO_PLAYERS = List.of("huy.danggia@goatsports.vn", "tung.vuthanh@goatsports.vn",
            "minh.hoangnhat@goatsports.vn", "linh.buikhanh@goatsports.vn");

    /** Hai kịch bản trò chuyện riêng: true = người chơi demo nói, false = người kia nói. */
    private static final List<List<Object[]>> DIRECT_SCRIPTS = List.of(
            List.of(new Object[]{false, "Ê bạn, tối thứ Bảy này đá kèo 5 người không? Team mình đang thiếu 1 người."},
                    new Object[]{true, "Mấy giờ vậy bạn? Mình rảnh sau 6h."},
                    new Object[]{false, "19h nha, sân Hoàng Long, mình đặt cọc rồi."},
                    new Object[]{true, "Ok chốt, mình mang thêm 1 quả bóng."},
                    new Object[]{false, "Tuyệt, tới sớm 15 phút khởi động nhé 💪"}),
            List.of(new Object[]{true, "Hôm qua đánh vui ghê, tuần sau đấu lại không?"},
                    new Object[]{false, "Được luôn, mình muốn phục thù 😄"},
                    new Object[]{true, "Mình đặt sân rồi gửi mã QR cho bạn nhé."},
                    new Object[]{false, "Ok, nhớ chọn khung tối thứ 5 giúp mình."}));

    private static final List<String> CLUB_SCRIPT = List.of(
            "Tối nay sinh hoạt như thường lệ nha mọi người, 19h có mặt.",
            "Mình đến muộn 10 phút, mọi người cứ khởi động trước.",
            "Ai mượn áo bib tuần trước nhớ mang trả nhé 😅",
            "Cuối tháng có giải phong trào, CLB mình đăng ký 1 đội không?",
            "Đăng ký đi anh, em tham gia!",
            "Ok, mình lên danh sách, ai muốn đá thì thả tim tin này.");

    private final MongoTemplate mongo;
    private final JdbcTemplate jdbc;

    @Override
    public void run(String... args) {
        LocalDateTime now = LocalDateTime.now().withSecond(0).withNano(0);
        List<DemoPopulation.Player> players = DemoPopulation.players();
        int conversations = 0;
        int messages = 0;
        for (int index = 0; index < DEMO_PLAYERS.size(); index++) {
            UUID demo = DemoPopulation.userId(DEMO_PLAYERS.get(index));
            for (int script = 0; script < DIRECT_SCRIPTS.size(); script++) {
                UUID other = players.get(index * 17 + script * 5 + 3).userId();
                UUID conversationId = id("demo-direct", demo + "|" + other);
                if (exists(conversationId)) continue;
                LocalDateTime start = now.minusHours(3L + index * 7L + script * 26L);
                List<Object[]> lines = DIRECT_SCRIPTS.get(script);
                List<UUID> senders = new ArrayList<>();
                List<String> contents = new ArrayList<>();
                for (Object[] line : lines) {
                    senders.add((boolean) line[0] ? demo : other);
                    contents.add((String) line[1]);
                }
                // Cuộc thứ nhất: tin cuối của người kia chưa đọc.
                boolean unread = script == 0;
                messages += conversation(conversationId, "DIRECT", null, null, List.of(demo, other), senders, contents,
                        start, unread ? demo : null);
                conversations++;
            }
        }
        List<Object[]> clubs = jdbc.query("""
                select conversation_id from conversations where type = 'CLUB' and created_by = 'demo-seed'
                """, (rs, index) -> new Object[]{rs.getObject(1, UUID.class)});
        for (Object[] club : clubs) {
            UUID conversationId = (UUID) club[0];
            Integer existing = jdbc.queryForObject("select count(*) from conversations where conversation_id = ? "
                    + "and last_message_id is not null", Integer.class, conversationId);
            if (existing != null && existing > 0) continue;
            List<UUID> members = jdbc.queryForList("""
                    select user_id from conversation_members where conversation_id = ? and left_at is null
                    order by joined_at, user_id
                    """, UUID.class, conversationId);
            if (members.size() < 2) continue;
            List<UUID> senders = new ArrayList<>();
            for (int line = 0; line < CLUB_SCRIPT.size(); line++) senders.add(members.get(line % members.size()));
            messages += clubMessages(conversationId, senders, now.minusHours(30));
            conversations++;
        }
        log.info("Tin nhắn mẫu: {} cuộc trò chuyện, {} tin nhắn", conversations, messages);
    }

    private boolean exists(UUID conversationId) {
        Integer count = jdbc.queryForObject("select count(*) from conversations where conversation_id = ?", Integer.class,
                conversationId);
        return count != null && count > 0;
    }

    /** Tạo cuộc trò chuyện (nếu là DIRECT), thành viên, tin nhắn; {@code unreadFor}: người còn chưa đọc tin cuối. */
    private int conversation(UUID conversationId, String type, String name, UUID contextId, List<UUID> members,
                             List<UUID> senders, List<String> contents, LocalDateTime start, UUID unreadFor) {
        Timestamp createdAt = Timestamp.valueOf(start.minusMinutes(1));
        jdbc.update("""
                insert into conversations (conversation_id, type, name, context_id, created_at, created_by, updated_at,
                                           updated_by)
                values (?, ?, ?, ?, ?, 'demo-sim', ?, 'demo-sim') on conflict do nothing
                """, conversationId, type, name, contextId, createdAt, createdAt);
        List<MessageEntity> messages = messages(conversationId, senders, contents, start, unreadFor);
        String lastId = messages.get(messages.size() - 1).getMessageId();
        String readUpTo = messages.size() > 1 ? messages.get(messages.size() - 2).getMessageId() : lastId;
        for (UUID member : members) {
            String lastRead = member.equals(unreadFor) ? readUpTo : lastId;
            jdbc.update("""
                    insert into conversation_members (member_id, conversation_id, user_id, role, joined_at,
                                                      last_read_message_id, muted, created_at, created_by, updated_at,
                                                      updated_by)
                    values (?, ?, ?, 'MEMBER', ?, ?, false, ?, 'demo-sim', ?, 'demo-sim') on conflict do nothing
                    """, id("demo-member", conversationId + "|" + member), conversationId, member, createdAt,
                    UUID.fromString(lastRead), createdAt, createdAt);
        }
        mongo.insertAll(messages);
        touch(conversationId, messages);
        return messages.size();
    }

    private int clubMessages(UUID conversationId, List<UUID> senders, LocalDateTime start) {
        List<MessageEntity> messages = messages(conversationId, senders, CLUB_SCRIPT, start, null);
        mongo.insertAll(messages);
        touch(conversationId, messages);
        return messages.size();
    }

    private static List<MessageEntity> messages(UUID conversationId, List<UUID> senders, List<String> contents,
                                                LocalDateTime start, UUID unreadFor) {
        List<MessageEntity> messages = new ArrayList<>();
        for (int index = 0; index < contents.size(); index++) {
            boolean last = index == contents.size() - 1;
            UUID messageId = id("demo-message", conversationId + "|" + index);
            LocalDateTime sentAt = start.plusMinutes(index * 4L + (index % 2) * 2L);
            messages.add(MessageEntity.builder().messageId(messageId.toString()).conversationId(conversationId.toString())
                    .senderId(senders.get(index).toString()).clientMessageId(messageId.toString())
                    .content(contents.get(index)).type(MessageType.TEXT)
                    .status(last && unreadFor != null ? MessageStatus.DELIVERED : MessageStatus.READ).deleted(false)
                    .sentAt(sentAt).build());
        }
        return messages;
    }

    private void touch(UUID conversationId, List<MessageEntity> messages) {
        MessageEntity last = messages.get(messages.size() - 1);
        jdbc.update("""
                update conversations set last_message_at = ?, last_message_id = ?, updated_at = ?, updated_by = 'demo-sim'
                where conversation_id = ?
                """, Timestamp.valueOf(last.getSentAt()), UUID.fromString(last.getMessageId()),
                Timestamp.valueOf(last.getSentAt()), conversationId);
    }

    static UUID id(String kind, String key) {
        return UUID.nameUUIDFromBytes(("goatsports:" + kind + ":" + key).getBytes(StandardCharsets.UTF_8));
    }
}
