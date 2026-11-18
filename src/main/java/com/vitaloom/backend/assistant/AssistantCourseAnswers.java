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
    private static final Pattern REPORTED_AMOUNT = Pattern.compile(
            "\\b(?:(?:toi|minh) (?:(?:da|chi|vua) )*(?:ngu|uong)|i (?:slept|drank)) \\d+\\b");
    private static final Context CLARIFICATION = new Context(null, Window.TODAY, Analysis.SUMMARY, null);
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
                        "Hãy bật quyền đọc Sleep/Water trong Cài đặt.", null)), null);
            }
            if (insights == null) return sleepTimingUnavailable();
            var tools = personalTools(owner, text, authorized);
            tools.getRecentMainSleepTiming();
            if (!authorized.getAsBoolean()) throw new AssistantPersonalDataTools.PersonalDataAccessDeniedException();
            return tools.answer();
        }

        var parts = new ArrayList<Part>();
        Intent selected = null;
        String selectedClause = null;
        boolean unknown = false;
        boolean ambiguous = false;
        boolean unresolvedCorrection = false;
        for (String clause : clauses(query)) {
            boolean help = isAppHelp(clause);
            Intent intent = localIntent(clause, previous, true);
            if (intent != null) {
                if (selected != null) ambiguous = true;
                selected = intent;
                selectedClause = clause;
                continue;
            }
            if (mentionsBothTopics(clause) && !help) {
                ambiguous = true;
                continue;
            }
            Article article = retrieve(clause, help);
            if (article == null) {
                if (isContinuation(clause) || correctionIndex(clause) >= 0) {
                    unresolvedCorrection |= correctionIndex(clause) >= 0;
                    parts.add(new Part("INSUFFICIENT_EVIDENCE",
                            "Bạn muốn xem dữ liệu giấc ngủ hay lượng nước cho khoảng đó?", null));
                } else {
                    unknown = true;
                }
            } else {
                parts.add(new Part("ANSWERED", article.answer(), new Source(article.id(), 1, article.title(),
                        article.route(), null, null)));
            }
        }

        Context context = unresolvedCorrection ? CLARIFICATION : null;
        if (ambiguous) {
            parts.add(new Part("INSUFFICIENT_EVIDENCE", "Hãy hỏi một loại dữ liệu cá nhân mỗi lượt.", null));
            context = CLARIFICATION;
        } else if (selected != null) {
            try {
                LocalDate today = LocalDate.now(clock.withZone(ZONE));
                context = resolve(selected, selectedClause, previous, today);
                if (personalAllowed) {
                    if (!authorized.getAsBoolean()) {
                        throw new AssistantPersonalDataTools.PersonalDataAccessDeniedException();
                    }
                    parts.add(read(owner, context, today));
                    if (!authorized.getAsBoolean()) {
                        throw new AssistantPersonalDataTools.PersonalDataAccessDeniedException();
                    }
                } else {
                    parts.add(new Part("PERMISSION_REQUIRED", "Hãy bật quyền đọc Sleep/Water trong Cài đặt.", null));
                }
            } catch (DateTimeException invalid) {
                parts.add(new Part("INSUFFICIENT_EVIDENCE",
                        "Hãy chọn một ngày rõ ràng, tuần này hoặc Giấc ngủ gần đây.", null));
            } catch (DataAccessException unavailable) {
                parts.add(new Part("DEPENDENCY_UNAVAILABLE", "Tạm thời không đọc được dữ liệu cá nhân.", null));
            }
        }
        if (unknown) parts.add(unsupported().parts().getFirst());
        if (parts.isEmpty()) return unsupported();
        return answerWithParts(parts, context);
    }

    private static Answer answerWithParts(List<Part> parts, Context context) {
        String outcome = parts.stream().map(Part::status).distinct().count() > 1
                ? "PARTIAL" : parts.getFirst().status();
        return new Answer(outcome, List.copyOf(parts), context);
    }

    private Part sleepPeriod(String owner, Context context, LocalDate today) {
        var report = sleep.recent(owner);
        boolean calendarWeek = context.window() == Window.WEEK_TO_DATE;
        LocalDate start = calendarWeek ? monday(today) : report.period().start();
        LocalDate end = calendarWeek ? today : report.period().end();
        var source = source(calendarWeek ? "sleep-week-to-date" : "sleep-recent",
                calendarWeek ? "Giấc ngủ trong tuần này" : "Giấc ngủ gần đây", "/sleep/recent", start, end);
        if (!"VERIFIED".equals(report.dataStatus())) return absent(report.dataStatus(), source);
        var rows = report.days().stream().filter(day -> !day.day().isBefore(start) && !day.day().isAfter(end))
                .sorted(java.util.Comparator.comparing(SleepRecentStatisticsService.Day::day)).toList();
        var recorded = rows.stream().filter(AssistantCourseAnswers::recorded).toList();
        if (recorded.isEmpty()) return absent("NO_DATA", source);
        String days = rows.stream().map(day -> day.day() + ": " + (recorded(day) ? day.minutes() + " phút"
                        : "NOT_RECORDED".equals(day.status()) ? "Chưa ghi nhận" : "Chưa thể xác minh"))
                .collect(java.util.stream.Collectors.joining("\n"));
        String summary = calendarWeek ? "Có bản ghi cho %d/%d ngày từ thứ Hai đến hôm nay."
                .formatted(recorded.size(), rows.size())
                : "Có %d/%d ngày được ghi nhận, trung bình %d phút/ngày có bản ghi."
                        .formatted(report.recordedDays(), report.coverageDenominator(),
                                report.averageMinutesPerRecordedDay());
        if (context.analysis() != Analysis.SUMMARY) {
            if (recorded.size() == 1) {
                summary += " Chỉ có một ngày có bản ghi, chưa đủ để nhận xét về thay đổi qua các ngày.";
            } else {
                boolean varied = recorded.stream().anyMatch(day -> !day.minutes().equals(recorded.getFirst().minutes()));
                summary += varied ? " Thời lượng thay đổi giữa các ngày đã ghi."
                        : " Thời lượng bằng nhau ở các ngày đã ghi.";
            }
            summary += " Dữ liệu thời lượng chưa đủ để kết luận về chất lượng giấc ngủ hay dấu hiệu bất thường.";
        }
        String status = context.analysis() == Analysis.ANOMALY && recorded.size() == 1 ? "PARTIAL" : "ANSWERED";
        return new Part(status, summary + "\n" + days, source);
    }

    private static boolean recorded(SleepRecentStatisticsService.Day day) {
        return "RECORDED".equals(day.status()) && day.minutes() != null;
    }

    private static Part explainSleepDay(Part part, Analysis analysis) {
        if (analysis == Analysis.SUMMARY || !"ANSWERED".equals(part.status())) return part;
        String note = analysis == Analysis.ANOMALY
                ? "Một bản ghi thời lượng chưa đủ để xác định thay đổi bất thường so với các ngày khác."
                : "Đây là thời lượng giấc ngủ chính đã ghi. Thời lượng riêng lẻ chưa cho biết chất lượng giấc ngủ.";
        return new Part(analysis == Analysis.ANOMALY ? "PARTIAL" : part.status(),
                part.text() + " " + note, part.source());
    }

    private static Context resolve(Intent topic, String query, Context previous, LocalDate today) {
        boolean inherit = previous != null && isSleep(topic) == isSleep(previous.intent());
        boolean thisWeek = has(query, "tuan nay", "this week", "week to date");
        boolean lastWeek = has(query, "tuan truoc", "last week");
        boolean singleDay = isExplicitDay(query);
        boolean recent = has(query, "gan day", "7 ngay", "recent", "last 7 days")
                || isSleep(topic) && !thisWeek && !lastWeek && !singleDay && has(query, "trung binh", "average");
        boolean week = thisWeek || lastWeek || !isSleep(topic) && has(query, "tuan", "week");
        if ((isSleep(topic) && lastWeek) || (singleDay && (thisWeek || recent)) || (recent && week)) {
            throw new DateTimeException("Ambiguous or unsupported period");
        }
        Intent intent;
        Window window = Window.TODAY;
        if (isSleep(topic) && (thisWeek || recent)) {
            intent = Intent.SLEEP_RECENT;
            window = thisWeek ? Window.WEEK_TO_DATE : Window.RECENT;
        } else if (week) {
            intent = Intent.WATER_WEEK;
        } else if (singleDay) {
            intent = isSleep(topic) ? Intent.SLEEP_DAY : Intent.WATER_DAY;
        } else if (inherit) {
            intent = previous.intent();
            window = previous.window();
        } else {
            intent = topic;
        }
        boolean hasPeriod = singleDay || week || recent;
        LocalDate date = inherit && !hasPeriod && previous.date() != null
                ? previous.date() : date(query, today, intent);
        if (intent == Intent.SLEEP_RECENT) date = null;
        Analysis analysis = isSummaryQuestion(query) ? Analysis.SUMMARY
                : isAnomalyQuestion(query) ? Analysis.ANOMALY
                : isMeaningQuestion(query) || isSleepGuidanceQuestion(query) ? Analysis.MEANING
                : inherit ? previous.analysis() : Analysis.SUMMARY;
        return new Context(intent, window, analysis, date);
    }

    private static String[] clauses(String text) {
        return text.split("[;]|\\s+(?:va|and)\\s+");
    }

    private static boolean isAppHelp(String query) {
        return has(query, "o dau", "cach ", "how to ", "where ", "mo ", "open ", "how is it calculated");
    }

    private static Intent localIntent(String query, Context previous, boolean allowBareTopic) {
        if (isAppHelp(query)) return null;
        int correction = correctionIndex(query);
        String positive = correction >= 0 ? query.substring(0, correction) : query;
        Intent explicitTopic = intent(positive);
        if (correction >= 0) return explicitTopic;
        if (mentionsBothTopics(query)) return null;
        if (explicitTopic != null) {
            if (isGeneralRecommendation(query)) return null;
            return isPersonalRecordsQuestion(query) || isExplicitPeriod(query)
                    || allowBareTopic && !isSleepGuidanceQuestion(query)
                    ? explicitTopic : null;
        }
        return previous != null && isContinuation(query) ? previous.intent() : null;
    }

    private static int correctionIndex(String query) {
        for (String marker : List.of("khong phai ", "not about ", "not water", "not sleep")) {
            int index = query.indexOf(marker);
            if (index >= 0) return index;
        }
        return -1;
    }

    private static boolean mentionsBothTopics(String query) {
        String words = words(query);
        return has(words, " ngu ", " sleep ", " slept ") && has(words, " nuoc ", " water ");
    }

    private static boolean isSleep(Intent intent) {
        return intent == Intent.SLEEP_DAY || intent == Intent.SLEEP_RECENT;
    }

    private static boolean isExplicitPeriod(String query) {
        return isExplicitDay(query) || has(query, "tuan nay", "tuan truoc", "this week", "last week",
                "week to date", "gan day", "7 ngay", "recent");
    }

    private static boolean isExplicitDay(String query) {
        return DATE.matcher(query).find() || has(query, "hom nay", "hom qua", "today", "yesterday");
    }

    private static boolean isContinuation(String query) {
        if (isSummaryQuestion(query)) return true;
        String normalized = query.replaceAll("[?,.!]", "").strip();
        if (normalized.matches("(?:con |trong |vao |what about |on |in )?(?:ngay )?"
                + "(?:hom nay|hom qua|tuan nay|tuan truoc|gan day|7 ngay qua|today|yesterday|"
                + "this week|last week|last 7 days|\\d{4}-\\d{2}-\\d{2})(?: thi sao| thi the nao)?")) return true;
        String withoutPeriod = normalized.replaceAll("(?:trong )?(?:tuan nay|tuan truoc|hom nay|hom qua|"
                + "this week|last week|today|yesterday)", "").replaceAll("\\s+", " ").strip();
        return withoutPeriod.matches("(?:co )?bat thuong(?: gi)?(?: khong)?|(?:is |anything )?abnormal|"
                + "any anomalies|co y nghia gi|noi len dieu gi|dieu do (?:co y nghia gi|noi len dieu gi)|"
                + "what does (?:that|it) mean");
    }

    private static boolean isAnomalyQuestion(String query) {
        return has(query, "bat thuong", "abnormal", "unusual", "irregular", "anomalies");
    }

    private static boolean isSummaryQuestion(String query) {
        return has(query, "chi cho toi xem so lieu", "chi xem so lieu", "chi can so lieu",
                "show me the numbers", "just show the numbers");
    }

    private static boolean isMeaningQuestion(String query) {
        return has(query, "noi len dieu gi", "y nghia", "what does", "meaning");
    }

    private static boolean isSleepGuidanceQuestion(String query) {
        return has(query, "suc khoe", "healthy", "good for", "du giac", "du ngu", "ngu du", "enough sleep")
                || has(words(query), " nen ", " should ", " khuyen nghi ", " recommended ");
    }

    private static boolean isGeneralRecommendation(String query) {
        return has(words(query), " nen ", " should ", " khuyen nghi ", " recommended ")
                && !has(query, "ban ghi", "stored record", "saved data", "my log")
                && !REPORTED_AMOUNT.matcher(query).find();
    }

    private static boolean isPersonalRecordsQuestion(String query) {
        String words = words(query);
        boolean firstPerson = has(words, " toi ", " minh ", " my ", " i ");
        return firstPerson || has(query, "ban ghi", "da ghi", "saved data", "stored record")
                && !has(query, "duoc luu nhu the nao", "how are");
    }

    private static String words(String text) {
        return " " + text.replaceAll("[^a-z0-9 ]", " ") + " ";
    }

    boolean needsSleepGuidance(String text, Answer answer) {
        return answer.context() != null && isSleep(answer.context().intent())
                && isSleepGuidanceQuestion(normalize(text));
    }

    Answer combineSleepGuidance(Answer personal, Answer guidance) {
        var parts = new ArrayList<>(personal.parts());
        parts.addAll(guidance.parts());
        return answerWithParts(parts, personal.context());
    }

    Answer readyFaqLocalRoute(String owner, String text, boolean personalAllowed, Context previous) {
        return readyFaqLocalRoute(owner, text, personalAllowed, previous, () -> true);
    }

    Answer readyFaqLocalRoute(String owner, String text, boolean personalAllowed, Context previous,
            BooleanSupplier authorized) {
        String query = normalize(text);
        if (has(query, "co phieu", "chung khoan", "stock", "share price", "shares", "ticker")) {
            var local = answer(owner, text, false, null);
            if ("SAFETY".equals(local.outcome())) return local;
            return new Answer("INSUFFICIENT_EVIDENCE", List.of(new Part("INSUFFICIENT_EVIDENCE",
                    "Trợ lý không cung cấp giá cổ phiếu hoặc thông tin thị trường trực tiếp.", null)), null);
        }
        var local = answer(owner, text, false, previous, authorized);
        if ("SAFETY".equals(local.outcome())) return local;
        if (isSleepTimingRequest(query)) {
            return personalAllowed ? answer(owner, text, true, previous, authorized) : local;
        }
        boolean ambiguousPersonalRequest = mentionsBothTopics(query) && !isAppHelp(query)
                && !isGeneralRecommendation(query) && (isExplicitPeriod(query) || isPersonalRecordsQuestion(query));
        if (ambiguousPersonalRequest || Arrays.stream(clauses(query)).anyMatch(clause -> !isAppHelp(clause)
                && (localIntent(clause, previous, false) != null || isContinuation(clause)
                        || correctionIndex(clause) >= 0))) {
            return personalAllowed ? answer(owner, text, true, previous, authorized) : local;
        }
        return null;
    }

    static boolean isPersonalDataQuestion(String text) {
        String query = normalize(text);
        return !isGeneralRecommendation(query) && intent(query) != null
                && has(query, "cua toi", "cua minh", "toi ngu", "toi uong", "minh ngu", "minh uong",
                        "my sleep", "my water", "i slept", "i drank")
                && !has(query, "o dau", "cach ", "how to", "where", "mo ", "open ");
    }

    private static boolean isSleepTimingRequest(String query) {
        return has(query, "gio di ngu", "gio thuc day", "bedtime", "wake time")
                || has(query, "luc may gio", "vao may gio", "what time", "when should i")
                        && has(query, "ngu", "thuc day", "sleep", "go to bed", "wake");
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

    private Part read(String owner, Context context, LocalDate today) {
        LocalDate date = context.date();
        return switch (context.intent()) {
            case SLEEP_DAY -> explainSleepDay(sleepDay(owner, date), context.analysis());
            case SLEEP_RECENT -> sleepPeriod(owner, context, today);
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
        LocalDate monday = monday(today);
        LocalDate date = dates.stream().findFirst().orElse(intent == Intent.WATER_WEEK
                ? (has(query, "tuan truoc", "last week") ? monday.minusWeeks(1) : monday) : today);
        if (date.isAfter(today) || (intent == Intent.WATER_WEEK && date.getDayOfWeek() != DayOfWeek.MONDAY)) {
            throw new DateTimeException("Unsupported date");
        }
        return date;
    }

    private static LocalDate monday(LocalDate date) {
        return date.with(java.time.temporal.TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    private static Intent intent(String query) {
        String words = words(query);
        boolean sleep = has(words, " ngu ", " sleep ", " slept ");
        boolean water = has(words, " nuoc ", " water ");
        if (sleep == water) return null;
        return sleep ? Intent.SLEEP_DAY : Intent.WATER_DAY;
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
    enum Window { TODAY, RECENT, WEEK_TO_DATE }
    enum Analysis { SUMMARY, MEANING, ANOMALY }
    record Context(Intent intent, Window window, Analysis analysis, LocalDate date) {
        @org.springframework.data.annotation.PersistenceCreator
        Context {
            if (window == null) window = intent == Intent.SLEEP_RECENT ? Window.RECENT : Window.TODAY;
            analysis = analysis == null ? Analysis.SUMMARY : analysis;
        }
    }
    record Article(String id, String title, String route, String answer, List<String> aliases) {}
    record Source(String id, int version, String title, String route, String from, String to) {}
    record Part(String status, String text, Source source) {}
    record Answer(String outcome, List<Part> parts, Context context) {}
}
