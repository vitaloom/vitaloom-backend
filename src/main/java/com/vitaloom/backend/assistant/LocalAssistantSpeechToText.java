package com.vitaloom.backend.assistant;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

final class LocalAssistantSpeechToText implements AssistantSpeechToText {
    private static final int MAX_AUDIO_BYTES = 10 * 1024 * 1024;
    private static final int MAX_TRANSCRIPT_BYTES = 8 * 1024;
    private static final int MAX_PCM_BYTES = 20 * 16_000 * 2;
    private static final Duration CONVERSION_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration TRANSCRIPTION_TIMEOUT = Duration.ofSeconds(45);
    private static final Semaphore JOBS = new Semaphore(2);

    private final String ffmpegExecutable;
    private final String whisperExecutable;
    private final Path modelPath;
    private final ProcessExecutor processes;

    LocalAssistantSpeechToText(String ffmpegExecutable, String whisperExecutable, String modelPath) {
        this(ffmpegExecutable, whisperExecutable, Path.of(modelPath), LocalAssistantSpeechToText::runProcess);
    }

    LocalAssistantSpeechToText(String ffmpegExecutable, String whisperExecutable, Path modelPath,
                               ProcessExecutor processes) {
        if (ffmpegExecutable == null || ffmpegExecutable.isBlank()
                || whisperExecutable == null || whisperExecutable.isBlank()
                || modelPath == null || processes == null) {
            throw new IllegalArgumentException("Speech transcription configuration is incomplete.");
        }
        this.ffmpegExecutable = ffmpegExecutable;
        this.whisperExecutable = whisperExecutable;
        this.modelPath = modelPath;
        this.processes = processes;
    }

    @Override
    public String transcribe(byte[] audio) {
        if (audio == null || audio.length == 0 || audio.length > MAX_AUDIO_BYTES) {
            throw new SpeechTranscriptionException("Audio is empty or exceeds the supported size.");
        }
        if (!JOBS.tryAcquire()) {
            throw new SpeechTranscriptionException("Speech transcription is busy.");
        }

        Path directory = null;
        try {
            directory = Files.createTempDirectory("vitaloom-assistant-stt-");
            Path input = directory.resolve("recording.bin");
            Path pcm = directory.resolve("recording.wav");
            Path transcriptPrefix = directory.resolve("transcript");
            Path transcript = directory.resolve("transcript.txt");
            Files.write(input, audio);

            run(List.of(ffmpegExecutable, "-nostdin", "-v", "error", "-protocol_whitelist", "file",
                    "-format_whitelist", "mov,matroska,wav", "-i", input.toString(),
                    "-map", "0:a:0", "-ac", "1", "-ar", "16000", "-c:a", "pcm_s16le",
                    "-f", "wav", "-t", "20.001", "-bitexact", "-map_metadata", "-1",
                    "-map_chapters", "-1", pcm.toString()), CONVERSION_TIMEOUT);
            long pcmBytes = Files.size(pcm);
            if (pcmBytes <= 44 || pcmBytes > MAX_PCM_BYTES + 44L) {
                throw new SpeechTranscriptionException("Audio is silent or exceeds the 20 second limit.");
            }

            run(List.of(whisperExecutable, "-m", modelPath.toString(), "-l", "vi", "-f", pcm.toString(),
                    "-otxt", "-of", transcriptPrefix.toString(), "-nt", "-np", "-t", "2"),
                    TRANSCRIPTION_TIMEOUT);
            long transcriptBytes = Files.size(transcript);
            if (transcriptBytes > MAX_TRANSCRIPT_BYTES) {
                throw new SpeechTranscriptionException("Transcription exceeds the supported length.");
            }
            return Files.readString(transcript, StandardCharsets.UTF_8).strip();
        } catch (SpeechTranscriptionException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new SpeechTranscriptionException("Local speech transcription failed.");
        } finally {
            if (directory != null) deleteTemporaryFiles(directory);
            JOBS.release();
        }
    }

    private void run(List<String> command, Duration timeout) {
        try {
            if (processes.execute(command, timeout) != 0) {
                throw new SpeechTranscriptionException("Local speech processing failed.");
            }
        } catch (IOException exception) {
            throw new SpeechTranscriptionException("Local speech processing failed.");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new SpeechTranscriptionException("Local speech processing was interrupted.");
        }
    }

    private static int runProcess(List<String> command, Duration timeout) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        boolean completed;
        try {
            completed = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            terminate(process);
            Thread.currentThread().interrupt();
            throw exception;
        }
        if (!completed) {
            terminate(process);
            throw new SpeechTranscriptionException("Local speech processing timed out.");
        }
        return process.exitValue();
    }

    private static void deleteTemporaryFiles(Path directory) {
        try (var files = Files.list(directory)) {
            files.forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    path.toFile().deleteOnExit();
                }
            });
        } catch (IOException ignored) {
            // Try removing the directory below, even if listing it failed.
        }
        try {
            Files.deleteIfExists(directory);
        } catch (IOException ignored) {
            directory.toFile().deleteOnExit();
        }
    }

    private static void terminate(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        try {
            process.waitFor(2, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    @FunctionalInterface
    interface ProcessExecutor {
        int execute(List<String> command, Duration timeout) throws IOException, InterruptedException;
    }

    static final class SpeechTranscriptionException extends RuntimeException {
        SpeechTranscriptionException(String message) {
            super(message);
        }
    }
}
