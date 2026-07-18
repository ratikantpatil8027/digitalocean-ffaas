package com.ffaas.service;

import com.ffaas.api.dto.ConditionDto;
import com.ffaas.api.dto.CreateFlagRequest;
import com.ffaas.api.dto.FlagResponse;
import com.ffaas.api.dto.RuleDto;
import com.ffaas.api.dto.UpdateFlagRequest;
import com.ffaas.domain.Operator;
import com.ffaas.repository.FeatureFlagRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@Import(FlagService.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class FlagServicePersistenceTest {

    @Autowired
    private FlagService flagService;

    @Autowired
    private FeatureFlagRepository repository;

    @Test
    void shouldUpdateFlagReusingSameRulePriorities() {
        flagService.create(new CreateFlagRequest(
                "reuse-priority",
                "Reuse Priority",
                null,
                true,
                false,
                List.of(new RuleDto(
                        0,
                        true,
                        List.of(new ConditionDto("tier", Operator.EQ, "old")),
                        null
                ))
        ));

        FlagResponse updated = flagService.update("reuse-priority", new UpdateFlagRequest(
                null,
                "Reuse Priority",
                "replaced",
                true,
                false,
                List.of(new RuleDto(
                        0,
                        false,
                        List.of(new ConditionDto("tier", Operator.EQ, "new")),
                        null
                ))
        ));

        assertThat(updated.rules()).hasSize(1);
        assertThat(updated.rules().getFirst().priority()).isEqualTo(0);
        assertThat(updated.rules().getFirst().serve()).isFalse();
        assertThat(updated.rules().getFirst().conditions().getFirst().value()).isEqualTo("new");
        assertThat(updated.description()).isEqualTo("replaced");
        assertThat(repository.findByKey("reuse-priority")).isPresent();
    }
}
