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

        String jobId = UUID.randomUUID().toString().substring(0, 8);
        long startJob = System.currentTimeMillis();

        System.out.println("\n==============================");
        System.out.println("🚀 JOB STARTED: " + jobId);
        System.out.println("==============================");

        if (!running.compareAndSet(false, true)) {
            System.out.println("⛔ [" + jobId + "] job already running → skip");
            return;
        }

        try {
            List<Map<String, Object>> video = getMedia();

            System.out.println("📦 [" + jobId + "] videos found: " + video.size());

            if (video.isEmpty()) {
                System.out.println("📭 [" + jobId + "] no video");
                return;
            }

            for (Map<String, Object> vids : video) {

                String publicId = vids.get("public_id").toString();
                UUID id = UUID.fromString(vids.get("id").toString());

                System.out.println("\n------------------------------");
                System.out.println("🎬 [" + jobId + "] PROCESSING VIDEO");
                System.out.println("🆔 id: " + id);
                System.out.println("📁 publicId: " + publicId);
                System.out.println("------------------------------");

                Path inputPath = null;
                Path outputPath = null;

                try (InputStream in = storageService.getVideo("post-raw", publicId)) {

                    System.out.println("⬇️ [" + jobId + "] downloading video...");

                    inputPath = Files.createTempFile("input-", ".mp4");
                    outputPath = Files.createTempFile("output-", ".mp4");

                    Files.copy(in, inputPath, StandardCopyOption.REPLACE_EXISTING);

                    System.out.println("📥 [" + jobId + "] input file: " + inputPath);
                    System.out.println("📤 [" + jobId + "] output file: " + outputPath);

                    long ffStart = System.currentTimeMillis();

                    ProcessBuilder pb = new ProcessBuilder(
                            "ffmpeg",
                            "-y",
                            "-i", inputPath.toString(),
                            "-vf", "scale=720:-2,format=yuv420p",
                            "-c:v", "libx264",
                            "-pix_fmt", "yuv420p",
                            "-crf", "28",
                            "-c:a", "aac",
                            "-b:a", "128k",
                            "-movflags", "+faststart",
                            outputPath.toString()
                    );

                    System.out.println("⚙️ [" + jobId + "] starting ffmpeg...");

                    Process process = pb.start();

                    String stdout = new String(process.getInputStream().readAllBytes());
                    String stderr = new String(process.getErrorStream().readAllBytes());

                    int exit = process.waitFor();

                    long ffEnd = System.currentTimeMillis();

                    System.out.println("⏱️ [" + jobId + "] ffmpeg time: " + (ffEnd - ffStart) + "ms");
                    System.out.println("📊 [" + jobId + "] exit code: " + exit);

                    System.out.println("------ FFMPEG STDOUT ------");
                    System.out.println(stdout);

                    System.out.println("------ FFMPEG STDERR ------");
                    System.out.println(stderr);

                    if (exit != 0) {
                        System.out.println("❌ [" + jobId + "] ffmpeg FAILED for id " + id);

                        jdbcTemplate.update(
                                "UPDATE media SET status = 'ERROR' WHERE id = ?",
                                id
                        );

                        throw new RuntimeException("FFmpeg error");
                    }

                    System.out.println("⬆️ [" + jobId + "] uploading result...");

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

                    System.out.println("✅ [" + jobId + "] DONE video id: " + id);

                } catch (Exception e) {
                    System.out.println("💥 [" + jobId + "] ERROR processing video id: " + id);
                    e.printStackTrace();
                    try {
                        throw e;
                    } catch (IOException ex) {
                        throw new RuntimeException(ex);
                    } catch (InterruptedException ex) {
                        throw new RuntimeException(ex);
                    }
                } finally {
                    try { if (inputPath != null) Files.deleteIfExists(inputPath); } catch (Exception ignored) {}
                    try { if (outputPath != null) Files.deleteIfExists(outputPath); } catch (Exception ignored) {}
                }
            }

        } finally {
            running.set(false);

            long endJob = System.currentTimeMillis();
            System.out.println("\n==============================");
            System.out.println("🏁 JOB FINISHED: " + jobId);
            System.out.println("⏱️ TOTAL TIME: " + (endJob - startJob) + "ms");
            System.out.println("==============================\n");
        }
    }
}
