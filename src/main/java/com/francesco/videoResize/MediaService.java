package com.francesco.videoResize;


import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class MediaService {
    private  final JdbcTemplate jdbcTemplate;
    private final StorageService storageService;
    private  final AtomicBoolean running = new AtomicBoolean(false);

    public MediaService(JdbcTemplate jdbcTemplate, StorageService storageService) {
        this.jdbcTemplate = jdbcTemplate;
        this.storageService = storageService;
    }

    public List<Map<String,Object>> getMedia(){
        String sql = "SELECT * FROM media WHERE format = 'video' AND status = 'PENDING'";
        return jdbcTemplate.queryForList(sql);
    }
    @Scheduled(fixedDelay = 10000)
    public void getVideo() {
        if (!this.running.compareAndSet(false, true)) {
            System.out.println("[VIDEO] job already running");
            return;
        }
        try {
            List<Map<String, Object>> video = getMedia();
            System.out.println("[VIDEO] fetched: " + video.size());
            if (video.isEmpty()) {
                System.out.println("[VIDEO] no videos");
                return;
            }
            for (Map<String, Object> vids : video) {
                String publicId = null;
                UUID id = null;
                Path inputPath = null;
                Path outputPath = null;
                try {
                    publicId = vids.get("public_id").toString();
                    id = UUID.fromString(vids.get("id").toString());
                    System.out.println("[VIDEO] start id=" + id + " publicId=" + publicId);
                    try (InputStream in = storageService.getVideo("post-raw", publicId)) {
                        inputPath = Files.createTempFile("in-", ".mp4");
                        outputPath = Files.createTempFile("out-", ".mp4");
                        Files.copy(in, inputPath, StandardCopyOption.REPLACE_EXISTING);
                        System.out.println("[VIDEO] downloaded: " + inputPath);
                        ProcessBuilder pb = new ProcessBuilder(
                                "ffmpeg",
                                "-y",
                                "-i", inputPath.toString(),
                                "-map_metadata", "-1",
                                "-vf", "scale=-2:720,format=yuv420p",
                                "-c:v", "libx265",
                                "-pix_fmt", "yuv420p",
                                "-tag:v", "hvc1",
                                "-crf", "30",
                                "-preset", "fast",
                                "-c:a", "aac",
                                "-b:a", "96k",
                                "-movflags", "+faststart",
                                outputPath.toString()
                        );

                        System.out.println("[VIDEO] ffmpeg start id=" + id);
                        Process process = pb.start();
                        System.out.println("[VIDEO] after start");
                        boolean finished = process.waitFor(5, TimeUnit.MINUTES);
                        if (!finished) {
                            process.destroyForcibly();
                            System.out.println("[VIDEO] TIMEOUT id=" + id);
                            throw new RuntimeException("FFmpeg timeout");
                        }
                        System.out.println("[VIDEO] after waitFor");
                        int exit = process.exitValue();
                        if (exit != 0) {
                            String err = new String(process.getErrorStream().readAllBytes());
                            System.out.println("[VIDEO] FFmpeg ERROR id=" + id);
                            System.out.println(err);
                            throw new RuntimeException("FFmpeg failed");
                        }
                        System.out.println("[VIDEO] ffmpeg done id=" + id);
                        String key = storageService.uploadRawVideo(
                                outputPath.toFile(),
                                outputPath.getFileName().toString()
                        );
                        String url = storageService.getRawUrl(key);
                        storageService.delete("post-raw", publicId);
                        jdbcTemplate.update(
                                "UPDATE media SET link = ?, public_id = ?, status = 'DONE' WHERE id = ?",
                                url, key, id
                        );
                        System.out.println("[VIDEO] DONE id=" + id);
                    }
                } catch (Exception e) {
                    System.out.println("[VIDEO] ERROR id=" + id + " publicId=" + publicId);
                    System.out.println("[VIDEO] " + e.getMessage());
                    if (id != null) {
                        jdbcTemplate.update(
                                "UPDATE media SET status = 'ERROR' WHERE id = ?",
                                id
                        );
                    }
                } finally {
                    try {
                        if (inputPath != null) Files.deleteIfExists(inputPath);
                        if (outputPath != null) Files.deleteIfExists(outputPath);
                    } catch (IOException ignored) {}
                    System.out.println("[VIDEO] cleanup done id=" + id);
                }
            }
        } finally {
            running.set(false);
            System.out.println("[VIDEO] job finished");
        }
    }
}