package demo;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AppConfig {
    @Bean(name = {"customerClient", "legacyClient"})
    public CustomerClient customerClient() {
        return new CustomerClient();
    }

    @Bean
    public ClockService clockService() {
        return new ClockService();
    }

    @Bean("singleClient")
    public SingleClient singleClient() {
        return new SingleClient();
    }

    @Bean(name = dynamicBeanName())
    public CustomerClient dynamicClient() {
        return new CustomerClient();
    }

    private static String dynamicBeanName() {
        return "dynamicClient";
    }
}
