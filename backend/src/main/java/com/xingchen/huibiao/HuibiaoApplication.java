package com.xingchen.huibiao;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
public class HuibiaoApplication {

    public static void main(String[] args) {
        SpringApplication.run(HuibiaoApplication.class, args);
    }
}
