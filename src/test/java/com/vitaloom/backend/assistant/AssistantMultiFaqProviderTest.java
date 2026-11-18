package com.vitaloom.backend.assistant;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AssistantMultiFaqProviderTest {
    @Test
    void parsesOneToThreeDistinctGroundedParts() {
        assertThat(GeminiAssistantProvider.parseFaqContextReply(
                "{\"decision\":\"MATCH\",\"parts\":[{\"faqId\":\"faq-1\",\"answer\":\"First\"}] }"))
                .containsExactly(new GeminiAssistantProvider.FaqPart("faq-1", "First"));
        assertThat(GeminiAssistantProvider.parseFaqContextReply(
                "{\"decision\":\"MATCH\",\"parts\":[{\"faqId\":\"faq-1\",\"answer\":\"First\"},"
                        + "{\"faqId\":\"faq-2\",\"answer\":\"Second\"},"
                        + "{\"faqId\":\"faq-3\",\"answer\":\"Third\"}]}"))
                .containsExactly(new GeminiAssistantProvider.FaqPart("faq-1", "First"),
                        new GeminiAssistantProvider.FaqPart("faq-2", "Second"),
                        new GeminiAssistantProvider.FaqPart("faq-3", "Third"));
    }

    @Test
    void representsOnlyExplicitNoMatchAsEmpty() {
        assertThat(GeminiAssistantProvider.parseFaqContextReply("{\"decision\":\"NO_MATCH\"}"))
                .isEmpty();
    }

    @Test
    void rejectsMalformedDuplicateOverbudgetAndInvalidParts() {
        for (String invalid : java.util.Arrays.asList(null, "", "prose", "[]", "{\"decision\":\"NO_MATCH\",\"parts\":[]}",
                "{\"decision\":\"MATCH\",\"parts\":[]}",
                "{\"decision\":\"MATCH\",\"parts\":[{\"faqId\":\"x\",\"answer\":\"ok\"},"
                        + "{\"faqId\":\"x\",\"answer\":\"again\"}]}",
                "{\"decision\":\"MATCH\",\"parts\":[{\"faqId\":\"1\",\"answer\":\"a\"},"
                        + "{\"faqId\":\"2\",\"answer\":\"b\"},{\"faqId\":\"3\",\"answer\":\"c\"},"
                        + "{\"faqId\":\"4\",\"answer\":\"d\"}]}",
                "{\"decision\":\"MATCH\",\"parts\":[{\"faqId\":\"x\",\"answer\":\" \"}]}",
                "{\"decision\":\"MATCH\",\"parts\":[{\"faqId\":4,\"answer\":\"ok\"}]}",
                "{\"decision\":\"MATCH\",\"parts\":[{\"faqId\":\"x\",\"answer\":\"ok\",\"extra\":true}]}",
                "{\"decision\":\"MATCH\",\"parts\":[{\"faqId\":\"x\",\"answer\":\"ok\"}],\"extra\":true}",
                "{\"decision\":\"MATCH\",\"parts\":[{\"faqId\":\"x\",\"answer\":\"ok\"}]} trailing")) {
            assertThatThrownBy(() -> GeminiAssistantProvider.parseFaqContextReply(invalid))
                    .isInstanceOf(GeminiAssistantProvider.InvalidFaqResponse.class);
        }
    }
}
