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

@Service
public class MediaService {
    private  final JdbcTemplate jdbcTemplate;
    private final StorageService storageService;

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
        List<Map<String, Object>> video = getMedia();

        if (video.isEmpty()) {
            System.out.println("no video");
            return;
        }

        for (Map<String, Object> vids : video) {
            String publicId = vids.get("public_id").toString();
            UUID id =UUID.fromString(vids.get("id").toString());
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

                    errorOutput = reader.lines()
                            .reduce("", (a, b) -> a + "\n" + b);
                }
                if (exit != 0) {
                    String sqlError = "UPDATE media SET status = 'ERROR' WHERE id = ?";
                    this.jdbcTemplate.update(sqlError,id);
                    System.out.println("FFmpeg error output:\n" + errorOutput);
                    throw new RuntimeException("FFmpeg error");
                }
                System.out.println(inputPath.getFileName());
                System.out.println(outputPath.getFileName());
                String key = this.storageService.uploadRawVideo(outputPath.toFile(),outputPath.getFileName().toString());
                String url = this.storageService.getRawUrl(key);
                this.storageService.delete("post-raw",publicId);
                String sql = "UPDATE media SET link = ? , public_id = ? WHERE id = ?";
                this.jdbcTemplate.update(sql,url,key,id);
                String sqlDone = "UPDATE media SET status = 'DONE' WHERE id = ?";
                this.jdbcTemplate.update(sqlDone,id);
                Runtime runtime = Runtime.getRuntime();

                long usedMemory = runtime.totalMemory() - runtime.freeMemory();
                long freeMemory = runtime.freeMemory();
                long totalMemory = runtime.totalMemory();
                long maxMemory = runtime.maxMemory();

                System.out.printf(
                        "Memory -> Used: %.2f MB | Free: %.2f MB | Total: %.2f MB | Max: %.2f MB%n",
                        usedMemory / 1024.0 / 1024.0,
                        freeMemory / 1024.0 / 1024.0,
                        totalMemory / 1024.0 / 1024.0,
                        maxMemory / 1024.0 / 1024.0
                );
            } catch (Exception e) {
                throw new RuntimeException(e);
            } finally {
                try {
                    if (inputPath != null) {
                        Files.deleteIfExists(inputPath);
                    }
                } catch (IOException ignored) {}

                try {
                    if (outputPath != null) {
                        Files.deleteIfExists(outputPath);
                    }
                } catch (IOException ignored) {}
            }
        }
    }
}
