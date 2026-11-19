package iuh.fit.socialservice.infrastructure;

import iuh.fit.socialservice.domain.enums.PostSport;
import iuh.fit.socialservice.domain.enums.PostStatus;
import iuh.fit.socialservice.domain.enums.PostVisibility;
import iuh.fit.socialservice.infrastructure.persistence.entity.PostEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Hàng đợi kiểm duyệt mô phỏng (dev / demo) cho trang Kiểm duyệt của admin:
 * <ul>
 *   <li>6 bài vi phạm (spam, xúc phạm, lừa đảo, cá độ...) của người chơi mô phỏng: 4 bài đang chờ xử lý (mỗi bài 2 báo
 *       cáo), 1 bài đã ẩn, 1 bài đã gỡ — tác giả bài bị gỡ đang kháng nghị;</li>
 *   <li>2 báo cáo sai trên bài bình thường đã bị bác (DISMISS_REPORT);</li>
 *   <li>vài lượt chặn người dùng giữa người chơi mô phỏng.</li>
 * </ul>
 * Người xử lý là admin kiểm duyệt demo (kiemduyet@goatsports.vn, id tất định). Chạy một lần: bỏ qua nếu dữ liệu đã có,
 * để thao tác kiểm duyệt lúc demo không bị khởi động lại ghi đè. Bật bằng {@code app.demo-data.simulation.enabled=true}.
 */
@Slf4j
@Component
@Order(22)
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.demo-data.simulation", name = "enabled", havingValue = "true")
public class DemoModerationSeeder implements CommandLineRunner {
    private static final UUID MODERATOR = DemoPopulation.userId("kiemduyet@goatsports.vn");

    private final MongoTemplate mongo;
    private final JdbcTemplate jdbc;

    /** Bài vi phạm: tác giả (chỉ số người chơi), nội dung, lý do báo cáo, kết quả xử lý (null = đang chờ). */
    record Flagged(int author, int[] reporters, String sport, String content, String reason, String action, int daysAgo) { }

    static final List<Flagged> FLAGGED = List.of(
            new Flagged(12, new int[]{40, 77}, "FOOTBALL",
                    "Thanh lý giày đá bóng chính hãng giá sỉ, ai cần inbox Zalo để lấy mã giảm 50% nhé!!! Số lượng có hạn",
                    "Spam / quảng cáo bán hàng", null, 1),
            new Flagged(58, new int[]{21, 133}, "BADMINTON",
                    "Sân này dở tệ, chủ sân lừa đảo, mọi người tuyệt đối đừng đặt kẻo mất tiền!!!",
                    "Thông tin sai sự thật, bôi nhọ cơ sở", null, 2),
            new Flagged(104, new int[]{9, 66}, "FOOTBALL",
                    "Đá như vậy thì về nhà ngủ đi cho đỡ chật sân, đội gì mà gà quá",
                    "Xúc phạm, công kích người khác", null, 1),
            new Flagged(177, new int[]{30, 145}, "PICKLEBALL",
                    "Nhận cày ELO thuê, lên hạng nhanh, uy tín, chuyển khoản trước rồi làm liền",
                    "Lừa đảo / gian lận xếp hạng", null, 3),
            new Flagged(83, new int[]{5, 119}, "FOOTBALL",
                    "Kèo đá có tiền 500k/người tối nay, đội thua trả hết, ai chơi inbox",
                    "Cá cược, cờ bạc", "HIDE_CONTENT", 6),
            new Flagged(150, new int[]{47, 201}, "TENNIS",
                    "Nhận quà miễn phí cho người chơi tennis, bấm link rút gọn rồi nhập mật khẩu để nhận ngay",
                    "Lừa đảo, đường dẫn đáng ngờ", "REMOVE_CONTENT", 8));

    @Override
    public void run(String... args) {
        Integer existing = jdbc.queryForObject("select count(*) from content_reports where created_by = 'demo-sim'", Integer.class);
        if (existing != null && existing > 0) return;
        LocalDateTime now = LocalDateTime.now().withSecond(0).withNano(0);
        List<DemoPopulation.Player> players = DemoPopulation.players();
        int reports = 0;
        for (int index = 0; index < FLAGGED.size(); index++) {
            Flagged flagged = FLAGGED.get(index);
            DemoPopulation.Player author = players.get(flagged.author());
            UUID postId = id("sim-flagged-post", String.valueOf(index));
            LocalDateTime postedAt = now.minusDays(flagged.daysAgo()).withHour(20).withMinute(15);
            PostStatus status = "HIDE_CONTENT".equals(flagged.action()) ? PostStatus.HIDDEN
                    : "REMOVE_CONTENT".equals(flagged.action()) ? PostStatus.REMOVED : PostStatus.PUBLISHED;
            if (mongo.findById(postId.toString(), PostEntity.class) == null) {
                mongo.insert(PostEntity.builder().postId(postId.toString()).authorId(author.userId().toString())
                        .content(flagged.content()).visibility(PostVisibility.PUBLIC).status(status)
                        .sport(PostSport.valueOf(flagged.sport())).createdAt(postedAt).updatedAt(postedAt)
                        .publishedAt(postedAt).build());
            }
            String reportStatus = flagged.action() == null ? "PENDING" : "RESOLVED";
            UUID firstReport = null;
            for (int reporter = 0; reporter < flagged.reporters().length; reporter++) {
                UUID reportId = id("sim-report", index + "|" + reporter);
                if (firstReport == null) firstReport = reportId;
                reports += report(reportId, players.get(flagged.reporters()[reporter]).userId(), postId, flagged.reason(),
                        reportStatus, postedAt.plusHours(1 + reporter * 3L));
            }
            if (flagged.action() != null) {
                LocalDateTime actionAt = postedAt.plusDays(1).withHour(9);
                UUID actionId = id("sim-moderation", String.valueOf(index));
                action(actionId, firstReport, author.userId(), flagged.action(),
                        "Vi phạm quy định cộng đồng: " + flagged.reason().toLowerCase(), actionAt);
                if ("REMOVE_CONTENT".equals(flagged.action())) {
                    jdbc.update("""
                            insert into content_appeals (appeal_id, action_id, appellant_id, reason, status,
                                                         created_at, created_by, updated_at, updated_by)
                            values (?, ?, ?, ?, 'PENDING', ?, 'demo-sim', ?, 'demo-sim') on conflict do nothing
                            """, id("sim-content-appeal", String.valueOf(index)), actionId, author.userId(),
                            "Mình chỉ chia sẻ lại chương trình khuyến mãi bạn gửi, không biết là lừa đảo. Mong admin "
                                    + "xem lại và khôi phục giúp, mình sẽ cẩn thận hơn.",
                            Timestamp.valueOf(actionAt.plusHours(5)), Timestamp.valueOf(actionAt.plusHours(5)));
                }
            }
        }
        reports += falseReports(players, now);
        int blocks = blocks(players, now);
        log.info("Kiểm duyệt mô phỏng: {} báo cáo, {} lượt chặn người dùng", reports, blocks);
    }

