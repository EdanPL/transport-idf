package com.transit.engine;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class TransitFlowEngineApplication {

	public static void main(String[] args) {
		SpringApplication.run(TransitFlowEngineApplication.class, args);
	}

}
