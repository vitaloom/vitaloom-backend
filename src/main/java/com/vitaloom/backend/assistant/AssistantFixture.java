package com.vitaloom.backend.assistant;

import java.text.Normalizer;
import java.util.List;

final class AssistantFixture {
    static final String QUESTION = "Mình xem nhật ký giấc ngủ ở đâu?";
    static final String ANSWER = "Mở mục Giấc ngủ rồi chọn Nhật ký.";
    static final String PROMPT = prompt(ANSWER);
    static final String SLEEP_QUESTION = "Nhật ký ngủ tổng hợp hôm nay ghi bao nhiêu phút?";
    static final int SLEEP_MINUTES = 423;
    static final String SLEEP_ANSWER = "Nhật ký tổng hợp ghi %d phút ngủ.".formatted(SLEEP_MINUTES);
    static AssistantCourseAnswers.Source sleepSource(String day) {
        return new AssistantCourseAnswers.Source("synthetic-sleep-journal", 1,
                "Nhật ký giấc ngủ tổng hợp", "/sleep/journal", day, day);
    }
    static final Faq JOURNAL = new Faq("app-help-sleep-journal", "Nhật ký giấc ngủ", "/sleep/journal",
            QUESTION, "Tìm chỗ xem lại các lần ngủ đã ghi ở đâu?", ANSWER);
    static final Faq WATER_PROGRESS = new Faq("app-help-water-progress", "Tiến độ nước", "/water/progress",
            "Làm sao xem tiến độ uống nước trong tuần?", "Có chỗ nào xem các lần uống trong tuần không?",
            "Mở Nước rồi chọn Tiến độ.");
    static final Faq WATER_LOG = new Faq("app-help-water-log", "Ghi lượng nước", "/water/add",
            "Ghi một lần uống nước như thế nào?", "Mình vừa uống nước, nhập ở đâu?",
            "Mở Nước rồi chọn thêm lần uống.");
    static final List<Faq> CORPUS = List.of(JOURNAL, WATER_PROGRESS, WATER_LOG);

    record Faq(String id, String title, String route, String question, String paraphrase, String answer) {
        AssistantCourseAnswers.Source source() {
            return new AssistantCourseAnswers.Source(id, 1, title, route, null, null);
        }
    }

    static Faq matchingFaq(String question) {
        String normalized = normalize(question);
        return CORPUS.stream().filter(faq -> normalized.equalsIgnoreCase(faq.question())
                || normalized.equalsIgnoreCase(faq.paraphrase())).findFirst().orElse(null);
    }

    private AssistantFixture() {}

    static String prompt(String evidence) {
        return prompt(QUESTION, evidence);
    }

    static String prompt(String question, String evidence) {
        return "Chỉ trả lời bằng đúng câu sau, không thêm nội dung: "
                + evidence + "\nCâu hỏi: " + question;
    }

    static boolean matchesQuestion(String text) {
        return QUESTION.equalsIgnoreCase(normalize(text));
    }

    static boolean matchesAnswer(String text) {
        return matchesAnswer(ANSWER, text);
    }

    static boolean matchesAnswer(String expected, String text) {
        return text != null && expected.equalsIgnoreCase(normalize(text.replace("**", "")));
    }

    private static String normalize(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFC).strip();
    }
}
