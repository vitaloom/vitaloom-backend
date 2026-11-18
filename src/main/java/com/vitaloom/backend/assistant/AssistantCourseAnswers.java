package com.vitaloom.backend.assistant;

import com.vitaloom.backend.sleep.service.SleepJournalService;
import com.vitaloom.backend.sleep.dto.response.SessionResponse;
import com.vitaloom.backend.statistics.service.SleepRecentStatisticsService;
import com.vitaloom.backend.statistics.service.SleepInsightsStatisticsService;
import com.vitaloom.backend.water.service.WaterService;
import java.text.Normalizer;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;
import org.springframework.context.annotation.Conditional;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

@Component
@Conditional(AssistantActivation.Course.class)
final class AssistantCourseAnswers {
    private static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final Pattern DATE = Pattern.compile("\\b[0-9]{4}-[0-9]{2}-[0-9]{2}\\b");
    private static final Set<String> STOP_WORDS = Set.of("toi", "minh", "ban", "cho", "cach", "o", "dau",
            "mo", "xem", "trong", "voi", "cua", "lam", "the", "nao", "how", "where", "can", "i", "open", "to");
    private static final List<Article> ARTICLES = List.of(
            new Article("sleep-journal", "Nhật ký giấc ngủ", "/sleep/journal",
                    "Mở mục Giấc ngủ rồi chọn Nhật ký.", List.of("nhat ky giac ngu", "sleep journal", "trang ghi chep giac ngu")),
            new Article("water-progress", "Tiến độ nước", "/water/progress",
                    "Mở mục Nước rồi chọn Tiến độ để xem nhật ký uống nước theo tuần.",
                    List.of("tien do nuoc", "water progress")),
            new Article("water-log", "Ghi lượng nước", "/water/add",
                    "Mở mục Nước rồi chọn thêm lần uống để ghi lượng nước.",
                    List.of("ghi nuoc", "them nuoc", "add water", "log water")));

    private final SleepJournalService journal;
    private final SleepRecentStatisticsService sleep;
    private final WaterService water;
    private final Clock clock;
    private final SleepInsightsStatisticsService insights;

