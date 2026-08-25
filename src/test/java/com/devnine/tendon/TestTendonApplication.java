package com.devnine.tendon;

import org.springframework.boot.SpringApplication;

public class TestTendonApplication {

	public static void main(String[] args) {
		SpringApplication.from(TendonApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
