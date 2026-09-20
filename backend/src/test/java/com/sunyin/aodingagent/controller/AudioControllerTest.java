package com.sunyin.aodingagent.controller;

import com.sunyin.aodingagent.audio.AudioAnalysisService;
import com.sunyin.aodingagent.audio.AudioPitchTrackResult;
import com.sunyin.aodingagent.audio.AudioSpectrumResult;
import com.sunyin.aodingagent.audio.AudioSpectrogramResult;
import com.sunyin.aodingagent.audio.VocalScoringService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AudioControllerTest {

    private static final int TEN_MEBIBYTES = 10 * 1024 * 1024;

    @Test
    void returnsSpectrumForMultipartAudio() throws Exception {
        MockMvc mockMvc = mockMvcFor(new StubAudioAnalysisService(spectrumResult(), null, null, null));
        MockMultipartFile file = new MockMultipartFile(
                "file", "tone.wav", "audio/wav", new byte[]{1, 2, 3});

        mockMvc.perform(multipart("/ai/audio/spectrum").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fileName").value("tone.wav"))
                .andExpect(jsonPath("$.bands.low.startHz").value(20.0))
                .andExpect(jsonPath("$.segments[0].dominantBand").value("MID"));
    }

    @Test
    void returnsBadRequestWhenSpectrumAnalysisRejectsAudio() throws Exception {
        MockMvc mockMvc = mockMvcFor(new StubAudioAnalysisService(
                null, null, null, new IllegalArgumentException("仅支持 WAV、MP3、M4A 音频")));
        MockMultipartFile file = new MockMultipartFile(
                "file", "audio.txt", "text/plain", new byte[]{1});

        mockMvc.perform(multipart("/ai/audio/spectrum").file(file))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("仅支持 WAV、MP3、M4A 音频"));
    }

    @Test
    void returnsSpectrogramForMultipartAudio() throws Exception {
        MockMvc mockMvc = mockMvcFor(new StubAudioAnalysisService(null, spectrogramResult(), null, null));
        MockMultipartFile file = new MockMultipartFile(
                "file", "tone.wav", "audio/wav", new byte[]{1, 2, 3});

        mockMvc.perform(multipart("/ai/audio/spectrogram").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fftSize").value(8192))
                .andExpect(jsonPath("$.frequenciesHz[0]").value(20.5))
                .andExpect(jsonPath("$.peakDbfs[0]").value(-12.5))
                .andExpect(jsonPath("$.frames[0].dbfs[0]").value(-24.5));
    }

    @Test
    void returnsPitchTrackForMultipartAudio() throws Exception {
        MockMvc mockMvc = mockMvcFor(new StubAudioAnalysisService(null, null, pitchTrackResult(), null));
        MockMultipartFile file = new MockMultipartFile(
                "file", "voice.wav", "audio/wav", new byte[]{1, 2, 3});

        mockMvc.perform(multipart("/ai/audio/pitch-track").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fileName").value("voice.wav"))
                .andExpect(jsonPath("$.points[0].note").value("A3"))
                .andExpect(jsonPath("$.summary.robustLowestNote").value("A3"))
                .andExpect(jsonPath("$.summary.rangeSemitones").doesNotExist())
                .andExpect(jsonPath("$.summary.voicedRatio").doesNotExist())
                .andExpect(jsonPath("$.analysisScope").isNotEmpty());
    }

    @Test
    void returnsBadRequestWhenSpectrogramAnalysisRejectsAudio() throws Exception {
        MockMvc mockMvc = mockMvcFor(new StubAudioAnalysisService(
                null, null, null, new IllegalArgumentException("仅支持 WAV、MP3、M4A 音频")));
        MockMultipartFile file = new MockMultipartFile(
                "file", "audio.txt", "text/plain", new byte[]{1});

        mockMvc.perform(multipart("/ai/audio/spectrogram").file(file))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("仅支持 WAV、MP3、M4A 音频"));
    }

    @Test
    void returnsJsonBadRequestWhenMultipartInfrastructureRejectsTheUpload() throws Exception {
        MockMvc mockMvc = mockMvcFor(new StubAudioAnalysisService(
                null, null, null, new MaxUploadSizeExceededException(10L * 1024 * 1024)));
        MockMultipartFile file = new MockMultipartFile(
                "file", "over.wav", "audio/wav", new byte[]{1});

        mockMvc.perform(multipart("/ai/audio/spectrogram").file(file))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("音频文件不能超过 10MB"));
    }

    @Test
    void honorsExactTenMebibyteBoundaryThroughTheMultipartRoute() throws Exception {
        MockMvc mockMvc = mockMvcFor(new AudioAnalysisService());
        MockMultipartFile exact = new MockMultipartFile(
                "file", "exact.wav", "audio/wav", exactSizeWav());
        MockMultipartFile over = new MockMultipartFile(
                "file", "over.wav", "audio/wav", new byte[TEN_MEBIBYTES + 1]);

        mockMvc.perform(multipart("/ai/audio/spectrogram").file(exact))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sampleRate").value(192_000));
        mockMvc.perform(multipart("/ai/audio/spectrogram").file(over))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("音频文件不能超过 10MB"));
    }

    @Test
    void returnsJsonBadRequestForCraftedExtremeSampleRateThroughTheRealService() throws Exception {
        MockMvc mockMvc = mockMvcFor(new AudioAnalysisService());
        MockMultipartFile file = new MockMultipartFile(
                "file", "hostile.wav", "audio/wav", wavHeaderWithSampleRate(1_000_000));

        mockMvc.perform(multipart("/ai/audio/spectrogram").file(file))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("音频采样率必须在 8000 Hz 到 192000 Hz 之间"));
    }

    private MockMvc mockMvcFor(AudioAnalysisService audioAnalysisService) {
        AudioController controller = new AudioController(audioAnalysisService, new VocalScoringService());
        return MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new AudioUploadExceptionHandler())
                .build();
    }

    private AudioSpectrumResult spectrumResult() {
        AudioSpectrumResult.BandDefinitions bands = new AudioSpectrumResult.BandDefinitions(
                new AudioSpectrumResult.BandDefinition(20, 250),
                new AudioSpectrumResult.BandDefinition(250, 2000),
                new AudioSpectrumResult.BandDefinition(2000, 8000)
        );
        AudioSpectrumResult.SpectrumSummary overall = new AudioSpectrumResult.SpectrumSummary(
                0.1, 0.8, 0.1, AudioSpectrumResult.DominantBand.MID);
        AudioSpectrumResult.SpectrumSegment segment = new AudioSpectrumResult.SpectrumSegment(
                0, 1, 0.1, 0.8, 0.1,
                -20, -8, -20, 1000.0, AudioSpectrumResult.DominantBand.MID);
        return new AudioSpectrumResult(
                "tone.wav", 1, 16_000, 8_000, bands, overall, List.of(segment));
    }

    private AudioSpectrogramResult spectrogramResult() {
        return new AudioSpectrogramResult(
                "tone.wav", 1, 48_000, 24_000, 8192, 0.1,
                20, 24_000, -120, 0,
                List.of(20.5, 21.5),
                List.of(-12.5, -18.5),
                List.of(new AudioSpectrogramResult.SpectrogramFrame(0, List.of(-24.5, -36.5)))
        );
    }

    private AudioPitchTrackResult pitchTrackResult() {
        AudioPitchTrackResult.PitchPoint point = new AudioPitchTrackResult.PitchPoint(
                0, 220.0, 57.0, "A3", 0.0, 1, true, -12);
        AudioPitchTrackResult.PitchSummary summary = new AudioPitchTrackResult.PitchSummary(
                "A3", "A4", "A3", "A4", "E4", "A3-A4", 12.0, 0, 66.6);
        return new AudioPitchTrackResult(
                "voice.wav", 1, 16_000, 0.1, 55, 71,
                List.of(point), List.of(), summary, List.of(),
                "音高概览", "无参考旋律分析");
    }

    private byte[] exactSizeWav() {
        int channels = 8;
        int sampleRate = 192_000;
        int frameSize = channels * 2;
        ByteBuffer wav = ByteBuffer.allocate(TEN_MEBIBYTES).order(ByteOrder.LITTLE_ENDIAN);
        wav.put("RIFF".getBytes(StandardCharsets.US_ASCII));
        wav.putInt(TEN_MEBIBYTES - 8);
        wav.put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII));
        wav.putInt(16);
        wav.putShort((short) 1);
        wav.putShort((short) channels);
        wav.putInt(sampleRate);
        wav.putInt(sampleRate * frameSize);
        wav.putShort((short) frameSize);
        wav.putShort((short) 16);
        wav.put("JUNK".getBytes(StandardCharsets.US_ASCII));
        wav.putInt(12);
        wav.position(wav.position() + 12);
        wav.put("data".getBytes(StandardCharsets.US_ASCII));
        wav.putInt(TEN_MEBIBYTES - 64);
        return wav.array();
    }

    private byte[] wavHeaderWithSampleRate(int sampleRate) {
        ByteBuffer wav = ByteBuffer.allocate(46).order(ByteOrder.LITTLE_ENDIAN);
        wav.put("RIFF".getBytes(StandardCharsets.US_ASCII));
        wav.putInt(38);
        wav.put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII));
        wav.putInt(16);
        wav.putShort((short) 1);
        wav.putShort((short) 1);
        wav.putInt(sampleRate);
        wav.putInt(Math.multiplyExact(sampleRate, 2));
        wav.putShort((short) 2);
        wav.putShort((short) 16);
        wav.put("data".getBytes(StandardCharsets.US_ASCII));
        wav.putInt(2);
        wav.putShort((short) 0);
        return wav.array();
    }

    private static class StubAudioAnalysisService extends AudioAnalysisService {
        private final AudioSpectrumResult spectrumResult;
        private final AudioSpectrogramResult spectrogramResult;
        private final AudioPitchTrackResult pitchTrackResult;
        private final RuntimeException failure;

        private StubAudioAnalysisService(
                AudioSpectrumResult spectrumResult,
                AudioSpectrogramResult spectrogramResult,
                AudioPitchTrackResult pitchTrackResult,
                RuntimeException failure
        ) {
            this.spectrumResult = spectrumResult;
            this.spectrogramResult = spectrogramResult;
            this.pitchTrackResult = pitchTrackResult;
            this.failure = failure;
        }

        @Override
        public AudioSpectrumResult analyzeSpectrum(MultipartFile file) throws IOException {
            if (failure != null) throw failure;
            return spectrumResult;
        }

        @Override
        public AudioSpectrogramResult analyzeSpectrogram(MultipartFile file) throws IOException {
            if (failure != null) throw failure;
            return spectrogramResult;
        }


        @Override
        public AudioPitchTrackResult analyzePitchTrack(MultipartFile file) throws IOException {
            if (failure != null) throw failure;
            return pitchTrackResult;
        }
    }
}