    AssistantCourseAnswers(SleepJournalService journal, SleepRecentStatisticsService sleep,
            WaterService water, Clock clock) {
        this(journal, sleep, water, clock, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    AssistantCourseAnswers(SleepJournalService journal, SleepRecentStatisticsService sleep,
            WaterService water, Clock clock, SleepInsightsStatisticsService insights) {
        this.journal = journal;
        this.sleep = sleep;
        this.water = water;
        this.clock = clock;
        this.insights = insights;
    }

    Answer answer(String owner, String text, boolean personalAllowed, Context previous) {
        return answer(owner, text, personalAllowed, previous, () -> true);
    }

    Answer answer(String owner, String text, boolean personalAllowed, Context previous,
            BooleanSupplier authorized) {
        String query = normalize(text);
        if (has(query, "khong tho duoc", "dau nguc", "tu tu", "tu sat", "qua lieu",
                "cap cuu", "chest pain", "can't breathe", "suicide", "overdose")) {
            return new Answer("SAFETY", List.of(new Part("SAFETY",
                    "Nếu đang có nguy hiểm tức thời, hãy liên hệ dịch vụ cấp cứu địa phương ngay.", null)), null);
        }
        if (isPrescriptionQuery(query)) {
            return prescriptionRefusal();
        }
        if (isSleepTimingRequest(query)) {
            if (!personalAllowed) {
                String status = "PERMISSION_REQUIRED";
                return new Answer(status, List.of(new Part(status,
                        "Hãy bật quyền đọc Sleep/Water cho hội thoại này.", null)), null);
            }
            if (insights == null) return sleepTimingUnavailable();
            var tools = personalTools(owner, text, authorized);
            tools.getRecentMainSleepTiming();
            if (!authorized.getAsBoolean()) throw new AssistantPersonalDataTools.PersonalDataAccessDeniedException();
            return tools.answer();
        }

        var parts = new ArrayList<Part>();
        String[] clauses = query.split("[;]|\\s+(?:va|and)\\s+");
        Intent intent = null;
        String intentClause = null;
        boolean unknown = false;
        boolean ambiguous = false;
        for (String clause : clauses) {
            boolean help = has(clause, "o dau", "cach ", "how ", "where ", "mo ", "open ");
            Article article = retrieve(clause, help);
            if (article != null) {
                parts.add(new Part("ANSWERED", article.answer(), new Source(article.id(), 1, article.title(), article.route(),
                        null, null)));
                continue;
            }
            Intent detected = help ? null : intent(clause);
            if (detected == null && previous != null && has(clause, "hom qua", "hom nay", "yesterday", "today", "con ngay")) {
                detected = previous.intent();
            }
            if (detected == null) {
                unknown = true;
            } else if (intent != null) {
                ambiguous = true;
            } else {
                intent = detected;
                intentClause = clause;
            }
        }

        Context context = null;
        if (ambiguous) {
            parts.add(new Part("INSUFFICIENT_EVIDENCE", "Hãy hỏi một loại dữ liệu cá nhân mỗi lượt.", null));
        } else if (intent != null) {
            if (!personalAllowed) {
                parts.add(new Part("PERMISSION_REQUIRED", "Hãy bật quyền đọc Sleep/Water cho hội thoại này.", null));
            } else {
                try {
                    if (!authorized.getAsBoolean()) {
                        parts.add(new Part("PERMISSION_REQUIRED", "Hãy bật quyền đọc Sleep/Water trong Cài đặt.", null));
                        return new Answer("PERMISSION_REQUIRED", List.copyOf(parts), null);
                    }
                    LocalDate today = LocalDate.now(clock.withZone(ZONE));
                    LocalDate date = date(intentClause, today, intent);
                    parts.add(read(owner, intent, date, today));
                    if (!authorized.getAsBoolean()) {
                        throw new AssistantPersonalDataTools.PersonalDataAccessDeniedException();
                    }
                    context = new Context(intent);
                } catch (DateTimeException invalid) {
                    parts.add(new Part("INSUFFICIENT_EVIDENCE",
                            "Hãy dùng một ngày YYYY-MM-DD. Tuần nước bắt đầu vào thứ Hai và Giấc ngủ gần đây luôn là 7 ngày đến hôm nay.", null));
                } catch (DataAccessException unavailable) {
                    parts.add(new Part("DEPENDENCY_UNAVAILABLE", "Tạm thời không đọc được dữ liệu cá nhân.", null));
                }
            }
        }
        if (unknown) parts.add(unsupported().parts().getFirst());
        if (parts.isEmpty()) return unsupported();
        String outcome = parts.getFirst().status();
        if (parts.stream().map(Part::status).distinct().count() > 1) outcome = "PARTIAL";
        return new Answer(outcome, List.copyOf(parts), context);
    }

    Answer readyFaqLocalRoute(String owner, String text, boolean personalAllowed, Context previous) {
        return readyFaqLocalRoute(owner, text, personalAllowed, previous, () -> true);
    }

    Answer readyFaqLocalRoute(String owner, String text, boolean personalAllowed, Context previous,
            BooleanSupplier authorized) {
        String query = normalize(text);
        var local = answer("", text, false, null, authorized);
        if ("SAFETY".equals(local.outcome())) return local;
        if (isPrescriptionQuery(query)) return prescriptionRefusal();
        if (isSleepTimingRequest(query)) {
            return personalAllowed ? null : local;
        }
        if (has(query, "co phieu", "chung khoan", "stock", "share price", "shares", "ticker")) {
            return new Answer("INSUFFICIENT_EVIDENCE", List.of(new Part("INSUFFICIENT_EVIDENCE",
                    "Trợ lý không cung cấp giá cổ phiếu hoặc thông tin thị trường trực tiếp.", null)), null);
        }
        if (isPersonalRecentSleep(query) && !has(query, "trung binh", "average")) {
            return answer(owner, text, personalAllowed, previous, authorized);
        }
        return null;
    }

    private static boolean isPersonalRecentSleep(String query) {
        return java.util.Arrays.stream(query.split("[;]|\\s+(?:va|and)\\s+"))
                .anyMatch(clause -> {
                    String words = clause.replaceAll("[^a-z0-9 ]", " ").replaceAll("\\s+", " ").strip();
                    if (words.equals("giac ngu gan day")) return true;
                    if (intent(clause) != Intent.SLEEP_RECENT) return false;
                    return has(" " + words + " ", " cua toi ", " cua minh ", " my sleep ",
                            " my recent sleep ", " i slept ", " i sleep ", " toi ngu ", " toi da ngu ",
                            " minh ngu ", " minh da ngu ");
                });
    }

    static boolean isPersonalDataQuestion(String text) {
        String query = normalize(text);
        if (isSleepTimingRequest(query)) return true;
        return intent(query) != null
                && has(query, "cua toi", "cua minh", "toi ngu", "toi uong", "minh ngu", "minh uong",
                        "my sleep", "my water", "i slept", "i drank")
                && !has(query, "o dau", "cach ", "how to", "where", "mo ", "open ");
    }

    private static boolean isSleepTimingRequest(String query) {
        return has(query, "nen ngu", "nen di ngu", "nen thuc", "nen day", "should i sleep",
                "should i go to bed", "should i wake", "gio di ngu", "gio thuc day", "bedtime", "wake time")
                || has(query, "luc may gio", "vao may gio") && has(query, "ngu", "thuc day");
    }

    private static Answer sleepTimingUnavailable() {
        String status = "INSUFFICIENT_EVIDENCE";
        return new Answer(status, List.of(new Part(status,
                "Chưa thể lấy thống kê giờ ngủ và giờ thức.", null)), null);
    }

    AssistantPersonalDataTools personalTools(String owner, String text, java.util.function.BooleanSupplier current) {
        return new AssistantPersonalDataTools(owner, new AssistantPersonalDataService(sleep, water, clock),
                current, insights, isSleepTimingRequest(normalize(text)) ? ZONE : null);
    }

    String personalToolQuestion(String question) {
        return "Local date (Asia/Ho_Chi_Minh): " + LocalDate.now(clock.withZone(ZONE)) + "\n" + question;
    }

    private static boolean isPrescriptionQuery(String query) {
        return has(query, "chan doan", "ke don", "don thuoc", "lieu thuoc", "nen uong thuoc",
                "can uong thuoc", "thuoc nao nen uong", "what medicine", "which medicine", "what medication",
                "which medication", "diagnose", "prescribe", "prescription", "dosage", "what dose");
    }

    private static Answer prescriptionRefusal() {
        return new Answer("SAFETY", List.of(new Part("SAFETY",
                "Trợ lý không thể kê đơn hoặc hướng dẫn liều thuốc. Hãy hỏi chuyên gia y tế.", null)), null);
    }

    private Part read(String owner, Intent intent, LocalDate date, LocalDate today) {
        return switch (intent) {
            case SLEEP_DAY -> sleepDay(owner, date);
            case SLEEP_RECENT -> {
                var report = sleep.recent(owner);
                var source = source("sleep-recent", "Giấc ngủ gần đây", "/sleep/recent",
                        report.period().start(), report.period().end());
                if (!"VERIFIED".equals(report.dataStatus())) {
                    yield absent(report.dataStatus(), source);
                }
                String days = report.days().stream().sorted(java.util.Comparator.comparing(
                                SleepRecentStatisticsService.Day::day))
                        .map(day -> day.day() + ": " + ("RECORDED".equals(day.status()) && day.minutes() != null
                                ? day.minutes() + " phút" : "NOT_RECORDED".equals(day.status())
                                        ? "Chưa ghi nhận" : "Chưa thể xác minh"))
                        .reduce((first, next) -> first + "\n" + next).orElse("");
                yield new Part("ANSWERED", "Có %d/%d ngày được ghi nhận, trung bình %d phút/ngày có bản ghi.\n%s"
                        .formatted(report.recordedDays(), report.coverageDenominator(),
                                report.averageMinutesPerRecordedDay(), days), source);
            }
            case WATER_DAY -> {
                var summary = water.dailySummary(owner, date.toString(), ZONE.getId());
                var source = source("water-day", "Nhật ký nước", "/water/progress", date, date);
                if (summary.logs().isEmpty()) yield absent("NO_DATA", source);
                yield new Part("PARTIAL", "%d ml từ %d lần uống đã lưu trên máy chủ. Chưa xác minh mục tiêu và độ đầy đủ khi đồng bộ."
                        .formatted(summary.totalMl(), summary.logs().size()), source);
            }
            case WATER_WEEK -> {
                var report = water.weeklyProgress(owner, date.toString(), ZONE.getId());
                var source = source("water-week", "Tiến độ nước", "/water/progress", date, date.plusDays(6));
                String lines = report.days().stream().filter(day -> !LocalDate.parse(day.date()).isAfter(today))
                        .map(day -> day.date() + ": " + (day.totalMl() > 0 ? day.totalMl() + " ml" : "chưa có bản ghi"))
                        .reduce((first, next) -> first + "\n" + next).orElse("");
                yield new Part("PARTIAL", lines + "\nDữ liệu đã lưu trên máy chủ. Chưa xác minh độ đầy đủ hoặc mục tiêu.", source);
            }
        };
    }

    boolean hasSyntheticSleepFixture(String owner, String day) {
        var records = mainRecords(owner, day);
        if (records.size() != 1) return false;
        var row = records.getFirst();
        return row.confirmedAt() != null && "DURATION".equals(row.method())
                && row.minutes() == AssistantFixture.SLEEP_MINUTES && row.feeling() == null;
    }

    private List<SessionResponse> mainRecords(String owner, String day) {
        return journal.list(owner, day, day).stream().filter(row -> "MAIN".equals(row.sleepType())).toList();
    }

    private Part sleepDay(String owner, LocalDate date) {
        var source = source("sleep-day", "Nhật ký giấc ngủ", "/sleep/journal", date, date);
        var records = mainRecords(owner, date.toString());
        if (records.isEmpty()) return absent("NO_DATA", source);
        var row = records.getFirst();
        if (records.size() != 1 || row.confirmedAt() == null || row.minutes() <= 0 || row.minutes() > 1440
                || !List.of("RANGE", "DURATION").contains(row.method() == null ? "" : row.method())) {
            return absent("UNVERIFIED_DATA", source);
        }
        String detail = "%d phút (%s)".formatted(row.minutes(), row.method());
        if ("RANGE".equals(row.method())) {
            if (row.startAt() == null || row.endAt() == null || row.timeZone() == null
                    || !Duration.between(row.startAt(), row.endAt()).equals(Duration.ofMinutes(row.minutes()))) {
                return absent("UNVERIFIED_DATA", source);
            }
            try {
                ZoneId zone = ZoneId.of(row.timeZone());
                detail += ", từ " + row.startAt().atZone(zone).toLocalDateTime()
                        + " đến " + row.endAt().atZone(zone).toLocalDateTime() + " (" + zone + ")";
            } catch (DateTimeException invalidZone) {
                return absent("UNVERIFIED_DATA", source);
            }
        }
        String feeling = switch (row.feeling() == null ? "" : row.feeling()) {
            case "COMFORTABLE" -> "thoải mái";
            case "NORMAL" -> "bình thường";
            case "TIRED" -> "mệt";
            default -> null;
        };
        if (feeling != null) detail += ". Cảm nhận: " + feeling;
        return new Part("ANSWERED", detail + ".", source);
    }

    private static Part absent(String status, Source source) {
        return new Part(status, "NO_DATA".equals(status) ? "Chưa có bản ghi cho khoảng thời gian này."
                : "Chưa thể xác minh dữ liệu cho khoảng thời gian này.", source);
    }

    private static Source source(String id, String title, String route, LocalDate start, LocalDate end) {
        return new Source(id, 1, title, route, start.toString(), end.toString());
    }

    private static LocalDate date(String query, LocalDate today, Intent intent) {
        var matcher = DATE.matcher(query);
        var dates = new HashSet<LocalDate>();
        while (matcher.find()) dates.add(LocalDate.parse(matcher.group()));
        if (has(query, "hom qua", "yesterday")) dates.add(today.minusDays(1));
        if (has(query, "hom nay", "today")) dates.add(today);
        if (dates.size() > 1 || (intent == Intent.SLEEP_RECENT && !dates.isEmpty())
                || (query.contains("con ngay") && dates.isEmpty())) {
            throw new DateTimeException("Ambiguous or unsupported date");
        }
        LocalDate monday = today.with(java.time.temporal.TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate date = dates.stream().findFirst().orElse(intent == Intent.WATER_WEEK
                ? (has(query, "tuan truoc", "last week") ? monday.minusWeeks(1) : monday) : today);
        if (date.isAfter(today) || (intent == Intent.WATER_WEEK && date.getDayOfWeek() != DayOfWeek.MONDAY)) {
            throw new DateTimeException("Unsupported date");
        }
        return date;
    }

    private static Intent intent(String query) {
        String words = " " + query.replaceAll("[^a-z0-9 ]", " ") + " ";
        boolean mentionsSleep = has(words, " ngu ", " sleep ");
        boolean mentionsWater = has(words, " nuoc ", " water ");
        if (mentionsSleep && mentionsWater) return null;
        if (mentionsSleep) {
            return has(query, "gan day", "7 ngay", "recent", "last 7 days", "tuan nay", "this week",
                    "trung binh", "average") ? Intent.SLEEP_RECENT : Intent.SLEEP_DAY;
        }
        if (mentionsWater) {
            return has(query, "tuan", "week") ? Intent.WATER_WEEK : Intent.WATER_DAY;
        }
        return null;
    }

    private static Article retrieve(String clause, boolean help) {
        Set<String> query = tokens(clause);
        if (query.isEmpty()) return null;
        Article best = null;
        double bestScore = 0.75;
        boolean tied = false;
        for (Article article : ARTICLES) {
            if (!help && !article.id().equals("water-log")) continue;
            double score = article.aliases().stream().mapToDouble(alias -> {
                if ((" " + clause.replaceAll("[^a-z0-9 ]", " ") + " ").contains(" " + alias + " ")) return 1;
                Set<String> terms = tokens(alias);
                long shared = terms.stream().filter(query::contains).count();
                return shared / Math.sqrt((double) terms.size() * query.size());
            }).max().orElse(0);
            if (score > bestScore) {
                best = article;
                bestScore = score;
                tied = false;
            } else if (score == bestScore) {
                tied = true;
            }
        }
        return tied ? null : best;
    }

    private static Set<String> tokens(String text) {
        var terms = new HashSet<>(Arrays.asList(text.split("[^a-z0-9]+")));
        terms.remove("");
        terms.removeAll(STOP_WORDS);
        return terms;
    }

    private static boolean has(String text, String... phrases) {
        for (String phrase : phrases) {
            if (text.contains(phrase)) return true;
        }
        return false;
    }

    private static String normalize(String text) {
        return Normalizer.normalize(text.toLowerCase(Locale.ROOT).replace('đ', 'd'), Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "").replaceAll("\\s+", " ").strip();
    }

    private static Answer unsupported() {
        return new Answer("INSUFFICIENT_EVIDENCE", List.of(new Part("INSUFFICIENT_EVIDENCE",
                "Hãy hỏi cách dùng VitaLoom hoặc số liệu Sleep/Water đã ghi trong ứng dụng.", null)), null);
    }

    enum Intent { SLEEP_DAY, SLEEP_RECENT, WATER_DAY, WATER_WEEK }
    record Context(Intent intent) {}
    record Article(String id, String title, String route, String answer, List<String> aliases) {}
    record Source(String id, int version, String title, String route, String from, String to) {}
    record Part(String status, String text, Source source) {}
    record Answer(String outcome, List<Part> parts, Context context) {}
}
