package com.vitaloom.backend.sleep;

import com.vitaloom.backend.sleep.controller.SleepStoryController;
import com.vitaloom.backend.sleep.controller.exceptionhandler.SleepExceptionHandler;
import com.vitaloom.backend.sleep.service.SleepStoryService;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class SleepStoryControllerTest {
    private final SleepStoryService service = mock(SleepStoryService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new SleepStoryController(service))
            .setControllerAdvice(new SleepExceptionHandler()).build();
    private final TestingAuthenticationToken account = new TestingAuthenticationToken("authenticated-owner", "unused");

    @Test
    void storyUsesAuthenticatedSubjectAndOnlyExplicitSingleZone() throws Exception {
        mvc.perform(get("/api/sleep/recent/story").principal(account)).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"));
        verify(service).story("authenticated-owner", null);
        mvc.perform(get("/api/sleep/recent/story").principal(account).param("timeZone", "Asia/Ho_Chi_Minh"))
                .andExpect(status().isOk());
        verify(service).story("authenticated-owner", ZoneId.of("Asia/Ho_Chi_Minh"));
    }

    @Test
    void rejectsInvalidDuplicateOrOwnerSelectingStoryParameters() throws Exception {
        for (String zone : new String[]{"", "Invalid/Zone", "asia/ho_chi_minh"}) {
            mvc.perform(get("/api/sleep/recent/story").principal(account).param("timeZone", zone))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        }
        mvc.perform(get("/api/sleep/recent/story").principal(account).param("timeZone", "UTC", "UTC"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/sleep/recent/story").principal(account).param("owner", "other"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void allPublishedExplanationCodesKeepExistingBodyAndZoneIsA04Only() throws Exception {
        for (String code : SleepStoryService.SUPPORTED_CODES) {
            mvc.perform(post("/api/sleep/ai/explanations").principal(account).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"insightCode\":\"" + code + "\",\"evidenceVersion\":\"detail-v1\"}"))
                    .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"));
            verify(service).explanation("authenticated-owner", code, "detail-v1", null);
        }
        mvc.perform(post("/api/sleep/ai/explanations").principal(account).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"insightCode\":\"A04\",\"timeZone\":\"Asia/Ho_Chi_Minh\"}"))
                .andExpect(status().isOk());
        verify(service).explanation("authenticated-owner", "A04", null, ZoneId.of("Asia/Ho_Chi_Minh"));
    }

    @Test
    void explanationRejectsUnsupportedCodesUnknownFieldsAndUnselectedZones() throws Exception {
        for (String body : new String[]{"{\"insightCode\":\"C01\"}",
                "{\"insightCode\":\"B01\",\"timeZone\":\"UTC\"}",
                "{\"insightCode\":\"A04\",\"timeZone\":null}",
                "{\"insightCode\":\"A04\",\"timeZone\":\"Invalid/Zone\"}",
                "{\"insightCode\":\"B01\",\"owner\":\"other\"}"}) {
            mvc.perform(post("/api/sleep/ai/explanations").principal(account).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        }
        verifyNoInteractions(service);
    }
}
