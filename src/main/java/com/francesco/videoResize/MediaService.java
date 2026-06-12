package com.francesco.videoResize;

import jakarta.annotation.PostConstruct;
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

        if (!running.compareAndSet(false, true)) {
            System.out.println("job already running → skip");
            return;
        }

        try {
            List<Map<String, Object>> video = getMedia();

            if (video.isEmpty()) {
                System.out.println("no video");
                return;
            }

            for (Map<String, Object> vids : video) {

                String publicId = vids.get("public_id").toString();
                UUID id = UUID.fromString(vids.get("id").toString());

                Path inputPath = null;
                Path outputPath = null;

                try (InputStream in = storageService.getVideo("post-raw", publicId)) {

                    inputPath = Files.createTempFile("input-", ".mp4");
                    outputPath = Files.createTempFile("output-", ".mp4");

                    Files.copy(in, inputPath, StandardCopyOption.REPLACE_EXISTING);

                    ProcessBuilder pb = new ProcessBuilder(
                            "ffmpeg",
                            "-y",
                            "-i", inputPath.toString(),
                            "-vf", "scale=720:-2",
                            "-c:v", "libx264",
                            "-pix_fmt", "yuv420p",
                            "-crf", "28",
                            "-c:a", "aac",
                            "-b:a", "128k",
                            "-movflags", "+faststart",
                            outputPath.toString()
                    );

                    Process process = pb.start();
                    int exit = process.waitFor();

                    String errorOutput;
                    try (BufferedReader reader = new BufferedReader(
                            new InputStreamReader(process.getErrorStream()))) {
                        errorOutput = reader.lines().reduce("", (a, b) -> a + "\n" + b);
                    }

                    if (exit != 0) {
                        jdbcTemplate.update(
                                "UPDATE media SET status = 'ERROR' WHERE id = ?",
                                id
                        );
                        System.out.println(errorOutput);
                        throw new RuntimeException("FFmpeg error");
                    }

                    String key = storageService.uploadRawVideo(outputPath.toFile(), outputPath.getFileName().toString());
                    String url = storageService.getRawUrl(key);

                    storageService.delete("post-raw", publicId);

                    jdbcTemplate.update(
                            "UPDATE media SET link = ?, public_id = ?, status = 'DONE' WHERE id = ?",
                            url, key, id
                    );

                } catch (IOException e) {
                    throw new RuntimeException(e);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                } finally {
                    try { if (inputPath != null) Files.deleteIfExists(inputPath); } catch (Exception ignored) {}
                    try { if (outputPath != null) Files.deleteIfExists(outputPath); } catch (Exception ignored) {}
                }
            }

        } finally {
            running.set(false);
        }
    }
}
