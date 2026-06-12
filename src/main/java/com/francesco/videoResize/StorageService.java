package com.francesco.videoResize;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;

@Service
public class StorageService {
    private final S3Client s3Client;
    @Value("${cloudflare.raw.url}")
    private String rawUrl;

    public StorageService(S3Client s3Client) {
        this.s3Client = s3Client;
    }

    public void delete(String bucket, String key){
        this.s3Client.deleteObject(r->r
                .bucket(bucket)
                .key(key));
        System.out.println(key + "deleted");
    }
    public String uploadRawVideo(File file, String fileName){
        String key = "videos/raw/" + UUID.randomUUID() +"/" +fileName;
        PutObjectRequest request = PutObjectRequest.builder()
                .bucket("post-raw")
                .key(key)
                .contentType("video/mp4")
                .build();
        s3Client.putObject(request, RequestBody.fromFile(file));
        return key;
    }
    public InputStream getVideo(String bucket, String key){
        File temp = null;
        try {
            temp = File.createTempFile("video-", ".mp4");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
         return this.s3Client.getObject(
                GetObjectRequest.builder()
                        .bucket(bucket)
                        .key(key)
                        .build()

        );
    }
    public String getRawUrl(String key) {
        return rawUrl + "/" + key;
    }

}
