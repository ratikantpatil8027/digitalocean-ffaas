package com.ffaas.service;

import com.ffaas.api.dto.ConditionDto;
import com.ffaas.api.dto.CreateFlagRequest;
import com.ffaas.api.dto.FlagResponse;
import com.ffaas.api.dto.RuleDto;
import com.ffaas.api.dto.UpdateFlagRequest;
import com.ffaas.domain.Condition;
import com.ffaas.domain.FeatureFlag;
import com.ffaas.domain.Operator;
import com.ffaas.domain.Rule;
import com.ffaas.repository.FeatureFlagRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FlagServiceTest {

    @Mock
    private FeatureFlagRepository repository;

    @Mock
    private EntityManager entityManager;

    @InjectMocks
    private FlagService flagService;

    @Test
    void shouldReplaceRulesAtomicallyOnUpdate() {
        FeatureFlag existing = new FeatureFlag();
        existing.setKey("premium-dashboard");
        existing.setName("Old");
        existing.setEnabled(true);
        existing.setDefaultState(false);
        Rule oldRule = new Rule();
        oldRule.setPriority(0);
        oldRule.setServe(true);
        oldRule.setConditions(List.of(new Condition("tier", Operator.EQ, "old")));
        existing.addRule(oldRule);

        when(repository.findByKey("premium-dashboard")).thenReturn(Optional.of(existing));
        when(repository.save(any(FeatureFlag.class))).thenAnswer(inv -> inv.getArgument(0));

        UpdateFlagRequest request = new UpdateFlagRequest(
                null,
                "Premium Dashboard",
                "updated",
                true,
                false,
                List.of(new RuleDto(
                        1,
                        true,
                        List.of(new ConditionDto("tier", Operator.EQ, "new")),
                        null
                ))
        );

        FlagResponse response = flagService.update("premium-dashboard", request);

        ArgumentCaptor<FeatureFlag> captor = ArgumentCaptor.forClass(FeatureFlag.class);
        verify(repository).save(captor.capture());
        FeatureFlag saved = captor.getValue();
        assertThat(saved.getRules()).hasSize(1);
        assertThat(saved.getRules().getFirst().getPriority()).isEqualTo(1);
        assertThat(saved.getRules().getFirst().getConditions().getFirst().value()).isEqualTo("new");
        assertThat(saved.getRules()).noneMatch(r -> r.getPriority() == 0 && "old".equals(
                r.getConditions().isEmpty() ? null : r.getConditions().getFirst().value()));
        assertThat(response.rules()).hasSize(1);
        assertThat(response.name()).isEqualTo("Premium Dashboard");
    }

    @Test
    void shouldCreateFlag() {
        when(repository.existsByKey("new-flag")).thenReturn(false);
        when(repository.save(any(FeatureFlag.class))).thenAnswer(inv -> {
            FeatureFlag flag = inv.getArgument(0);
            Rule rule = flag.getRules().getFirst();
            // simulate generated ids
            setRuleId(rule, UUID.randomUUID());
            return flag;
        });

        CreateFlagRequest request = new CreateFlagRequest(
                "new-flag",
                "New Flag",
                null,
                true,
                false,
                List.of(new RuleDto(0, true, List.of(new ConditionDto("a", Operator.EQ, "1")), null))
        );

        FlagResponse response = flagService.create(request);

        assertThat(response.key()).isEqualTo("new-flag");
        assertThat(response.rules()).hasSize(1);
        verify(repository).save(any(FeatureFlag.class));
    }

    @Test
    void shouldThrowDuplicateFlagKeyWhenExists() {
        when(repository.existsByKey("dup")).thenReturn(true);

        CreateFlagRequest request = new CreateFlagRequest(
                "dup", "Dup", null, true, false, List.of());

        assertThatThrownBy(() -> flagService.create(request))
                .isInstanceOf(DuplicateFlagKeyException.class);
    }

    @Test
    void shouldMapDataIntegrityViolationToDuplicateKey() {
        when(repository.existsByKey("race")).thenReturn(false);
        when(repository.save(any(FeatureFlag.class)))
                .thenThrow(new DataIntegrityViolationException("unique_key"));

        CreateFlagRequest request = new CreateFlagRequest(
                "race", "Race", null, true, false, List.of());

        assertThatThrownBy(() -> flagService.create(request))
                .isInstanceOf(DuplicateFlagKeyException.class);
    }

    @Test
    void shouldThrowNotFoundOnUpdate() {
        when(repository.findByKey("missing")).thenReturn(Optional.empty());

        UpdateFlagRequest request = new UpdateFlagRequest(
                null, "Name", null, true, false, new ArrayList<>());

        assertThatThrownBy(() -> flagService.update("missing", request))
                .isInstanceOf(FlagNotFoundException.class);
    }

    @Test
    void shouldThrowNotFoundOnDelete() {
        when(repository.findByKey("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> flagService.delete("missing"))
                .isInstanceOf(FlagNotFoundException.class);
    }

    private static void setRuleId(Rule rule, UUID id) {
        try {
            var field = Rule.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(rule, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
