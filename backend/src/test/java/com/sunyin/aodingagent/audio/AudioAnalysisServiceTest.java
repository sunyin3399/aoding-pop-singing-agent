package com.sunyin.aodingagent.audio;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import ws.schild.jave.Encoder;
import ws.schild.jave.MultimediaObject;
import ws.schild.jave.encode.AudioAttributes;
import ws.schild.jave.encode.EncodingAttributes;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AudioAnalysisServiceTest {

    @TempDir
    Path tempDirectory;

    @Test
    void detectsStableA4Tone() {
        AudioAnalysisService service = new AudioAnalysisService();
        float sampleRate = 16_000;
        double[] samples = new double[(int) sampleRate * 2];
        for (int i = 0; i < samples.length; i++) {
            samples[i] = 0.5 * Math.sin(2 * Math.PI * 440 * i / sampleRate);
        }

        AudioAnalysisResult result = service.analyzeSamples("a4.wav", samples, sampleRate, 1);

        assertEquals(2.0, result.durationSeconds());
        assertNotNull(result.medianPitchHz());
        assertTrue(Math.abs(result.medianPitchHz() - 440) < 10, "应识别出接近 A4 的音高");
        assertTrue(result.voicedRatio() > 0.8);
        assertNotNull(result.pitchStabilityCents());
        assertTrue(result.pitchStabilityCents() < 30);
        assertTrue(result.visualization().waveform().size() <= 600);
        assertTrue(result.visualization().waveform().size() > 100);
        assertTrue(result.visualization().rmsEnvelope().size() > 10);
        assertTrue(result.visualization().pitchTrack().stream()
                .filter(point -> point.value() != null)
                .allMatch(point -> Math.abs(point.value() - 440) < 10));
        assertEquals(64, result.visualization().melSpectrogram().frequenciesHz().size());
        assertTrue(result.visualization().melSpectrogram().levels().size() > 10);
        assertTrue(result.visualization().melSpectrogram().levels().size() <= 720);
        assertTrue(result.visualization().melSpectrogram().levels().stream()
                .allMatch(frame -> frame.size() == 64
                        && frame.stream().allMatch(level -> level >= 0 && level <= 255)));
        assertNotNull(result.visualization().spectralFeatures().centroidHz());
        assertTrue(Math.abs(result.visualization().spectralFeatures().centroidHz() - 440) < 35,
                "纯音谱质心应接近 440Hz");
        assertTrue(result.visualization().spectralFeatures().flatness() < 0.1,
                "纯音的谱平坦度应较低");
    }

    @Test
    void decodesAndAnalyzesMp3AndM4aUploads() throws Exception {
        Path wav = createA4Wav();
        Path mp3 = transcode(wav, "sample.mp3", "mp3", "libmp3lame");
        Path m4a = transcode(wav, "sample.m4a", "ipod", "aac");
        AudioAnalysisService service = new AudioAnalysisService();

        AudioAnalysisResult mp3Result = service.analyze(new MockMultipartFile(
                "file", "sample.mp3", "audio/mpeg", Files.readAllBytes(mp3)));
        AudioAnalysisResult m4aResult = service.analyze(new MockMultipartFile(
                "file", "sample.m4a", "audio/mp4", Files.readAllBytes(m4a)));

        assertPitchNearA4(mp3Result);
        assertPitchNearA4(m4aResult);
    }

    private Path createA4Wav() throws Exception {
        float sampleRate = 16_000;
        byte[] pcm = new byte[(int) sampleRate * 2 * 2];
        for (int i = 0; i < pcm.length / 2; i++) {
            short sample = (short) (16_000 * Math.sin(2 * Math.PI * 440 * i / sampleRate));
            pcm[i * 2] = (byte) sample;
            pcm[i * 2 + 1] = (byte) (sample >> 8);
        }
        Path wav = tempDirectory.resolve("source.wav");
        AudioFormat format = new AudioFormat(sampleRate, 16, 1, true, false);
        try (AudioInputStream stream = new AudioInputStream(
                new ByteArrayInputStream(pcm), format, pcm.length / format.getFrameSize())) {
            AudioSystem.write(stream, AudioFileFormat.Type.WAVE, wav.toFile());
        }
        return wav;
    }

    private Path transcode(Path source, String targetName, String format, String codec) throws Exception {
        Path target = tempDirectory.resolve(targetName);
        AudioAttributes audio = new AudioAttributes();
        audio.setCodec(codec);
        audio.setChannels(1);
        audio.setSamplingRate(16_000);
        audio.setBitRate(128_000);
        EncodingAttributes attributes = new EncodingAttributes();
        attributes.setOutputFormat(format);
        attributes.setAudioAttributes(audio);
        new Encoder().encode(new MultimediaObject(source.toFile()), target.toFile(), attributes);
        return target;
    }

    private void assertPitchNearA4(AudioAnalysisResult result) {
        assertNotNull(result.medianPitchHz());
        assertTrue(Math.abs(result.medianPitchHz() - 440) < 15,
                () -> result.fileName() + " 应识别出接近 A4 的音高，实际为 " + result.medianPitchHz());
    }
}
