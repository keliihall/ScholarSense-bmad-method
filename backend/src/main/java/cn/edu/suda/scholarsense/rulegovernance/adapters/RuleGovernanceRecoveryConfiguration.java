package cn.edu.suda.scholarsense.rulegovernance.adapters;

import cn.edu.suda.scholarsense.rulegovernance.adapters.outbound.JdbcRuleVersionBusinessOwnerBindingQueryAdapter;
import cn.edu.suda.scholarsense.rulegovernance.api.RuleVersionBusinessOwnerBindingQueryPort;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

/** Dedicated least-privilege reader for HRAP checker resolution. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
        name = "scholarsense.rule-governance.recovery-binding-enabled",
        havingValue = "true")
public class RuleGovernanceRecoveryConfiguration {
    @Bean
    @ConfigurationProperties("scholarsense.rule-governance.recovery-binding-datasource")
    DataSourceProperties ruleGovernanceRecoveryDataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean("ruleGovernanceRecoveryDataSource")
    DataSource ruleGovernanceRecoveryDataSource(
            @Qualifier("ruleGovernanceRecoveryDataSourceProperties")
                    DataSourceProperties properties) {
        return properties.initializeDataSourceBuilder().build();
    }

    @Bean("ruleGovernanceRecoveryJdbc")
    JdbcTemplate ruleGovernanceRecoveryJdbc(
            @Qualifier("ruleGovernanceRecoveryDataSource") DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    @Bean
    RuleVersionBusinessOwnerBindingQueryPort ruleVersionBusinessOwnerBindingQueryPort(
            @Qualifier("ruleGovernanceRecoveryJdbc") JdbcTemplate jdbc,
            ObjectMapper json) {
        return new JdbcRuleVersionBusinessOwnerBindingQueryAdapter(jdbc, json);
    }
}
