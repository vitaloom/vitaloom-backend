package com.vitaloom.backend.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.vitaloom.backend.sleep.dto.request.SessionWrite;
import com.vitaloom.backend.sleep.dto.response.SessionResponse;
import com.vitaloom.backend.sleep.service.SleepJournalService;
import com.vitaloom.backend.water.dto.response.WaterLogResponse;
import com.vitaloom.backend.water.service.WaterService;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage.ToolCall;

class AssistantVoiceActionsTest {
    private static final Instant NOW = Instant.parse("2026-11-18T03:00:00Z");
    private final WaterService water = mock(WaterService.class);
    private final SleepJournalService sleep = mock(SleepJournalService.class);
    private final AssistantVoiceActions actions = new AssistantVoiceActions(water, sleep);

    @Test
    void recordsExplicitAmountAtRequestTimeUnderAuthenticatedOwner() {
        when(water.create("account-a", 250, NOW.toString())).thenReturn(new WaterLogResponse("water", 250, NOW));
        var answer = actions.apply("account-a", "Nhập lượng nước 250ml tại thời điểm hiện tại cho tôi", NOW,
                call("recordWater", """
                        {"amount":"250ml","when":"tại thời điểm hiện tại"}
                        """));
        assertThat(answer.outcome()).isEqualTo("ANSWERED");
        assertThat(answer.parts().getFirst().text()).contains("250 ml", "10:00");
        verifyNoInteractions(sleep);
    }

    @Test
    void rejectsInventedAmountAndExplainsMissingWaterData() {
        var answer = actions.apply("account-a", "Nhập lượng nước lúc 8 giờ sáng cho tôi", NOW,
                call("recordWater", """
                        {"amount":"250ml","when":"8 giờ sáng"}
                        """));
        assertThat(answer.outcome()).isEqualTo("INSUFFICIENT_EVIDENCE");
        assertThat(answer.parts().getFirst().text()).contains("Chưa thể ghi lượng nước", "ml");
        verifyNoInteractions(water, sleep);
    }

    @Test
    void doesNotTreatNumbersForTimeOrAFragmentOfAnAmountAsVolume() {
        assertThat(actions.apply("account-a", "Ghi 1250 ml nước lúc 8 giờ sáng", NOW,
                call("recordWater", """
                        {"amount":"250 ml"}
                        """)).outcome()).isEqualTo("INSUFFICIENT_EVIDENCE");
        assertThat(actions.apply("account-a", "Ghi 250 ml nước lúc 8 giờ sáng", NOW,
                call("recordWater", """
                        {"amount":"250 ml"}
                        """)).outcome()).isEqualTo("INSUFFICIENT_EVIDENCE");
        verifyNoInteractions(water, sleep);
    }

    @Test
    void rejectsWhenToolOmitsTheSpokenPartOfDay() {
        var answer = actions.apply("account-a", "Ghi 250 ml nước lúc 8h30 tối", NOW,
                call("recordWater", """
                        {"amount":"250 ml","when":"8h30"}
                        """));
        assertThat(answer.outcome()).isEqualTo("INSUFFICIENT_EVIDENCE");
        verifyNoInteractions(water, sleep);
    }

    @Test
    void createsDurationOnVietnameseDateAndMapsFeeling() {
        when(sleep.create(eq("account-b"), any())).thenReturn(sleepResult("2026-11-17", 480));
        var answer = actions.apply("account-b", "Nhập 8 tiếng ngủ cho ngày 17/11. Cảm giác bình thường", NOW,
                call("recordSleep", """
                        {"duration":"8 tiếng","day":"ngày 17/11","feeling":"bình thường"}
                        """));
        assertThat(answer.outcome()).isEqualTo("ANSWERED");
        var write = ArgumentCaptor.forClass(SessionWrite.class);
        verify(sleep).create(eq("account-b"), write.capture());
        assertThat(write.getValue().endDay()).isEqualTo("2026-11-17");
        assertThat(write.getValue().minutes().intValue()).isEqualTo(480);
        assertThat(write.getValue().feeling()).isEqualTo("NORMAL");
    }

    @Test
    void createsRangeAcrossMidnightInVietnamTimezone() {
        when(sleep.create(eq("account-b"), any())).thenReturn(sleepResult("2026-11-18", 400));
        var answer = actions.apply("account-b",
                "Nhập cho tôi thời gian ngủ từ 11h20 tối hôm qua đến 6h sáng hôm nay, cảm giác tốt", NOW,
                call("recordSleep", """
                        {"start":"11h20 tối hôm qua","end":"6h sáng hôm nay","feeling":"tốt"}
                        """));
        assertThat(answer.outcome()).isEqualTo("ANSWERED");
        var write = ArgumentCaptor.forClass(SessionWrite.class);
        verify(sleep).create(eq("account-b"), write.capture());
        assertThat(write.getValue().timeZone()).isEqualTo("Asia/Ho_Chi_Minh");
        assertThat(write.getValue().start()).isEqualTo(new SessionWrite.CivilTime("2026-11-17", "23", "20", null));
        assertThat(write.getValue().end()).isEqualTo(new SessionWrite.CivilTime("2026-11-18", "06", "00", null));
        assertThat(write.getValue().feeling()).isEqualTo("COMFORTABLE");
    }

    @Test
    void acceptsTranscriptWithSpelledOutClockTimesAndDate() {
        when(sleep.create(eq("account-b"), any())).thenReturn(sleepResult("2026-11-18", 400));
        var answer = actions.apply("account-b",
                "Ghi giấc ngủ từ mười một giờ hai mươi tối ngày mười bảy tháng mười một đến sáu giờ sáng hôm nay, cảm giác tốt", NOW,
                call("recordSleep", """
                        {"start":"mười một giờ hai mươi tối ngày mười bảy tháng mười một",
                         "end":"sáu giờ sáng hôm nay","feeling":"tốt"}
                        """));
        assertThat(answer.outcome()).isEqualTo("ANSWERED");
        var write = ArgumentCaptor.forClass(SessionWrite.class);
        verify(sleep).create(eq("account-b"), write.capture());
        assertThat(write.getValue().start()).isEqualTo(new SessionWrite.CivilTime("2026-11-17", "23", "20", null));
        assertThat(write.getValue().end()).isEqualTo(new SessionWrite.CivilTime("2026-11-18", "06", "00", null));
    }

    @Test
    void rejectsOmittedFeelingAndUnlistedToolOrArguments() {
        assertThat(actions.apply("account-a", "Ghi 8 giờ ngủ hôm nay", NOW,
                call("recordSleep", """
                        {"duration":"8 giờ","day":"hôm nay"}
                        """)).outcome()).isEqualTo("INSUFFICIENT_EVIDENCE");
        assertThat(actions.apply("account-a", "Ghi 250 ml nước", NOW,
                call("deleteWater", "{}")).outcome()).isEqualTo("INSUFFICIENT_EVIDENCE");
        assertThat(actions.apply("account-a", "Ghi 250 ml nước", NOW,
                call("recordWater", """
                        {"amount":"250 ml","owner":"another-account"}
                        """)).outcome()).isEqualTo("INSUFFICIENT_EVIDENCE");
        verifyNoInteractions(water, sleep);
    }

    private static ToolCall call(String name, String arguments) {
        return new ToolCall("call", "function", name, arguments);
    }

    private static SessionResponse sleepResult(String day, int minutes) {
        return new SessionResponse("sleep", NOW, "MAIN", null, null, day, minutes,
                null, null, null, null, null, true);
    }
}
