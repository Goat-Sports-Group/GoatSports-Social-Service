package iuh.fit.socialservice.infrastructure;

import iuh.fit.socialservice.domain.enums.PostSport;
import iuh.fit.socialservice.domain.enums.PostStatus;
import iuh.fit.socialservice.domain.enums.PostVisibility;
import iuh.fit.socialservice.domain.enums.ReactionType;
import iuh.fit.socialservice.domain.service.PostTags;
import iuh.fit.socialservice.infrastructure.persistence.entity.PlayerCallDoc;
import iuh.fit.socialservice.infrastructure.persistence.entity.PostCommentEntity;
import iuh.fit.socialservice.infrastructure.persistence.entity.PostEntity;
import iuh.fit.socialservice.infrastructure.persistence.entity.PostReactionEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * Cộng đồng mô phỏng (dev / demo): {@link #POST_DAYS} ngày bài viết của người chơi mô phỏng — khoe trận, tìm người,
 * hỏi sân — kèm lượt thích, lưu, bình luận, chia sẻ, cùng mạng theo dõi / bạn bè. Lượng bài tăng dần theo thời gian
 * và đông hơn cuối tuần, để bảng tin, xếp hạng cá nhân hóa và dashboard cộng đồng có số liệu thật.
 *
 * <p>Tất định theo ngày; chạy lại chỉ bổ sung các ngày còn thiếu. Bật bằng {@code app.demo-data.simulation.enabled=true}.
 */
@Slf4j
@Component
@Order(21)
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.demo-data.simulation", name = "enabled", havingValue = "true")
public class DemoSimulationSeeder implements CommandLineRunner {
    static final int POST_DAYS = DemoPopulation.HISTORY_DAYS;
    /** Bài trong bấy nhiêu ngày gần nhất vẫn nhận thêm tương tác ở lần chạy sau. */
    private static final int ENGAGE_DAYS = 2;
    private static final String CREATED_BY = "demo-sim";

    private final MongoTemplate mongo;
    private final JdbcTemplate jdbc;

    private static final Map<String, String[]> POSTS = Map.of(
            "FOOTBALL", new String[]{
                    "Trận phủi tối nay thắng 5-3, cả team đá cháy quá! #bongda #phui #sanco",
                    "Cuối tuần này team mình cần thêm 2 bạn đá cánh, ai rảnh inbox nhé #timdoi #bongda",
                    "Sân cỏ nhân tạo mới thay, bóng lăn đẹp hẳn. Anh em nên thử #sanbong #bongda",
                    "Ghi được cú vô lê đầu tiên trong đời, hạnh phúc nguyên ngày #bongda #highlight",
                    "Đá 7 người hay 5 người vui hơn? Team mình đang tranh cãi #bongda #thaoluan",
                    "Giữ sức cho giải phong trào tháng sau, tập thể lực 3 buổi/tuần #bongda #giaidau"},
            "BADMINTON", new String[]{
                    "Đánh đôi nam nữ tối nay, set cuối 21-19 nghẹt thở #caulong #doinamnu",
                    "Ai có kinh nghiệm chọn vợt cho người mới chơi không? #caulong #hoidap",
                    "Sáng chủ nhật đánh cầu xong đi ăn phở, combo hoàn hảo #caulong #cuoituan",
                    "Tập smash cả tháng cuối cùng cũng có lực hơn #caulong #luyentap",
                    "Tìm bạn đánh đơn trình trung bình, khu vực gần nhà #caulong #timdoi",
                    "Sân thảm mới êm chân, đánh 2 tiếng không mỏi gối #caulong #sancaulong"},
            "PICKLEBALL", new String[]{
                    "Lần đầu chơi pickleball mà ghiền luôn, dễ chơi hơn tưởng tượng #pickleball #nguoimoi",
                    "Kỹ thuật dink ở khu kitchen là chìa khóa thắng điểm #pickleball #kythuat",
                    "Rủ cả nhà cùng chơi pickleball, ai cũng vui #pickleball #giadinh",
                    "Ai đánh pickleball tối thứ 5 không, thiếu 1 người #pickleball #timdoi"},
            "TENNIS", new String[]{
                    "Cú trái tay một tay cuối cùng cũng ổn định #tennis #luyentap",
                    "Đánh tennis buổi sáng sớm trời mát, đông người hơn hẳn #tennis #buoisang",
                    "Tìm bạn đánh đôi trình 3.0, cuối tuần #tennis #timdoi",
                    "Thay dây vợt mới, cảm giác bóng khác hẳn #tennis #dungcu"},
            "BASKETBALL", new String[]{
                    "Đấu 3x3 chiều nay, thua sát nút nhưng vui #bongro #3x3",
                    "Tập ném phạt 100 quả mỗi ngày, tỉ lệ vào đã lên 70% #bongro #luyentap",
                    "Team bóng rổ cần thêm center, ai cao trên 1m80 không #bongro #timdoi"},
            "VOLLEYBALL", new String[]{
                    "Bóng chuyền hơi chiều chủ nhật, cả xóm cùng ra sân #bongchuyen #phongtrao",
                    "Đỡ bước một chuẩn thì đập mới đẹp, tập đi anh em #bongchuyen #kythuat",
                    "Tìm 2 bạn chơi bóng chuyền tối thứ 7 #bongchuyen #timdoi"});
    private static final String[] COMMENTS = {"Đỉnh quá bạn ơi!", "Cho mình tham gia với", "Sân này ở đâu vậy bạn?",
            "Hôm nào làm kèo nữa nhé", "Chúc mừng team!", "Hay quá, lưu lại để thử", "Mình cũng đang tìm chỗ chơi khu này",
            "Giờ đó còn slot không bạn?", "Trình mình mới tập có chơi chung được không?", "Ghé sân này rồi, ổn áp",
            "Hẹn cuối tuần gặp nhé", "Kỹ thuật này hữu ích ghê"};
    private static final String[] SHARE_CAPTIONS = {"Ai đi cùng không?", "Hay nè mọi người", "Lưu lại cho team", ""};

    @Override
    public void run(String... args) {
        seed(LocalDateTime.now());
    }

    /** Mỗi đêm: thêm bài của ngày mới và tương tác tiếp cho các bài vừa đăng. */
    @Scheduled(cron = "0 25 0 * * *", zone = "Asia/Ho_Chi_Minh")
    public void daily() {
        seed(LocalDateTime.now());
    }

    /**
     * Sinh lại toàn bộ {@link #POST_DAYS} ngày, bỏ qua bài đã có (id theo ngày + thứ tự): lần đầu ra đủ lịch sử, các
     * lần sau chỉ thêm bài mới; tăng số bài mỗi ngày thì các ngày cũ cũng được bổ sung. Bài đăng trong
     * {@link #ENGAGE_DAYS} ngày gần nhất được tương tác tiếp (thích, bình luận tới sau).
     */
    void seed(LocalDateTime now) {
        List<DemoPopulation.Player> players = DemoPopulation.players();
        int network = seedNetwork(players);
        LocalDate today = now.toLocalDate();
        LocalDate first = today.minusDays(POST_DAYS);
        List<String> authors = players.stream().map(player -> player.userId().toString()).toList();
        Query recentQuery = Query.query(Criteria.where("authorId").in(authors)
                .and("publishedAt").gte(first.atStartOfDay()));
        recentQuery.fields().include("_id", "authorId", "sport", "publishedAt", "sharedPostId");
        List<PostEntity> existing = mongo.find(recentQuery, PostEntity.class);
        Set<String> existingIds = new HashSet<>();
        existing.forEach(post -> existingIds.add(post.getPostId()));
        Map<String, DemoPopulation.Player> byId = new HashMap<>();
        players.forEach(player -> byId.put(player.userId().toString(), player));

        List<PostEntity> posts = new ArrayList<>();
        List<PostReactionEntity> reactions = new ArrayList<>();
        List<PostCommentEntity> comments = new ArrayList<>();
        int[] activity = players.stream().mapToInt(player -> (int) Math.round(player.bookingsPerWeek() * 10)).toArray();
        for (LocalDate date = first; !date.isAfter(today); date = date.plusDays(1)) {
            Random random = DemoPopulation.random(0x9057, date.toEpochDay());
            double progress = (date.toEpochDay() - first.toEpochDay()) / (double) POST_DAYS;
            boolean weekend = date.getDayOfWeek() == DayOfWeek.SATURDAY || date.getDayOfWeek() == DayOfWeek.SUNDAY;
            int count = (int) Math.round((4 + 12 * progress) * (weekend ? 1.5 : 1) + random.nextGaussian() * 1.5);
            for (int index = 0; index < Math.max(2, count); index++) {
                String postId = id("sim-post", date + "|" + index).toString();
                if (existingIds.contains(postId)) continue;
                DemoPopulation.Player author = players.get(DemoPopulation.weighted(random, activity));
                LocalDateTime publishedAt = date.atTime(6 + random.nextInt(17), random.nextInt(60));
                if (publishedAt.isAfter(now)) continue;
                String sport = author.sports().get(author.sports().size() > 1 && random.nextInt(100) < 30 ? 1 : 0);
                String[] pool = POSTS.get(sport);
                String content = pool[random.nextInt(pool.length)];
                List<String> tags = PostTags.extract(content);
                posts.add(PostEntity.builder().postId(postId).authorId(author.userId().toString()).content(content)
                        .visibility(random.nextInt(100) < 90 ? PostVisibility.PUBLIC : PostVisibility.FRIENDS)
                        .status(PostStatus.PUBLISHED).sport(PostSport.valueOf(sport))
                        .playerCall(content.contains("#timdoi") ? playerCall(random, sport, author, publishedAt, today) : null)
                        .createdAt(publishedAt).updatedAt(publishedAt).publishedAt(publishedAt)
                        .tags(tags).tagKeys(tags.stream().map(PostTags::key).toList()).build());
                engage(engagementRandom(postId), players, author, sport, postId, publishedAt, now, posts, reactions, comments);
            }
        }
        // Bài vừa đăng: tương tác tới sau lần chạy trước (chỉ thêm lượt chưa có).
        LocalDateTime recent = now.minusDays(ENGAGE_DAYS);
        List<String> recentIds = new ArrayList<>();
        for (PostEntity post : existing) {
            DemoPopulation.Player author = byId.get(post.getAuthorId());
            if (author == null || post.getSharedPostId() != null || post.getSport() == null
                    || post.getPublishedAt().isBefore(recent)) continue;
            recentIds.add(post.getPostId());
            engage(engagementRandom(post.getPostId()), players, author, post.getSport().name(), post.getPostId(),
                    post.getPublishedAt(), now, posts, reactions, comments);
        }
        if (!recentIds.isEmpty()) {
            Set<String> seen = new HashSet<>(existingIds);
            mongo.find(Query.query(Criteria.where("postId").in(recentIds)), PostReactionEntity.class)
                    .forEach(item -> seen.add(item.getReactionId()));
            mongo.find(Query.query(Criteria.where("postId").in(recentIds)), PostCommentEntity.class)
                    .forEach(item -> seen.add(item.getCommentId()));
            posts.removeIf(post -> seen.contains(post.getPostId()));
            reactions.removeIf(item -> seen.contains(item.getReactionId()));
            comments.removeIf(item -> seen.contains(item.getCommentId()));
        }
        if (!posts.isEmpty()) mongo.insertAll(posts);
        if (!reactions.isEmpty()) mongo.insertAll(reactions);
        if (!comments.isEmpty()) mongo.insertAll(comments);
        if (!posts.isEmpty() || !reactions.isEmpty()) {
            // Ghi thẳng vào DB không phát sự kiện hành vi: cho lượt nạp bù chạy lại để ai-service học được.
            mongo.remove(Query.query(Criteria.where("_id").is("post-activity-v1")), "activity_backfills");
        }
        log.info("Dữ liệu mô phỏng cộng đồng: {} bài, {} lượt thích/lưu, {} bình luận từ {} tới {} ({} quan hệ mới)",
                posts.size(), reactions.size(), comments.size(), first, today, network);
    }

    /** Tương tác của một bài chỉ phụ thuộc bài đó, nên lần chạy sau sinh lại đúng các lượt cũ và thêm lượt mới tới. */
    private static Random engagementRandom(String postId) {
        UUID key = UUID.fromString(postId);
        return DemoPopulation.random(0xE96A, key.getMostSignificantBits(), key.getLeastSignificantBits());
    }

    /** Thích, lưu, bình luận, chia sẻ từ người cùng thành phố và cùng môn là chính; rải trong 2 ngày sau khi đăng. */
    private static void engage(Random random, List<DemoPopulation.Player> players, DemoPopulation.Player author,
                               String sport, String postId, LocalDateTime publishedAt, LocalDateTime now,
                               List<PostEntity> posts, List<PostReactionEntity> reactions, List<PostCommentEntity> comments) {
        List<DemoPopulation.Player> audience = players.stream()
                .filter(player -> player != author && (player.city() == author.city() || player.sports().contains(sport)))
                .toList();
        // Phân phối lệch: phần lớn bài vài lượt thích, một số ít bài "nổi".
        int likes = (int) Math.min(audience.size(), Math.round(Math.exp(random.nextGaussian() * 0.8 + 1.6)));
        int comment = Math.min(audience.size(), random.nextInt(100) < 55 ? 1 + random.nextInt(Math.max(1, likes / 3 + 1)) : 0);
        Set<Integer> picked = new HashSet<>();
        for (int index = 0; index < likes; index++) {
            int position = random.nextInt(audience.size());
            if (!picked.add(position)) continue;
            DemoPopulation.Player fan = audience.get(position);
            LocalDateTime at = awake(publishedAt.plusMinutes(5 + random.nextInt(48 * 60)));
            if (at.isAfter(now)) continue;
            reactions.add(reaction(postId, fan, ReactionType.LIKE, at));
            if (random.nextInt(100) < 12) reactions.add(reaction(postId, fan, ReactionType.SAVE, at.plusMinutes(1)));
        }
        for (int index = 0; index < comment; index++) {
            DemoPopulation.Player commenter = audience.get(random.nextInt(audience.size()));
            LocalDateTime at = awake(publishedAt.plusMinutes(10 + random.nextInt(24 * 60)));
            if (at.isAfter(now)) continue;
            comments.add(PostCommentEntity.builder().commentId(id("sim-comment", postId + "|" + index).toString())
                    .postId(postId).authorId(commenter.userId().toString())
                    .content(COMMENTS[random.nextInt(COMMENTS.length)]).status(PostStatus.PUBLISHED)
                    .createdAt(at).updatedAt(at).build());
        }
        if (random.nextInt(100) < 6) {
            DemoPopulation.Player sharer = audience.get(random.nextInt(audience.size()));
            LocalDateTime at = publishedAt.plusHours(1 + random.nextInt(24));
            if (at.isAfter(now)) return;
            String caption = SHARE_CAPTIONS[random.nextInt(SHARE_CAPTIONS.length)];
            posts.add(PostEntity.builder().postId(id("sim-share", postId).toString()).authorId(sharer.userId().toString())
                    .content(caption).visibility(PostVisibility.PUBLIC).status(PostStatus.PUBLISHED)
                    .sport(PostSport.valueOf(sport)).sharedPostId(postId)
                    .createdAt(at).updatedAt(at).publishedAt(at).build());
        }
    }

    /** Người dùng ngủ đêm: tương tác rơi vào 0–5h dời sang tối hôm đó (18–23h, giờ chơi thể thao xong) cho giống thật. */
    private static LocalDateTime awake(LocalDateTime at) {
        return at.getHour() < 6 ? at.plusHours(18) : at;
    }

    private static PostReactionEntity reaction(String postId, DemoPopulation.Player user, ReactionType type, LocalDateTime at) {
        return PostReactionEntity.builder().reactionId(id("sim-reaction", postId + "|" + user.email() + "|" + type).toString())
                .postId(postId).userId(user.userId().toString()).type(type).reactedAt(at).build();
    }

    private static PlayerCallDoc playerCall(Random random, String sport, DemoPopulation.Player author,
                                            LocalDateTime publishedAt, LocalDate today) {
        LocalDate playDate = publishedAt.toLocalDate().plusDays(1 + random.nextInt(4));
        int hour = 17 + random.nextInt(4);
        return PlayerCallDoc.builder().sport(PostSport.valueOf(sport))
                .playFormat("FOOTBALL".equals(sport) ? "5v5" : "Đôi")
                .playDate(playDate).startTime(String.format("%02d:00", hour)).endTime(String.format("%02d:00", hour + 2))
                .location(author.district() + ", " + author.city().name()).slots(1 + random.nextInt(3))
                .skillNote("Trình " + author.skill().toLowerCase()).filled(playDate.isBefore(today)).build();
    }

    /**
     * Mỗi người theo dõi 4–12 người cùng thành phố hoặc cùng môn; khoảng 1/3 trong số đó thành bạn bè. Người đã có
     * quan hệ thì bỏ qua, nên tăng quy mô cộng đồng chỉ sinh thêm cho người mới.
     */
    private int seedNetwork(List<DemoPopulation.Player> players) {
        Set<UUID> linked = new HashSet<>(jdbc.queryForList(
                "select distinct follower_id from follows where created_by = ?", UUID.class, CREATED_BY));
        List<Object[]> follows = new ArrayList<>();
        List<Object[]> friendships = new ArrayList<>();
        Set<String> friends = new HashSet<>();
        LocalDateTime base = LocalDateTime.now().minusDays(DemoPopulation.HISTORY_DAYS);
        for (DemoPopulation.Player player : players) {
            if (linked.contains(player.userId())) continue;
            Random random = DemoPopulation.random(0xF011, player.index());
            List<DemoPopulation.Player> near = players.stream()
                    .filter(other -> other != player && (other.city() == player.city()
                            || other.sports().get(0).equals(player.sports().get(0))))
                    .sorted(Comparator.comparingInt(other -> Math.floorMod((player.email() + other.email()).hashCode(), 997)))
                    .limit(4 + random.nextInt(9)).toList();
            for (DemoPopulation.Player other : near) {
                LocalDateTime at = base.plusDays(random.nextInt(DemoPopulation.HISTORY_DAYS));
                follows.add(new Object[]{id("sim-follow", player.email() + "|" + other.email()), player.userId(),
                        other.userId(), at, at});
                String pair = player.index() < other.index() ? player.email() + "|" + other.email() : other.email() + "|" + player.email();
                if (random.nextInt(100) < 33 && friends.add(pair)) {
                    friendships.add(new Object[]{id("sim-friendship", pair), player.userId(), other.userId(), at,
                            at.plusHours(2), at, at});
                }
            }
        }
        int inserted = sum(jdbc.batchUpdate("""
                        insert into follows (follow_id, follower_id, target_type, target_id,
                                             created_at, created_by, updated_at, updated_by)
                        values (?, ?, 'USER', ?, ?, 'demo-sim', ?, 'demo-sim') on conflict do nothing
                        """, follows, 500, (statement, row) -> bind(statement, row)));
        inserted += sum(jdbc.batchUpdate("""
                        insert into friendships (friendship_id, requester_id, addressee_id, status, requested_at,
                                                 responded_at, created_at, created_by, updated_at, updated_by)
                        values (?, ?, ?, 'ACCEPTED', ?, ?, ?, 'demo-sim', ?, 'demo-sim') on conflict do nothing
                        """, friendships, 500, (statement, row) -> bind(statement, row)));
        return inserted;
    }

    private static void bind(PreparedStatement statement, Object[] row) throws SQLException {
        for (int column = 0; column < row.length; column++) statement.setObject(column + 1, row[column]);
    }

    private static int sum(int[][] batches) {
        int total = 0;
        for (int[] batch : batches) for (int count : batch) total += Math.max(count, 0);
        return total;
    }

    private static UUID id(String kind, String key) {
        return UUID.nameUUIDFromBytes(("goatsports:" + kind + ":" + key).getBytes(StandardCharsets.UTF_8));
    }
}
