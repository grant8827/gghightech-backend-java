package com.gghightech.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class GghightechBackendApplication {

	public static void main(String[] args) {
		SpringApplication.run(GghightechBackendApplication.class, args);
	}

}
