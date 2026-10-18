package fi.nhl.analytics;

import fi.nhl.analytics.service.AppProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableJpaRepositories(considerNestedRepositories = true)
@EnableConfigurationProperties(AppProperties.class)
public class NhlAnalyticsApplication {
    public static void main(String[] args) { SpringApplication.run(NhlAnalyticsApplication.class, args); }
}
