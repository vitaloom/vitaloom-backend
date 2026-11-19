package com.vitaloom.backend.assistant;

@FunctionalInterface
interface AssistantSpeechToText {
    String transcribe(byte[] audio);

    final class SpeechTranscriptionException extends RuntimeException {
        SpeechTranscriptionException() {
            super("Speech transcription is unavailable.");
        }
    }
}
