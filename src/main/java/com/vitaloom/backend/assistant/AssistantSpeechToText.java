package com.vitaloom.backend.assistant;

@FunctionalInterface
interface AssistantSpeechToText {
    String transcribe(byte[] audio);
}
