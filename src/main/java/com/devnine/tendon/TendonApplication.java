package com.devnine.tendon;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;

@SpringBootApplication(exclude= {UserDetailsServiceAutoConfiguration.class})
public class TendonApplication {

	public static void main(String[] args) {
		SpringApplication.run(TendonApplication.class, args);
	}

}
