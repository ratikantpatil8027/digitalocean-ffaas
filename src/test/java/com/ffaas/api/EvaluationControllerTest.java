package com.ffaas.api;

import com.ffaas.api.dto.EvaluateRequest;
import com.ffaas.api.dto.EvaluateResponse;
import com.ffaas.engine.Reason;
import com.ffaas.service.EvaluationService;
import com.ffaas.service.FlagNotFoundException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(EvaluationController.class)
@Import({GlobalExceptionHandler.class, com.ffaas.config.JacksonConfig.class})
class EvaluationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private EvaluationService evaluationService;

    @Test
    void shouldReturnRuleMatchWithMatchedRuleId() throws Exception {
        UUID ruleId = UUID.fromString("0d5a7c1e-1111-2222-3333-444455556666");
        when(evaluationService.evaluate(eq("premium-dashboard"), any()))
                .thenReturn(new EvaluateResponse("premium-dashboard", true, Reason.RULE_MATCH, ruleId));

        mockMvc.perform(post("/api/v1/flags/premium-dashboard/evaluate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "userId": "user-123",
                                  "attributes": {
                                    "subscriptionTier": "premium",
                                    "region": "us-east"
                                  }
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.flagKey").value("premium-dashboard"))
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.reason").value("RULE_MATCH"))
                .andExpect(jsonPath("$.matchedRuleId").value(ruleId.toString()));
    }

    @Test
    void shouldReturn404ForUnknownFlag() throws Exception {
        when(evaluationService.evaluate(eq("missing"), any()))
                .thenThrow(new FlagNotFoundException("missing"));

        mockMvc.perform(post("/api/v1/flags/missing/evaluate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "userId": "user-123",
                                  "attributes": {}
                                }
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("FLAG_NOT_FOUND"))
                .andExpect(jsonPath("$.path").value("/api/v1/flags/missing/evaluate"));
    }

    @Test
    void shouldReturn400WhenUserIdMissing() throws Exception {
        mockMvc.perform(post("/api/v1/flags/premium-dashboard/evaluate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "attributes": {
                                    "subscriptionTier": "premium"
                                  }
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details[*].field", hasItem(containsString("userId"))));
    }

    @Test
    void shouldReturn400WhenAttributeValueIsList() throws Exception {
        mockMvc.perform(post("/api/v1/flags/premium-dashboard/evaluate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "userId": "user-123",
                                  "attributes": {
                                    "region": ["us-east", "eu-west"]
                                  }
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details[*].field", hasItem(containsString("attributes.region"))));
    }

    @Test
    void shouldDefaultAttributesToEmptyMapWhenAbsent() throws Exception {
        when(evaluationService.evaluate(eq("premium-dashboard"), any()))
                .thenReturn(new EvaluateResponse("premium-dashboard", false, Reason.DEFAULT, null));

        mockMvc.perform(post("/api/v1/flags/premium-dashboard/evaluate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "userId": "user-123"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reason").value("DEFAULT"));

        ArgumentCaptor<EvaluateRequest> captor = ArgumentCaptor.forClass(EvaluateRequest.class);
        verify(evaluationService).evaluate(eq("premium-dashboard"), captor.capture());
        assertThat(captor.getValue().attributes()).isEmpty();
    }
}
