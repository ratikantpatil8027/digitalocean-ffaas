package com.ffaas.repository;

import com.ffaas.domain.Condition;
import com.ffaas.domain.FeatureFlag;
import com.ffaas.domain.Operator;
import com.ffaas.domain.Rule;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class FeatureFlagRepositoryTest {

    @Autowired
    private FeatureFlagRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManager entityManager;

    @Test
    void shouldSaveAndReloadFlagWithRulesOrderedByPriority() {
        FeatureFlag flag = newFlag("premium-dashboard", "Premium Dashboard");
        flag.addRule(rule(10, true, List.of(
                new Condition("region", Operator.EQ, "us-east"))));
        flag.addRule(rule(0, true, List.of(
                new Condition("subscriptionTier", Operator.EQ, "premium"),
                new Condition("region", Operator.IN, List.of("us-east", "eu-west")))));

        repository.saveAndFlush(flag);
        entityManager.clear();

        FeatureFlag loaded = repository.findByKey("premium-dashboard").orElseThrow();
        assertThat(loaded.getName()).isEqualTo("Premium Dashboard");
        assertThat(loaded.isEnabled()).isTrue();
        assertThat(loaded.isDefaultState()).isFalse();
        assertThat(loaded.getRules()).hasSize(2);
        assertThat(loaded.getRules().get(0).getPriority()).isEqualTo(0);
        assertThat(loaded.getRules().get(1).getPriority()).isEqualTo(10);
        assertThat(loaded.getCreatedAt()).isNotNull();
        assertThat(loaded.getUpdatedAt()).isNotNull();
    }

    @Test
    void shouldRoundTripConditionsThroughJsonConverter() {
        FeatureFlag flag = newFlag("round-trip", "Round Trip");
        flag.addRule(rule(0, true, List.of(
                new Condition("subscriptionTier", Operator.EQ, "premium"),
                new Condition("region", Operator.IN, List.of("us-east", "eu-west")),
                new Condition("age", Operator.GT, 34),
                new Condition("beta", Operator.EQ, true))));

        repository.saveAndFlush(flag);
        entityManager.clear();

        FeatureFlag loaded = repository.findByKey("round-trip").orElseThrow();
        List<Condition> conditions = loaded.getRules().getFirst().getConditions();
        assertThat(conditions).hasSize(4);
        assertThat(conditions.get(0).attribute()).isEqualTo("subscriptionTier");
        assertThat(conditions.get(0).operator()).isEqualTo(Operator.EQ);
        assertThat(conditions.get(0).value()).isEqualTo("premium");
        assertThat(conditions.get(1).operator()).isEqualTo(Operator.IN);
        assertThat(conditions.get(1).value()).isEqualTo(List.of("us-east", "eu-west"));
        assertThat(conditions.get(2).operator()).isEqualTo(Operator.GT);
        assertThat(((Number) conditions.get(2).value()).doubleValue()).isEqualTo(34.0);
        assertThat(conditions.get(3).value()).isEqualTo(true);
    }

    @Test
    void shouldRejectDuplicateFlagKey() {
        repository.saveAndFlush(newFlag("dup-key", "First"));

        assertThatThrownBy(() -> repository.saveAndFlush(newFlag("dup-key", "Second")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void shouldCascadeDeleteRulesWhenFlagDeleted() {
        FeatureFlag flag = newFlag("to-delete", "To Delete");
        flag.addRule(rule(0, true, List.of(new Condition("tier", Operator.EQ, "free"))));
        flag.addRule(rule(1, false, List.of(new Condition("tier", Operator.NEQ, "free"))));
        repository.saveAndFlush(flag);

        assertThat(jdbcTemplate.queryForObject("select count(*) from flag_rules", Integer.class)).isEqualTo(2);

        repository.deleteByKey("to-delete");
        repository.flush();

        assertThat(repository.findByKey("to-delete")).isEmpty();
        assertThat(jdbcTemplate.queryForObject("select count(*) from flag_rules", Integer.class)).isEqualTo(0);
    }

    @Test
    void shouldRejectDuplicatePriorityWithinFlag() {
        FeatureFlag flag = newFlag("dup-priority", "Dup Priority");
        flag.addRule(rule(0, true, List.of(new Condition("a", Operator.EQ, "1"))));
        flag.addRule(rule(0, false, List.of(new Condition("a", Operator.EQ, "2"))));

        assertThatThrownBy(() -> repository.saveAndFlush(flag))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private static FeatureFlag newFlag(String key, String name) {
        FeatureFlag flag = new FeatureFlag();
        flag.setKey(key);
        flag.setName(name);
        flag.setDescription("test");
        flag.setEnabled(true);
        flag.setDefaultState(false);
        return flag;
    }

    private static Rule rule(int priority, boolean serve, List<Condition> conditions) {
        Rule rule = new Rule();
        rule.setPriority(priority);
        rule.setServe(serve);
        rule.setConditions(conditions);
        return rule;
    }
}
