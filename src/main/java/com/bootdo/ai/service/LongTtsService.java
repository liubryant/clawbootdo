package com.bootdo.ai.service;

import de.sciss.jump3r.Main;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.annotation.PreDestroy;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.*;

@Service
public class LongTtsService {
    private static final int MAX_TEXT_LENGTH = 10000;
    private static final long MAX_UPLOAD_BYTES = 15L * 1024 * 1024;
    private static final int CHUNK_UNITS = 120;
    private static final int ERROR_LIMIT = 8192;

    @Value("${ai.tts.endpoint:https://audio8-audio8-tts-preview-0-6b.hf.space/api/generate}")
    private String endpoint;

    @Value("${ai.tts.output-dir:/var/uploaded_files/ai-tts}")
    private String outputDir;

    @Value("${ai.tts.connect-timeout-ms:30000}")
    private int connectTimeoutMs;

    @Value("${ai.tts.read-timeout-ms:600000}")
    private int readTimeoutMs;

    private final ConcurrentMap<String, Job> jobs = new ConcurrentHashMap<>();
    private final ExecutorService executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(2), r -> {
                Thread thread = new Thread(r, "ai-long-tts-worker");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());

    public Job submit(MultipartFile voiceFile, String referenceText, String text) throws IOException {
        validate(voiceFile, referenceText, text);
        File root = new File(outputDir);
        if (!root.exists() && !root.mkdirs()) throw new IOException("无法创建语音输出目录");

        String id = UUID.randomUUID().toString().replace("-", "");
        File jobDir = new File(root, id);
        if (!jobDir.mkdirs()) throw new IOException("无法创建任务目录");
        String originalName = safeFileName(voiceFile.getOriginalFilename());
        File referenceFile = new File(jobDir, "reference" + extension(originalName));
        try (InputStream input = voiceFile.getInputStream()) {
            Files.copy(input, referenceFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }

        List<String> chunks = splitText(text, CHUNK_UNITS);
        Job job = new Job(id, chunks.size());
        jobs.put(id, job);
        try {
            executor.submit(() -> generate(job, jobDir, referenceFile, referenceText.trim(), chunks));
        } catch (RejectedExecutionException e) {
            jobs.remove(id);
            Files.deleteIfExists(referenceFile.toPath());
            Files.deleteIfExists(jobDir.toPath());
            throw new IllegalStateException("当前已有较多语音任务，请等待完成后再提交");
        }
        return job;
    }

    public Job get(String id) {
        return jobs.get(id);
    }

    public File output(String id) {
        Job job = jobs.get(id);
        if (job == null || !"SUCCESS".equals(job.status)) return null;
        File file = new File(outputDir, id + "/long-voice.mp3");
        return file.isFile() ? file : null;
    }

    private void generate(Job job, File jobDir, File referenceFile, String referenceText, List<String> chunks) {
        job.status = "RUNNING";
        job.message = "正在生成第 1 段";
        try {
            List<File> wavFiles = new ArrayList<>();
            for (int i = 0; i < chunks.size(); i++) {
                File wav = new File(jobDir, String.format(Locale.ROOT, "chunk-%03d.wav", i + 1));
                requestAudio(chunks.get(i), referenceText, referenceFile, wav);
                wavFiles.add(wav);
                job.completedChunks = i + 1;
                job.progress = Math.min(90, (int) Math.round((i + 1) * 90.0 / chunks.size()));
                job.message = i + 1 < chunks.size() ? "正在生成第 " + (i + 2) + " 段" : "正在合并 MP3";
            }
            File output = new File(jobDir, "long-voice.mp3");
            mergeAndEncodeMp3(jobDir, wavFiles, output);
            for (File wav : wavFiles) Files.deleteIfExists(wav.toPath());
            Files.deleteIfExists(referenceFile.toPath());
            job.progress = 100;
            job.status = "SUCCESS";
            job.message = "生成完成";
            job.fileSize = output.length();
        } catch (Exception e) {
            job.status = "FAILED";
            job.message = cleanError(e);
        }
    }

    private void requestAudio(String text, String referenceText, File referenceFile, File target) throws IOException {
        String boundary = "----BootdoTts" + UUID.randomUUID().toString().replace("-", "");
        HttpURLConnection conn = (HttpURLConnection) new URL(endpoint).openConnection();
        conn.setConnectTimeout(connectTimeoutMs);
        conn.setReadTimeout(readTimeoutMs);
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setChunkedStreamingMode(64 * 1024);
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        conn.setRequestProperty("Accept", "audio/wav");
        try (OutputStream raw = new BufferedOutputStream(conn.getOutputStream())) {
            writeField(raw, boundary, "text", text);
            writeField(raw, boundary, "reference_text", referenceText);
            writeField(raw, boundary, "temperature", "0.8");
            writeField(raw, boundary, "top_p", "0.95");
            writeField(raw, boundary, "top_k", "50");
            writeField(raw, boundary, "max_new_tokens", "1024");
            writeFile(raw, boundary, "reference_audio", referenceFile);
            raw.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        }
        int status = conn.getResponseCode();
        if (status != 200) {
            String detail = readLimited(conn.getErrorStream());
            conn.disconnect();
            throw new IOException("语音模型返回 " + status + (detail.isEmpty() ? "" : "：" + detail));
        }
        try (InputStream input = new BufferedInputStream(conn.getInputStream());
             OutputStream output = new BufferedOutputStream(new FileOutputStream(target))) {
            copy(input, output);
        } finally {
            conn.disconnect();
        }
    }

    private void mergeAndEncodeMp3(File jobDir, List<File> wavFiles, File output) throws Exception {
        File mergedWav = new File(jobDir, "merged.wav");
        List<AudioInputStream> streams = new ArrayList<>();
        try {
            Vector<InputStream> inputs = new Vector<>();
            AudioFormat format = null;
            long frames = 0;
            for (File wav : wavFiles) {
                AudioInputStream stream = AudioSystem.getAudioInputStream(wav);
                if (format == null) format = stream.getFormat();
                else if (!format.matches(stream.getFormat())) throw new IOException("生成片段音频格式不一致");
                streams.add(stream);
                inputs.add(stream);
                frames += stream.getFrameLength();
            }
            try (SequenceInputStream sequence = new SequenceInputStream(inputs.elements());
                 AudioInputStream merged = new AudioInputStream(sequence, format, frames)) {
                AudioSystem.write(merged, javax.sound.sampled.AudioFileFormat.Type.WAVE, mergedWav);
            }
        } finally {
            for (AudioInputStream stream : streams) try { stream.close(); } catch (IOException ignored) { }
        }
        int code = new Main().run(new String[]{"-b", "128", mergedWav.getAbsolutePath(), output.getAbsolutePath()});
        Files.deleteIfExists(mergedWav.toPath());
        if (code != 0 || !output.isFile() || output.length() == 0) throw new IOException("MP3 编码失败，状态码 " + code);
    }

    public static List<String> splitText(String text, int maxUnits) {
        String normalized = text == null ? "" : text.replace("\r\n", "\n").trim();
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int offset = 0; offset < normalized.length();) {
            int cp = normalized.codePointAt(offset);
            offset += Character.charCount(cp);
            current.appendCodePoint(cp);
            boolean boundary = "。！？!?；;\n".indexOf(cp) >= 0;
            if ((boundary && speechUnits(current.toString()) >= 40) || speechUnits(current.toString()) >= maxUnits) {
                result.add(current.toString().trim());
                current.setLength(0);
            }
        }
        if (current.toString().trim().length() > 0) result.add(current.toString().trim());
        return result;
    }

