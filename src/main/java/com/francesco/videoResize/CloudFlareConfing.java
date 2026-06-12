package com.francesco.videoResize;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

import java.net.URI;

@Configuration
public class CloudFlareConfing {
    @Value("${cloudflare.key.id}")
    private String keyId;

    @Value("${cloudflare.app.key}")
    private String appKey;

    @Value("${cloudflare.endpoint}")
    private String endpoint;

    @Value("${cloudflare.region}")
    private String region;

    @Bean
    public S3Client s3Client(){
        return S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(keyId,appKey)
                ))
                .region(Region.of(region))
                .build();
    }
}