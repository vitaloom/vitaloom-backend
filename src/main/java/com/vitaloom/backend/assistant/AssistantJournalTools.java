package com.vitaloom.backend.assistant;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/** Declares the journal actions that Gemini may propose for one conversational turn. */
final class AssistantJournalTools {
    private AssistantJournalTools() {}

    static boolean isJournalTool(String name) {
        return "recordWater".equals(name) || "recordSleep".equals(name);
    }

    static final class Declarations {
        @Tool(name = "recordWater", description = "Propose one new Water log for the current user.")
        public String recordWater(
                @ToolParam(description = "Literal spoken or typed volume including unit, e.g. '250ml'.") String amount,
                @ToolParam(required = false, description = "Literal time with any date, e.g. '8 giờ sáng hôm qua'.") String when) {
            throw new IllegalStateException("Journal declarations cannot execute directly.");
        }

        @Tool(name = "recordSleep", description = "Propose one new Main Sleep log for the current user.")
        public String recordSleep(
                @ToolParam(required = false, description = "Literal sleep duration, e.g. '8 tiếng'.") String duration,
                @ToolParam(required = false, description = "Literal wake day for duration mode, e.g. 'ngày 17/11'.") String day,
                @ToolParam(required = false, description = "Literal range start with date, e.g. '11h20 tối hôm qua'.") String start,
                @ToolParam(required = false, description = "Literal range end with date, e.g. '6h sáng hôm nay'.") String end,
                @ToolParam(description = "Literal feeling, e.g. 'tốt', 'bình thường', or 'mệt mỏi'.") String feeling) {
            throw new IllegalStateException("Journal declarations cannot execute directly.");
        }
    }
}
