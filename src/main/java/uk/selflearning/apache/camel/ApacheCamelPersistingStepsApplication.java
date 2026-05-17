package uk.selflearning.apache.camel;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;

@SpringBootApplication
@ComponentScan(
        basePackages = "uk.selflearning.apache.camel",
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.REGEX,
                pattern = "uk\\.selflearning\\.apache\\.camel\\.inspection\\..*"
        )
)
public class ApacheCamelPersistingStepsApplication {

    public static void main(String[] args) {
        SpringApplication.run(ApacheCamelPersistingStepsApplication.class, args);
    }
}
