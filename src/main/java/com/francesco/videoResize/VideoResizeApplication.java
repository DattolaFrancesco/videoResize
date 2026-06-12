package com.francesco.videoResize;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class VideoResizeApplication {

	public static void main(String[] args) {
		SpringApplication.run(VideoResizeApplication.class, args);
	}

}
