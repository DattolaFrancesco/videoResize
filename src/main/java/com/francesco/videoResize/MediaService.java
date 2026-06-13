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

        String jobId = UUID.randomUUID().toString().substring(0, 8);
        long startJob = System.currentTimeMillis();

        System.out.println("\n==============================");
        System.out.println("🚀 JOB STARTED: " + jobId);
        System.out.println("==============================");

        if (!running.compareAndSet(false, true)) {
            System.out.println("⛔ [" + jobId + "] job already running → skip");
            return;
        }

        Path inputPath = null;
        Path outputPath = null;
        UUID id = null;

        try {

            // =========================
            // 1. PRENDI SOLO 1 VIDEO
            // =========================
            Map<String, Object> vid = jdbcTemplate.queryForMap("""
            SELECT * FROM media
            WHERE status = 'PENDING'
            LIMIT 1
            FOR UPDATE SKIP LOCKED
        """);

            if (vid == null || vid.isEmpty()) {
                System.out.println("📭 [" + jobId + "] no video");
                return;
            }

            id = UUID.fromString(vid.get("id").toString());
            String publicId = vid.get("public_id").toString();

            // =========================
            // 2. MARK PROCESSING SUBITO
            // =========================
            jdbcTemplate.update(
                    "UPDATE media SET status='PROCESSING' WHERE id=?",
                    id
            );

            System.out.println("🎬 [" + jobId + "] PROCESSING VIDEO: " + id);

            // =========================
            // 3. DOWNLOAD
            // =========================
            try (InputStream in = storageService.getVideo("post-raw", publicId)) {

                inputPath = Files.createTempFile("input-", ".mp4");
                outputPath = Files.createTempFile("output-", ".mp4");

                Files.copy(in, inputPath, StandardCopyOption.REPLACE_EXISTING);

                System.out.println("📥 input size: " + Files.size(inputPath));

                // =========================
                // 4. FFmpeg SAFE CONFIG
                // =========================
                ProcessBuilder pb = new ProcessBuilder(
                        "ffmpeg",
                        "-y",
                        "-i", inputPath.toString(),

                        // ⚠️ riduce CPU/RAM spike
                        "-vf", "scale=640:-2:flags=lanczos",
                        "-c:v", "libx264",
                        "-preset", "veryfast",
                        "-crf", "30",
                        "-maxrate", "800k",
                        "-bufsize", "1600k",

                        "-tune", "fastdecode",
                        "-profile:v", "baseline",
                        "-level", "3.0",

                        "-c:a", "aac",
                        "-b:a", "96k",

                        "-movflags", "+faststart",
                        outputPath.toString()
                );

                pb.redirectErrorStream(true);

                System.out.println("⚙️ starting ffmpeg...");

                Process process = pb.start();

                // =========================
                // 5. STREAM LOG NON BLOCCANTE
                // =========================
                Thread logThread = new Thread(() -> {
                    try (BufferedReader br = new BufferedReader(
                            new InputStreamReader(process.getInputStream()))) {

                        String line;
                        while ((line = br.readLine()) != null) {
                            System.out.println("[FFMPEG] " + line);
                        }

                    } catch (Exception e) {
                        System.out.println("[FFMPEG] log error");
                    }
                });

                logThread.start();

                // =========================
                // 6. TIMEOUT SAFE
                // =========================
                boolean finished = process.waitFor(90, TimeUnit.SECONDS);

                if (!finished) {
                    System.out.println("💀 [" + jobId + "] TIMEOUT → killing ffmpeg");

                    process.destroy();
                    process.waitFor(5, TimeUnit.SECONDS);
                    process.destroyForcibly();

                    jdbcTemplate.update(
                            "UPDATE media SET status='ERROR' WHERE id=?",
                            id
                    );

                    return;
                }

                int exit = process.exitValue();
                logThread.join(3000);

                if (exit != 0) {
                    System.out.println("❌ ffmpeg failed");

                    jdbcTemplate.update(
                            "UPDATE media SET status='ERROR' WHERE id=?",
                            id
                    );

                    return;
                }

                // =========================
                // 7. UPLOAD
                // =========================
                String key = storageService.uploadRawVideo(
                        outputPath.toFile(),
                        outputPath.getFileName().toString()
                );

                String url = storageService.getRawUrl(key);

                storageService.delete("post-raw", publicId);

                jdbcTemplate.update("""
                UPDATE media
                SET link=?, public_id=?, status='DONE'
                WHERE id=?
            """, url, key, id);

                System.out.println("✅ DONE: " + id);

            }

        } catch (Exception e) {

            System.out.println("💥 ERROR jobId=" + jobId);
            e.printStackTrace();

            if (id != null) {
                jdbcTemplate.update(
                        "UPDATE media SET status='ERROR' WHERE id=?",
                        id
                );
            }

        } finally {

            running.set(false);

            try { if (inputPath != null) Files.deleteIfExists(inputPath); } catch (Exception ignored) {}
            try { if (outputPath != null) Files.deleteIfExists(outputPath); } catch (Exception ignored) {}

            long endJob = System.currentTimeMillis();

            System.out.println("\n==============================");
            System.out.println("🏁 JOB FINISHED: " + jobId);
            System.out.println("⏱ TOTAL: " + (endJob - startJob) + " ms");
            System.out.println("==============================\n");
        }
    }

}
