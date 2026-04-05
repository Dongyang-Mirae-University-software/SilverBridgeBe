package kr.gosky.sso;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

@EnableJpaAuditing
@SpringBootApplication
public class SsoBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(SsoBackendApplication.class, args);
    }

}
