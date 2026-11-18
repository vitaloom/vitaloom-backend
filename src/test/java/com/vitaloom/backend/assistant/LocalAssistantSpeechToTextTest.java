package com.vitaloom.backend.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class LocalAssistantSpeechToTextTest {
    @Test
    void transcribesVietnameseAndRemovesTemporaryFiles() throws IOException {
        var commands = new ArrayList<List<String>>();
        var temporaryDirectories = new ArrayList<Path>();
        var transcription = new LocalAssistantSpeechToText("ffmpeg with spaces", "whisper cli", Path.of("model path"),
                (command, timeout) -> {
                    commands.add(command);
                    if (command.getFirst().startsWith("ffmpeg")) {
                        Path output = Path.of(command.getLast());
                        temporaryDirectories.add(output.getParent());
                        Files.write(output, new byte[44 + 100]);
                    } else {
                        Path prefix = Path.of(command.get(command.indexOf("-of") + 1));
                        temporaryDirectories.add(Path.of(command.get(command.indexOf("-f") + 1)).getParent());
                        Files.writeString(Path.of(prefix + ".txt"), "  Đã ghi nhận nước.\n");
                    }
                    return 0;
                });

        assertThat(transcription.transcribe(new byte[]{1, 2, 3})).isEqualTo("Đã ghi nhận nước.");
        assertThat(commands).hasSize(2);
        assertThat(commands.getFirst()).contains("-protocol_whitelist", "file", "-format_whitelist",
                "mov,matroska,wav", "-bitexact", "-map_metadata", "-1", "-map_chapters", "-1");
        assertThat(commands.getFirst()).contains("-t", "20.001");
        assertThat(commands.get(1)).contains("whisper cli", "model path", "-l", "vi", "-t", "2");
        assertThat(temporaryDirectories).allSatisfy(path -> assertThat(Files.exists(path)).isFalse());
    }

    @Test
    void returnsEmptyStringForNoRecognizedSpeech() throws IOException {
        var transcription = new LocalAssistantSpeechToText("ffmpeg", "whisper", Path.of("model"), (command, timeout) -> {
            Path output = Path.of(command.getLast());
            if (command.getFirst().equals("ffmpeg")) Files.write(output, new byte[100]);
            else Files.writeString(Path.of(command.get(command.indexOf("-of") + 1) + ".txt"), "  \n");
            return 0;
        });

        assertThat(transcription.transcribe(new byte[]{1})).isEmpty();
    }

    @Test
    void rejectsOversizedInputBeforeStartingProcesses() {
        var commands = new ArrayList<List<String>>();
        var transcription = new LocalAssistantSpeechToText("ffmpeg", "whisper", Path.of("model"), (command, timeout) -> {
            commands.add(command);
            return 0;
        });

        assertThatThrownBy(() -> transcription.transcribe(new byte[10 * 1024 * 1024 + 1]))
                .isInstanceOf(LocalAssistantSpeechToText.SpeechTranscriptionException.class);
        assertThat(commands).isEmpty();
    }

    @Test
    void rejectsAudioLongerThanTwentySeconds() {
        var transcription = new LocalAssistantSpeechToText("ffmpeg", "whisper", Path.of("model"), (command, timeout) -> {
            if (command.getFirst().equals("ffmpeg")) Files.write(Path.of(command.getLast()), new byte[640_045]);
            return 0;
        });

        assertThatThrownBy(() -> transcription.transcribe(new byte[]{1}))
                .isInstanceOf(LocalAssistantSpeechToText.SpeechTranscriptionException.class)
                .hasMessageContaining("20 second");
    }
}
