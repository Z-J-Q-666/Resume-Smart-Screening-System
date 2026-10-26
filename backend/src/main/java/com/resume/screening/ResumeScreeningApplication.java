package com.resume.screening;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@MapperScan("com.resume.screening.mapper")
@EnableScheduling
public class ResumeScreeningApplication {

    public static void main(String[] args) {
        SpringApplication.run(ResumeScreeningApplication.class, args);
    }
}
