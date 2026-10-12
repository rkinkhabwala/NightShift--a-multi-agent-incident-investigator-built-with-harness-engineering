package dev.nightshift.chaoscontroller;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "dev.nightshift")
public class ChaosControllerApplication {

    public static void main(String[] args) {
        SpringApplication.run(ChaosControllerApplication.class, args);
    }
}
