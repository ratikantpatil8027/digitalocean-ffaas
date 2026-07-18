package com.ffaas.api;

import com.ffaas.api.dto.ConditionDto;
import com.ffaas.api.dto.FlagResponse;
import com.ffaas.api.dto.PagedResponse;
import com.ffaas.api.dto.RuleResponse;
import com.ffaas.domain.Operator;
import com.ffaas.service.DuplicateFlagKeyException;
import com.ffaas.service.FlagNotFoundException;
import com.ffaas.service.FlagService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(FlagController.class)
@Import({GlobalExceptionHandler.class, com.ffaas.config.JacksonConfig.class})
class FlagControllerTest {

    private static final String SAMPLE_CREATE = """
            {
              "key": "premium-dashboard",
              "name": "Premium Dashboard",
              "description": "New analytics dashboard for premium users",
              "enabled": true,
              "defaultState": false,
              "rules": [
                {
                  "priority": 0,
                  "serve": true,
                  "conditions": [
                    { "attribute": "subscriptionTier", "operator": "EQ", "value": "premium" },
                    { "attribute": "region", "operator": "IN", "value": ["us-east", "eu-west"] }
                  ]
                }
              ]
            }
            """;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private FlagService flagService;

    @Test
    void shouldCreateFlagAndReturn201WithLocation() throws Exception {
        when(flagService.create(any())).thenReturn(sampleFlagResponse());

        mockMvc.perform(post("/api/v1/flags")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(SAMPLE_CREATE))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", containsString("/api/v1/flags/premium-dashboard")))
                .andExpect(jsonPath("$.key").value("premium-dashboard"))
                .andExpect(jsonPath("$.name").value("Premium Dashboard"))
                .andExpect(jsonPath("$.rules[0].id").exists());
    }

    @Test
    void shouldReturn409WhenKeyAlreadyExists() throws Exception {
        when(flagService.create(any())).thenThrow(new DuplicateFlagKeyException("premium-dashboard"));

        mockMvc.perform(post("/api/v1/flags")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(SAMPLE_CREATE))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.error").value("DUPLICATE_KEY"))
                .andExpect(jsonPath("$.path").value("/api/v1/flags"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    void shouldReturn400WithFieldPathWhenInOperatorHasScalarValue() throws Exception {
        String invalid = """
                {
                  "key": "bad-in-flag",
                  "name": "Bad IN",
                  "enabled": true,
                  "defaultState": false,
                  "rules": [
                    {
                      "priority": 0,
                      "serve": true,
                      "conditions": [
                        { "attribute": "region", "operator": "IN", "value": "us-east" }
                      ]
                    }
                  ]
                }
                """;

        mockMvc.perform(post("/api/v1/flags")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalid))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details[*].field", hasItem(containsString("rules[0].conditions[0].value"))));
    }

    @Test
    void shouldReturn400WhenRulePrioritiesDuplicate() throws Exception {
        String invalid = """
                {
                  "key": "dup-priority-flag",
                  "name": "Dup Priority",
                  "enabled": true,
                  "defaultState": false,
                  "rules": [
                    {
                      "priority": 0,
                      "serve": true,
                      "conditions": [
                        { "attribute": "tier", "operator": "EQ", "value": "a" }
                      ]
                    },
                    {
                      "priority": 0,
                      "serve": false,
                      "conditions": [
                        { "attribute": "tier", "operator": "EQ", "value": "b" }
                      ]
                    }
                  ]
                }
                """;

        mockMvc.perform(post("/api/v1/flags")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalid))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details").isArray())
                .andExpect(jsonPath("$.details[0].field").exists())
                .andExpect(jsonPath("$.details[0].issue").exists());
    }

    @Test
    void shouldReturn400WhenBodyKeyDiffersFromPathOnUpdate() throws Exception {
        String body = """
                {
                  "key": "other-key",
                  "name": "Updated",
                  "enabled": true,
                  "defaultState": false,
                  "rules": []
                }
                """;

        mockMvc.perform(put("/api/v1/flags/premium-dashboard")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details[*].field", hasItem("key")));
    }

    @Test
    void shouldReturn404ForUnknownFlagOnGetUpdateDelete() throws Exception {
        when(flagService.get("missing")).thenThrow(new FlagNotFoundException("missing"));
        when(flagService.update(eq("missing"), any())).thenThrow(new FlagNotFoundException("missing"));
        doThrow(new FlagNotFoundException("missing")).when(flagService).delete("missing");

        mockMvc.perform(get("/api/v1/flags/missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("FLAG_NOT_FOUND"));

        mockMvc.perform(put("/api/v1/flags/missing")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "X",
                                  "enabled": true,
                                  "defaultState": false,
                                  "rules": []
                                }
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("FLAG_NOT_FOUND"));

        mockMvc.perform(delete("/api/v1/flags/missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("FLAG_NOT_FOUND"));
    }

    @Test
    void shouldListFlagsWithPagingEnvelope() throws Exception {
        when(flagService.list(eq(0), eq(20)))
                .thenReturn(new PagedResponse<>(List.of(sampleFlagResponse()), 0, 20, 1));

        mockMvc.perform(get("/api/v1/flags").param("page", "0").param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.items[0].key").value("premium-dashboard"))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalItems").value(1));
    }

    @Test
    void shouldMapUnexpectedExceptionTo500WithSafeMessage() throws Exception {
        when(flagService.get(anyString())).thenThrow(new RuntimeException("secret db password leaked"));

        mockMvc.perform(get("/api/v1/flags/premium-dashboard"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("An unexpected error occurred"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.not(containsString("password"))));
    }

    @Test
    void shouldReturn400WhenRuleElementIsNull() throws Exception {
        String invalid = """
                {
                  "key": "null-rule-flag",
                  "name": "Null Rule",
                  "enabled": true,
                  "defaultState": false,
                  "rules": [null]
                }
                """;

        mockMvc.perform(post("/api/v1/flags")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalid))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details[*].field", hasItem(containsString("rules"))));
    }

    @Test
    void shouldMapMalformedJsonTo400() throws Exception {
        mockMvc.perform(post("/api/v1/flags")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not-json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("MALFORMED_JSON"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.path").value("/api/v1/flags"));
    }

    private static FlagResponse sampleFlagResponse() {
        Instant now = Instant.parse("2026-07-18T10:00:00Z");
        return new FlagResponse(
                "premium-dashboard",
                "Premium Dashboard",
                "New analytics dashboard for premium users",
                true,
                false,
                List.of(new RuleResponse(
                        UUID.fromString("0d5a7c1e-1111-2222-3333-444455556666"),
                        0,
                        true,
                        null,
                        List.of(
                                new ConditionDto("subscriptionTier", Operator.EQ, "premium"),
                                new ConditionDto("region", Operator.IN, List.of("us-east", "eu-west"))
                        ))),
                now,
                now
        );
    }
}
