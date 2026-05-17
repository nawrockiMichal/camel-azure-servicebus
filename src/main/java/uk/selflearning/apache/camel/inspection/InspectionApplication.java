package uk.selflearning.apache.camel.inspection;

import org.apache.camel.spring.boot.CamelAutoConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(
        scanBasePackages = "uk.selflearning.apache.camel.inspection",
        exclude = CamelAutoConfiguration.class
)
public class InspectionApplication {

    public static void main(String[] args) {
        new org.springframework.boot.builder.SpringApplicationBuilder(InspectionApplication.class)
                .profiles("inspection")
                .run(args);
    }
}
