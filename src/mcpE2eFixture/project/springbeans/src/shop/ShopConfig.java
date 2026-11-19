package shop;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.ImportResource;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.context.annotation.Scope;

@Configuration
@ImportResource("classpath:legacy-context.xml")
public class ShopConfig {
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean(name = {"reportFormatter", "formatter"})
    @Primary
    @Scope("prototype")
    public ReportFormatter reportFormatter(Clock clock) {
        return new ReportFormatter(clock);
    }

    @Bean
    @Profile("dev")
    public Greeting devGreeting() {
        return new Greeting("hello from dev");
    }
}
