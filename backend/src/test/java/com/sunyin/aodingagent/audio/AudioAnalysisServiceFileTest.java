package com.sunyin.aodingagent.audio;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.data.Offset.offset;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class AudioAnalysisServiceFileTest {

    private static final float SAMPLE_RATE = 16_000;
    private final AudioAnalysisService service = new AudioAnalysisService();

    @Test
    void analyzesSpectrumFromUploadedWav() throws IOException {
        MockMultipartFile file = wavFile("low-tone.wav", 100, 1.0, 0.5);

        AudioSpectrumResult result = service.analyzeSpectrum(file);

        assertThat(result.fileName()).isEqualTo("low-tone.wav");
        assertThat(result.durationSeconds()).isEqualTo(1.0);
        assertThat(result.sampleRate()).isEqualTo(SAMPLE_RATE);
        assertThat(result.nyquistHz()).isEqualTo(8_000.0);
        assertThat(result.segments().getFirst().dominantBand())
                .isEqualTo(AudioSpectrumResult.DominantBand.LOW);
    }

    @Test
    void existingAnalysisStillUsesDecodedWavMetadata() throws IOException {
        MockMultipartFile file = wavFile("existing.wav", 440, 1.0, 0.5);

        AudioAnalysisResult result = service.analyze(file);

        assertThat(result.fileName()).isEqualTo("existing.wav");
        assertThat(result.durationSeconds()).isEqualTo(1.0);
        assertThat(result.sampleRate()).isEqualTo(SAMPLE_RATE);
        assertThat(result.channels()).isEqualTo(1);
    }

    @Test
    void analyzesSpectrogramFromUploadedHighRateWav() throws IOException {
        MockMultipartFile file = wavFile("high-rate.wav", 44_100, 440, 1.0, 0.5);

        AudioSpectrogramResult result = service.analyzeSpectrogram(file);

        assertThat(result.sampleRate()).isEqualTo(44_100);
        assertThat(result.nyquistHz()).isEqualTo(22_050);
        assertThat(result.durationSeconds()).isCloseTo(1.0, offset(0.01));
    }

    @Test
    void forwardsEndpointSpecificSampleRatesForCompressedUploads() throws IOException {
        MockMultipartFile file = new MockMultipartFile("file", "tone.mp3", "audio/mpeg", new byte[]{1});
        AudioAnalysisService deterministicService = new AudioAnalysisService(
                (uploadedFile, fileName, extension, compressedTargetSampleRate) ->
                        new AudioAnalysisService.DecodedAudio(
                                fileName,
                                new double[compressedTargetSampleRate / 10],
                                compressedTargetSampleRate,
                                1
                        )
        );

        AudioSpectrogramResult spectrogram = deterministicService.analyzeSpectrogram(file);
        AudioAnalysisResult analysis = deterministicService.analyze(file);
        AudioSpectrumResult spectrum = deterministicService.analyzeSpectrum(file);

        assertThat(spectrogram.sampleRate()).isEqualTo(48_000);
        assertThat(spectrogram.nyquistHz()).isEqualTo(24_000);
        assertThat(analysis.sampleRate()).isEqualTo(16_000);
        assertThat(spectrum.sampleRate()).isEqualTo(16_000);
    }

    @Test
    void decodesUploadedMp3WhenFfmpegIsAvailable() throws IOException {
        MockMultipartFile file = mp3File("tone.mp3");
        Optional<AudioAnalysisService> availableService = serviceUsingTestFfmpeg();
        assumeTrue(availableService.isPresent(), "ffmpeg is unavailable; skipping compressed-audio integration test");
        AudioAnalysisService compressedAudioService = availableService.orElseThrow();

        AudioSpectrogramResult spectrogram = compressedAudioService.analyzeSpectrogram(file);
        AudioAnalysisResult analysis = compressedAudioService.analyze(file);
        AudioSpectrumResult spectrum = compressedAudioService.analyzeSpectrum(file);

        assertThat(spectrogram.sampleRate()).isEqualTo(48_000);
        assertThat(spectrogram.nyquistHz()).isEqualTo(24_000);
        assertThat(analysis.sampleRate()).isEqualTo(16_000);
        assertThat(spectrum.sampleRate()).isEqualTo(16_000);
    }

    @Test
    void rejectsEmptyFileForSpectrumAnalysis() {
        MockMultipartFile file = new MockMultipartFile("file", "empty.wav", "audio/wav", new byte[0]);

        assertThatThrownBy(() -> service.analyzeSpectrum(file))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("请选择非空 WAV 音频文件");
    }

    @Test
    void rejectsUnsupportedExtensionForSpectrumAnalysis() {
        MockMultipartFile file = new MockMultipartFile("file", "audio.txt", "text/plain", new byte[]{1});

        assertThatThrownBy(() -> service.analyzeSpectrum(file))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("仅支持 WAV、MP3、M4A 音频");
    }

    @Test
    void rejectsOversizedFileForSpectrumAnalysis() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "large.wav", "audio/wav", new byte[(int) AudioAnalysisService.MAX_FILE_BYTES + 1]);

        assertThatThrownBy(() -> service.analyzeSpectrum(file))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("音频文件不能超过 10MB");
    }

    @Test
    void rejectsEmptyFileForSpectrogramAnalysis() {
        MockMultipartFile file = new MockMultipartFile("file", "empty.wav", "audio/wav", new byte[0]);

        assertThatThrownBy(() -> service.analyzeSpectrogram(file))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("请选择非空 WAV 音频文件");
    }

    @Test
    void rejectsUnsupportedExtensionForSpectrogramAnalysis() {
        MockMultipartFile file = new MockMultipartFile("file", "audio.txt", "text/plain", new byte[]{1});

        assertThatThrownBy(() -> service.analyzeSpectrogram(file))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("仅支持 WAV、MP3、M4A 音频");
    }

    @Test
    void rejectsOversizedFileForSpectrogramAnalysis() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "large.wav", "audio/wav", new byte[(int) AudioAnalysisService.MAX_FILE_BYTES + 1]);

        assertThatThrownBy(() -> service.analyzeSpectrogram(file))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("音频文件不能超过 10MB");
    }

    @Test
    void rejectsOverThreeMinuteWavForSpectrogramAnalysis() throws IOException {
        MockMultipartFile file = wavFile("long.wav", SAMPLE_RATE, 440, 181.0, 0.5);

        assertThatThrownBy(() -> service.analyzeSpectrogram(file))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("音频时长不能超过 3 分钟");
    }

    @Test
    void rejectsCraftedWavWithExtremeSourceSampleRateBeforeAnalysis() {
        MockMultipartFile file = wavHeaderFile("hostile.wav", 1_000_000);

        assertThatThrownBy(() -> service.analyzeSpectrogram(file))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("音频采样率必须在 8000 Hz 到 192000 Hz 之间");
    }

    @Test
    void keepsLegacyAnalysisBehaviorForOutOfRangeWavSampleRates() {
        MockMultipartFile analyzeFile = wavHeaderFile("legacy-analyze.wav", 1_000_000);
        MockMultipartFile spectrumFile = wavHeaderFile("legacy-spectrum.wav", 1_000_000);

        assertThatCode(() -> service.analyze(analyzeFile)).doesNotThrowAnyException();
        assertThatCode(() -> service.analyzeSpectrum(spectrumFile)).doesNotThrowAnyException();
    }

    private MockMultipartFile mp3File(String fileName) throws IOException {
        try (InputStream input = getClass().getResourceAsStream("/audio/quarter-second-tone.mp3")) {
            if (input == null) {
                throw new IllegalStateException("Missing test fixture: /audio/quarter-second-tone.mp3");
            }
            return new MockMultipartFile("file", fileName, "audio/mpeg", input.readAllBytes());
        }
    }

    private Optional<AudioAnalysisService> serviceUsingTestFfmpeg() {
        if (System.getProperty("os.name").toLowerCase().contains("win")) {
            return Optional.of(new AudioAnalysisService());
        }
        String executableName = "ffmpeg";
        return Arrays.stream(System.getenv().getOrDefault("PATH", "").split(File.pathSeparator))
                .map(directory -> Path.of(directory, executableName))
                .filter(Files::isExecutable)
                .findFirst()
                .map(executable -> new AudioAnalysisService(() -> executable.toString()));
    }

    private MockMultipartFile wavFile(
            String fileName,
            double frequencyHz,
            double seconds,
            double amplitude
    ) throws IOException {
        return wavFile(fileName, SAMPLE_RATE, frequencyHz, seconds, amplitude);
    }

    private MockMultipartFile wavFile(
            String fileName,
            float sampleRate,
            double frequencyHz,
            double seconds,
            double amplitude
    ) throws IOException {
        int sampleCount = (int) Math.round(seconds * sampleRate);
        byte[] pcm = new byte[sampleCount * 2];
        for (int index = 0; index < sampleCount; index++) {
            short sample = (short) Math.round(
                    Short.MAX_VALUE * amplitude * Math.sin(2 * Math.PI * frequencyHz * index / sampleRate));
            pcm[index * 2] = (byte) (sample & 0xff);
            pcm[index * 2 + 1] = (byte) ((sample >>> 8) & 0xff);
        }
        AudioFormat format = new AudioFormat(sampleRate, 16, 1, true, false);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (AudioInputStream input = new AudioInputStream(
                new ByteArrayInputStream(pcm), format, sampleCount)) {
            AudioSystem.write(input, AudioFileFormat.Type.WAVE, output);
        }
        return new MockMultipartFile("file", fileName, "audio/wav", output.toByteArray());
    }

    private MockMultipartFile wavHeaderFile(String fileName, int sampleRate) {
        ByteBuffer wav = ByteBuffer.allocate(46).order(ByteOrder.LITTLE_ENDIAN);
        wav.put("RIFF".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        wav.putInt(38);
        wav.put("WAVEfmt ".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        wav.putInt(16);
        wav.putShort((short) 1);
        wav.putShort((short) 1);
        wav.putInt(sampleRate);
        wav.putInt(Math.multiplyExact(sampleRate, 2));
        wav.putShort((short) 2);
        wav.putShort((short) 16);
        wav.put("data".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        wav.putInt(2);
        wav.putShort((short) 0);
        return new MockMultipartFile("file", fileName, "audio/wav", wav.array());
    }
}
