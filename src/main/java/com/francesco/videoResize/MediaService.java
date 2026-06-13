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

        try {

            List<Map<String, Object>> videos = getMedia();

            System.out.println("📦 [" + jobId + "] videos found: " + videos.size());

            if (videos.isEmpty()) {
                System.out.println("📭 [" + jobId + "] no video");
                return;
            }

            for (Map<String, Object> vid : videos) {

                UUID id = UUID.fromString(vid.get("id").toString());
                String publicId = vid.get("public_id").toString();

                System.out.println("\n------------------------------");
                System.out.println("🎬 [" + jobId + "] PROCESSING VIDEO");
                System.out.println("🆔 id: " + id);
                System.out.println("📁 publicId: " + publicId);
                System.out.println("------------------------------");

                Path inputPath = null;
                Path outputPath = null;

                try (InputStream in =
                             storageService.getVideo("post-raw", publicId)) {

                    System.out.println("⬇️ [" + jobId + "] downloading video...");

                    inputPath = Files.createTempFile("input-", ".mp4");
                    outputPath = Files.createTempFile("output-", ".mp4");

                    Files.copy(
                            in,
                            inputPath,
                            StandardCopyOption.REPLACE_EXISTING
                    );

                    System.out.println("📥 [" + jobId + "] input file: " + inputPath);
                    System.out.println("📤 [" + jobId + "] output file: " + outputPath);

                    System.out.println(
                            "📏 [" + jobId + "] input size: "
                                    + Files.size(inputPath) + " bytes"
                    );

                    long ffStart = System.currentTimeMillis();

                    ProcessBuilder pb = new ProcessBuilder(
                            "ffmpeg",
                            "-threads", "1",
                            "-y",
                            "-i", inputPath.toString(),
                            "-vf", "scale=720:-2,format=yuv420p",
                            "-c:v", "libx264",
                            "-pix_fmt", "yuv420p",
                            "-preset", "ultrafast",
                            "-crf", "28",
                            "-c:a", "aac",
                            "-b:a", "128k",
                            "-movflags", "+faststart",
                            outputPath.toString()
                    );

                    pb.redirectErrorStream(true);

                    System.out.println("⚙️ [" + jobId + "] ABOUT TO START FFMPEG");

                    Process process = pb.start();

                    System.out.println("⚙️ [" + jobId + "] FFMPEG STARTED");
                    System.out.println("⚙️ [" + jobId + "] PID = " + process.pid());

                    Thread ffmpegLogger = new Thread(() -> {

                        try (
                                BufferedReader br =
                                        new BufferedReader(
                                                new InputStreamReader(
                                                        process.getInputStream()
                                                )
                                        )
                        ) {

                            String line;

                            while ((line = br.readLine()) != null) {
                                System.out.println("[FFMPEG] " + line);
                            }

                            System.out.println("[FFMPEG] STREAM CLOSED");

                        } catch (Exception e) {
                            System.out.println("[FFMPEG] LOGGER ERROR");
                            e.printStackTrace();
                        }
                    });

                    ffmpegLogger.start();

                    System.out.println(
                            "⚙️ [" + jobId + "] WAITING FOR FFMPEG..."
                    );

                    boolean finished =
                            process.waitFor(60, TimeUnit.SECONDS);

                    System.out.println(
                            "⚙️ [" + jobId + "] WAIT RETURNED"
                    );

                    System.out.println(
                            "⚙️ [" + jobId + "] FINISHED = " + finished
                    );

                    if (!finished) {

                        System.out.println(
                                "💀 [" + jobId + "] FFMPEG TIMEOUT"
                        );

                        process.destroyForcibly();

                        jdbcTemplate.update(
                                "UPDATE media SET status='ERROR' WHERE id=?",
                                id
                        );

                        throw new RuntimeException(
                                "FFmpeg timeout"
                        );
                    }

                    int exit = process.exitValue();

                    ffmpegLogger.join(5000);

                    long ffEnd = System.currentTimeMillis();

                    System.out.println(
                            "⏱️ [" + jobId + "] FFMPEG TIME = "
                                    + (ffEnd - ffStart) + " ms"
                    );

                    System.out.println(
                            "📊 [" + jobId + "] EXIT CODE = "
                                    + exit
                    );

                    System.out.println(
                            "📂 [" + jobId + "] OUTPUT EXISTS = "
                                    + Files.exists(outputPath)
                    );

                    if (Files.exists(outputPath)) {

                        System.out.println(
                                "📏 [" + jobId + "] OUTPUT SIZE = "
                                        + Files.size(outputPath)
                                        + " bytes"
                        );
                    }

                    if (exit != 0) {

                        jdbcTemplate.update(
                                "UPDATE media SET status='ERROR' WHERE id=?",
                                id
                        );

                        throw new RuntimeException(
                                "FFmpeg exit code = " + exit
                        );
                    }

                    System.out.println(
                            "⬆️ [" + jobId + "] uploading result..."
                    );

                    String key =
                            storageService.uploadRawVideo(
                                    outputPath.toFile(),
                                    outputPath.getFileName().toString()
                            );

                    System.out.println(
                            "☁️ [" + jobId + "] upload complete"
                    );

                    String url = storageService.getRawUrl(key);

                    storageService.delete(
                            "post-raw",
                            publicId
                    );

                    jdbcTemplate.update(
                            "UPDATE media " +
                                    "SET link=?, public_id=?, status='DONE' " +
                                    "WHERE id=?",
                            url,
                            key,
                            id
                    );

                    System.out.println(
                            "✅ [" + jobId + "] DONE video id: " + id
                    );

                } catch (Exception e) {

                    System.out.println(
                            "💥 [" + jobId + "] ERROR VIDEO ID = " + id
                    );

                    e.printStackTrace();

                } finally {

                    try {
                        if (inputPath != null)
                            Files.deleteIfExists(inputPath);
                    } catch (Exception ignored) {}

                    try {
                        if (outputPath != null)
                            Files.deleteIfExists(outputPath);
                    } catch (Exception ignored) {}
                }
            }

        } finally {

            running.set(false);

            long endJob = System.currentTimeMillis();

            System.out.println("\n==============================");
            System.out.println("🏁 JOB FINISHED: " + jobId);
            System.out.println(
                    "⏱️ TOTAL TIME: "
                            + (endJob - startJob)
                            + " ms"
            );
            System.out.println("==============================\n");
        }


    }

}