    /** Hai báo cáo sai trên bài bình thường gần đây, đã bị bác. */
    private int falseReports(List<DemoPopulation.Player> players, LocalDateTime now) {
        Query query = new Query(Criteria.where("postId").regex("^[0-9a-f-]{36}$").and("status").is(PostStatus.PUBLISHED)
                .and("sharedPostId").is(null).and("publishedAt").gte(now.minusDays(10)).lte(now.minusDays(3)))
                .with(Sort.by("postId")).limit(2);
        List<PostEntity> posts = mongo.find(query, PostEntity.class);
        int inserted = 0;
        for (int index = 0; index < posts.size(); index++) {
            PostEntity post = posts.get(index);
            UUID reportId = id("sim-false-report", String.valueOf(index));
            LocalDateTime reportedAt = post.getPublishedAt().plusHours(2);
            DemoPopulation.Player reporter = players.get(250 + index);
            inserted += report(reportId, reporter.userId(), UUID.fromString(post.getPostId()),
                    "Nội dung không phù hợp", "REJECTED", reportedAt);
            action(id("sim-false-report-action", String.valueOf(index)), reportId, UUID.fromString(post.getAuthorId()),
                    "DISMISS_REPORT", "Bài viết không vi phạm quy định cộng đồng", reportedAt.plusHours(20));
        }
        return inserted;
    }

    private int blocks(List<DemoPopulation.Player> players, LocalDateTime now) {
        int[][] pairs = {{40, 104}, {9, 104}, {21, 58}, {66, 177}, {133, 58}};
        List<Object[]> rows = new ArrayList<>();
        for (int[] pair : pairs) {
            UUID blocker = players.get(pair[0]).userId();
            UUID blocked = players.get(pair[1]).userId();
            Timestamp at = Timestamp.valueOf(now.minusDays(pair[0] % 7 + 1L).withHour(22));
            rows.add(new Object[]{id("sim-block", blocker + "|" + blocked), blocker, blocked,
                    "Bình luận thiếu tôn trọng", at, at, at});
        }
        int total = 0;
        for (int count : jdbc.batchUpdate("""
                insert into user_blocks (block_id, blocker_id, blocked_user_id, reason, blocked_at, created_at, created_by,
                                         updated_at, updated_by)
                values (?, ?, ?, ?, ?, ?, 'demo-sim', ?, 'demo-sim') on conflict do nothing
                """, rows)) {
            total += Math.max(count, 0);
        }
        return total;
    }

    private int report(UUID reportId, UUID reporterId, UUID postId, String reason, String status, LocalDateTime at) {
        Timestamp createdAt = Timestamp.valueOf(at);
        return jdbc.update("""
                insert into content_reports (report_id, reporter_id, target_type, target_id, reason, status,
                                             created_at, created_by, updated_at, updated_by)
                values (?, ?, 'POST', ?, ?, ?, ?, 'demo-sim', ?, 'demo-sim') on conflict do nothing
                """, reportId, reporterId, postId, reason, status, createdAt, createdAt);
    }

    private void action(UUID actionId, UUID reportId, UUID authorId, String type, String reason, LocalDateTime at) {
        Timestamp actionAt = Timestamp.valueOf(at);
        jdbc.update("""
                insert into moderation_actions (action_id, report_id, moderator_id, content_author_id, action_type, reason,
                                                action_at, created_at, created_by, updated_at, updated_by)
                values (?, ?, ?, ?, ?, ?, ?, ?, 'demo-sim', ?, 'demo-sim') on conflict do nothing
                """, actionId, reportId, MODERATOR, authorId, type, reason, actionAt, actionAt, actionAt);
    }

    static UUID id(String kind, String key) {
        return UUID.nameUUIDFromBytes(("goatsports:" + kind + ":" + key).getBytes(StandardCharsets.UTF_8));
    }
}