    private static int speechUnits(String text) {
        int units = 0;
        boolean inLatinWord = false;
        for (int i = 0; i < text.length();) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.isWhitespace(cp) || isPunctuation(cp)) { inLatinWord = false; continue; }
            boolean latin = cp < 128 && Character.isLetterOrDigit(cp);
            if (latin) { if (!inLatinWord) units++; inLatinWord = true; }
            else { units++; inLatinWord = false; }
        }
        return units;
    }

    private static boolean isPunctuation(int cp) {
        int type = Character.getType(cp);
        return type == Character.CONNECTOR_PUNCTUATION || type == Character.DASH_PUNCTUATION ||
                type == Character.START_PUNCTUATION || type == Character.END_PUNCTUATION ||
                type == Character.INITIAL_QUOTE_PUNCTUATION || type == Character.FINAL_QUOTE_PUNCTUATION ||
                type == Character.OTHER_PUNCTUATION;
    }

    private void validate(MultipartFile file, String referenceText, String text) {
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("请上传 MP3 或 WAV 参考音频");
        if (file.getSize() > MAX_UPLOAD_BYTES) throw new IllegalArgumentException("参考音频不能超过 15MB");
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase(Locale.ROOT);
        if (!name.endsWith(".mp3") && !name.endsWith(".wav")) throw new IllegalArgumentException("参考音频仅支持 MP3、WAV");
        if (referenceText == null || referenceText.trim().isEmpty()) throw new IllegalArgumentException("请输入参考音频对应文字");
        if (text == null || text.trim().isEmpty()) throw new IllegalArgumentException("请输入需要生成的文字");
        if (text.length() > MAX_TEXT_LENGTH) throw new IllegalArgumentException("生成文字不能超过 " + MAX_TEXT_LENGTH + " 字");
    }

    private static void writeField(OutputStream out, String boundary, String name, String value) throws IOException {
        out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n" + value + "\r\n").getBytes(StandardCharsets.UTF_8));
    }

    private static void writeFile(OutputStream out, String boundary, String name, File file) throws IOException {
        out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"; filename=\"reference" + extension(file.getName()) + "\"\r\nContent-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        try (InputStream input = new BufferedInputStream(new FileInputStream(file))) { copy(input, out); }
    }

    private static void copy(InputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        int count;
        while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
    }

    private static String readLimited(InputStream input) throws IOException {
        if (input == null) return "";
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int count;
        while (output.size() < ERROR_LIMIT && (count = input.read(buffer, 0, Math.min(buffer.length, ERROR_LIMIT - output.size()))) != -1) output.write(buffer, 0, count);
        return new String(output.toByteArray(), StandardCharsets.UTF_8).trim();
    }

    private static String cleanError(Exception e) {
        String message = e.getMessage();
        return message == null || message.trim().isEmpty() ? "生成失败，请稍后重试" : message;
    }

    private static String safeFileName(String name) { return name == null ? "voice.wav" : new File(name).getName(); }
    private static String extension(String name) { int dot = name.lastIndexOf('.'); return dot < 0 ? ".wav" : name.substring(dot).toLowerCase(Locale.ROOT); }

    @PreDestroy
    public void shutdown() { executor.shutdownNow(); }

    public static class Job {
        private final String id;
        private final int totalChunks;
        private volatile int completedChunks;
        private volatile int progress;
        private volatile String status = "QUEUED";
        private volatile String message = "等待生成";
        private volatile long fileSize;
        Job(String id, int totalChunks) { this.id = id; this.totalChunks = totalChunks; }
        public String getId() { return id; }
        public int getTotalChunks() { return totalChunks; }
        public int getCompletedChunks() { return completedChunks; }
        public int getProgress() { return progress; }
        public String getStatus() { return status; }
        public String getMessage() { return message; }
        public long getFileSize() { return fileSize; }
        public String getDownloadUrl() { return "SUCCESS".equals(status) ? "/ai/tts/download/" + id : null; }
    }
}
