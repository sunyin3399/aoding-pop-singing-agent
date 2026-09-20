package com.sunyin.aodingagent.audio;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import ws.schild.jave.Encoder;
import ws.schild.jave.EncoderException;
import ws.schild.jave.MultimediaObject;
import ws.schild.jave.encode.AudioAttributes;
import ws.schild.jave.encode.EncodingAttributes;
import ws.schild.jave.process.ProcessLocator;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;
import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 读取用户上传的音频并计算可解释的基础声学数据，是音频分析功能的核心服务。
 * <p>
 * 它先把 WAV、MP3 或 M4A 统一解码成 PCM 采样，再根据不同接口计算整体响度、波形、频谱、
 * 声谱图或音高轨迹。文件大小、时长、格式和采样率会在计算前受到限制，避免异常文件占用
 * 过多内存和 CPU。
 * <p>
 * 这些结果只能说明录音中的基础声学现象。没有歌曲的参考旋律和节拍时，系统不知道用户
 * “应该唱哪个音”，因此不能据此判断歌曲音准、歌词音符或节奏是否正确，也不用于医疗诊断。
 */
@Service
public class AudioAnalysisService {
    private final HighNoteHighlightAnalyzer highNoteHighlightAnalyzer = new HighNoteHighlightAnalyzer();

    static final long MAX_FILE_BYTES = 10L * 1024 * 1024;
    static final double MAX_DURATION_SECONDS = 180.0;
    private static final double SILENCE_RMS = 0.01;
    private static final double MIN_PITCH_HZ = 70.0;
    private static final double MAX_PITCH_HZ = 1000.0;
    private static final double LOW_FREQUENCY_HZ = 20.0;
    private static final double MID_FREQUENCY_HZ = 250.0;
    private static final double HIGH_FREQUENCY_HZ = 2000.0;
    private static final int SPECTRUM_FFT_SIZE = 1024;
    private static final int SPECTRUM_HOP_SIZE = 512;
    private static final int MEL_BANDS = 64;
    private static final int MAX_SPECTROGRAM_FRAMES = 720;
    private static final double SPECTROGRAM_MIN_DB = -80.0;
    private static final int SPECTROGRAM_BUCKETS = 240;
    private static final int DETAILED_WINDOW_MILLIS = 170;
    private static final int DETAILED_HOP_MILLIS = 100;
    private static final double DETAILED_MIN_DBFS = -120.0;
    private static final int MIN_SOURCE_SAMPLE_RATE = 8_000;
    private static final int MAX_SOURCE_SAMPLE_RATE = 192_000;
    private static final int MAX_DETAILED_FFT_SIZE = 65_536;
    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of("wav", "mp3", "m4a");
    private final ProcessLocator ffmpegLocator;
    private final CompressedAudioDecoder compressedAudioDecoder;

    public AudioAnalysisService() {
        this(null, null);
    }

    /**
     * Spring 生产构造器。未配置路径时使用 JAVE 自带的 FFmpeg。
     */
    @Autowired
    public AudioAnalysisService(@Value("${audio.ffmpeg.executable:}") String ffmpegExecutable) {
        this(ffmpegExecutable == null || ffmpegExecutable.isBlank()
                ? null
                : () -> ffmpegExecutable, null);
    }

    AudioAnalysisService(ProcessLocator ffmpegLocator) {
        this(ffmpegLocator, null);
    }

    AudioAnalysisService(CompressedAudioDecoder compressedAudioDecoder) {
        this(null, compressedAudioDecoder);
    }

    private AudioAnalysisService(
            ProcessLocator ffmpegLocator,
            CompressedAudioDecoder compressedAudioDecoder
    ) {
        this.ffmpegLocator = ffmpegLocator;
        this.compressedAudioDecoder = compressedAudioDecoder;
    }

    /** 生成响度、峰值、音高稳定性、有效发声比例和前端可视化所需的综合分析。 */
    public AudioAnalysisResult analyze(MultipartFile file) throws IOException {
        DecodedAudio audio = decode(file, 16_000);
        return analyzeSamples(audio.fileName(), audio.samples(), audio.sampleRate(), audio.channels());
    }

    /** 计算整段录音的总体频谱，用于观察能量主要分布在哪些频率。 */
    public AudioSpectrumResult analyzeSpectrum(MultipartFile file) throws IOException {
        DecodedAudio audio = decode(file, 16_000);
        return analyzeSpectrumSamples(audio.fileName(), audio.samples(), audio.sampleRate(), audio.channels());
    }

    /** 计算随时间变化的详细声谱图，用于在频率实验室中查看不同时间的频率能量。 */
    public AudioSpectrogramResult analyzeSpectrogram(MultipartFile file) throws IOException {
        DecodedAudio audio = decode(file, 48_000);
        validateSourceSampleRate(audio.sampleRate());
        return analyzeSpectrogramSamples(audio.fileName(), audio.samples(), audio.sampleRate(), audio.channels());
    }

    /** 计算音高随时间的轨迹、稳健音域和最多三个具有代表性的高音片段。 */
    public AudioPitchTrackResult analyzePitchTrack(MultipartFile file) throws IOException {
        DecodedAudio audio = decode(file, 16_000);
        return analyzePitchTrackSamples(audio.fileName(), audio.samples(), audio.sampleRate(), audio.channels());
    }

    /**
     * 计算人声"发声行为"分析：感知频段分布（EQ 视角）+ 按音区聚合的发声机制指标 + 行为推断。
     * 采样到 32kHz，使 Nyquist=16kHz 足以覆盖空气感频段（8-15kHz）。
     */
    public VocalProductionResult analyzeVocalProduction(MultipartFile file) throws IOException {
        DecodedAudio audio = decode(file, 32_000);
        return analyzeVocalProductionSamples(audio.fileName(), audio.samples(), audio.sampleRate());
    }

    VocalProductionResult analyzeVocalProductionSamples(String fileName, double[] samples, float sampleRate) {
        if (samples.length == 0) {
            throw new IllegalArgumentException("音频中没有可分析的采样数据");
        }
        VocalFeatureAnalyzer.VocalFeatures features = VocalFeatureAnalyzer.analyze(samples, sampleRate);
        List<VocalProductionResult.RegisterAnalysis> registers =
                VocalBehaviorClassifier.buildRegisters(features);
        List<VocalProductionResult.BehaviorInference> behaviors =
                VocalBehaviorClassifier.inferBehaviors(features);
        String summary = VocalBehaviorClassifier.buildSummary(features, registers, behaviors);
        VocalControlAnalyzer.ControlResult control = VocalControlAnalyzer.analyze(features);
        String scope = "人声发声行为倾向推断基于声学特征假设（阈值待校准）；仅用于演唱练习辅助，不构成嗓音疾病或声带状态诊断。";
        return new VocalProductionResult(
                fileName,
                round(samples.length / sampleRate, 2),
                sampleRate,
                round(sampleRate / 2.0, 2),
                features.bandEnergies(),
                List.copyOf(registers),
                List.copyOf(behaviors),
                summary,
                scope,
                control.registerControls(),
                control.breakEvents(),
                control.controlNote());
    }

    AudioPitchTrackResult analyzePitchTrackSamples(
            String fileName, double[] samples, float sampleRate, int channels) {
        if (samples.length == 0) throw new IllegalArgumentException("音频中没有可分析的采样数据");
        List<FrameFeature> frames = analyzeFrames(samples, sampleRate);
        List<AudioPitchTrackResult.PitchPoint> points = new ArrayList<>(frames.size());
        List<Double> midiValues = new ArrayList<>();
        List<Double> localChanges = new ArrayList<>();
        Double previousMidi = null;
        int breaks = 0;
        boolean previousVoiced = false;
        for (FrameFeature frame : frames) {
            boolean voiced = frame.pitchHz() != null;
            Double midi = voiced ? frequencyToMidi(frame.pitchHz()) : null;
            if (midi != null) {
                midiValues.add(midi);
                if (previousMidi != null) localChanges.add(Math.abs(midi - previousMidi) * 100);
                previousMidi = midi;
            } else {
                previousMidi = null;
                if (previousVoiced) breaks++;
            }
            previousVoiced = voiced;
            double nearestMidi = midi == null ? 0 : Math.rint(midi);
            points.add(new AudioPitchTrackResult.PitchPoint(
                    round(frame.timeSeconds(), 3),
                    nullableRound(frame.pitchHz(), 2),
                    nullableRound(midi, 3),
                    midi == null ? null : midiToNote(nearestMidi),
                    midi == null ? null : round((midi - nearestMidi) * 100, 1),
                    round(frame.confidence(), 3),
                    voiced,
                    round(Math.max(-120, toDbfs(frame.rms())), 1)));
        }
        Collections.sort(midiValues);
        Collections.sort(localChanges);
        // 最低/最高单点容易被噪声误判，因此用 5% 和 95% 分位数表示更可靠的“稳健音域”。
        double low = midiValues.isEmpty() ? 48 : percentile(midiValues, 0.05);
        double high = midiValues.isEmpty() ? 72 : percentile(midiValues, 0.95);
        double absoluteLow = midiValues.isEmpty() ? low : midiValues.getFirst();
        double absoluteHigh = midiValues.isEmpty() ? high : midiValues.getLast();
        double median = midiValues.isEmpty() ? (low + high) / 2 : percentile(midiValues, 0.5);
        double q1 = midiValues.isEmpty() ? low : percentile(midiValues, 0.25);
        double q3 = midiValues.isEmpty() ? high : percentile(midiValues, 0.75);
        Double stability = localChanges.isEmpty() ? null : round(percentile(localChanges, 0.5), 1);
        Double highNoteThreshold = midiValues.isEmpty() ? null
                : round(highNoteHighlightAnalyzer.highNoteThresholdMidi(low, high), 2);
        AudioPitchTrackResult.PitchSummary summary = new AudioPitchTrackResult.PitchSummary(
                midiValues.isEmpty() ? null : midiToNote(Math.rint(absoluteLow)),
                midiValues.isEmpty() ? null : midiToNote(Math.rint(absoluteHigh)),
                midiValues.isEmpty() ? null : midiToNote(Math.rint(low)),
                midiValues.isEmpty() ? null : midiToNote(Math.rint(high)),
                midiValues.isEmpty() ? null : midiToNote(Math.rint(median)),
                midiValues.isEmpty() ? null : midiToNote(Math.rint(q1)) + "–" + midiToNote(Math.rint(q3)),
                stability, breaks, highNoteThreshold);
        List<AudioPitchTrackResult.HighNoteHighlight> highlights = midiValues.isEmpty() ? List.of()
                : highNoteHighlightAnalyzer.analyze(points, samples, sampleRate,
                samples.length / sampleRate, low, high);
        // 把能力边界放进返回结果，确保前端和后续 LLM 都不会把基础分析包装成歌曲音准评分。
        String scope = "无参考旋律的基础音高表现分析；不评价歌曲音准、歌词音符或节奏准确性。";
        String llmSummary = buildPitchLlmSummary(summary, highlights, scope);
        return new AudioPitchTrackResult(
                fileName, round(samples.length / sampleRate, 2), sampleRate,
                frames.size() < 2 ? 0 : round(frames.get(1).timeSeconds() - frames.getFirst().timeSeconds(), 3),
                Math.floor((low - 3) / 12) * 12, Math.ceil((high + 3) / 12) * 12,
                List.copyOf(points), pitchWaveform(samples, sampleRate), summary,
                highlights, llmSummary, scope);
    }

    private List<AudioPitchTrackResult.WaveformPoint> pitchWaveform(double[] samples, float sampleRate) {
        int bins = Math.min(600, samples.length);
        List<AudioPitchTrackResult.WaveformPoint> waveform = new ArrayList<>(bins);
        for (int bin = 0; bin < bins; bin++) {
            int start = bin * samples.length / bins;
            int end = Math.max(start + 1, (bin + 1) * samples.length / bins);
            double min = 1;
            double max = -1;
            for (int index = start; index < end; index++) {
                min = Math.min(min, samples[index]);
                max = Math.max(max, samples[index]);
            }
            waveform.add(new AudioPitchTrackResult.WaveformPoint(
                    round((start + end) / 2.0 / sampleRate, 3), round(min, 4), round(max, 4)));
        }
        return List.copyOf(waveform);
    }

    private String buildPitchLlmSummary(
            AudioPitchTrackResult.PitchSummary summary,
            List<AudioPitchTrackResult.HighNoteHighlight> highlights,
            String scope) {
        String range = summary.lowestNote() == null ? "未获得可靠音高" :
                "%s 到 %s，常用音区 %s"
                        .formatted(summary.robustLowestNote(), summary.robustHighestNote(), summary.tessitura());
        String stability = summary.pitchStabilityCents() == null ? "稳定性数据不足" :
                "相邻有效音高变化中位数 %.1f cents".formatted(summary.pitchStabilityCents());
        String evidence = highlights.stream().map(highlight -> {
            String inference = highlight.inferences().stream().map(item -> item.label() + "（" + item.confidence() + "）")
                    .collect(java.util.stream.Collectors.joining("、"));
            return "%.1f–%.1f 秒，推测主要音 %s，最长连续 %.1f 秒%s"
                    .formatted(highlight.startSeconds(), highlight.endSeconds(), highlight.representativeNote(),
                            highlight.longestContinuousSeconds(), inference.isBlank() ? "" : "，" + inference);
        }).collect(java.util.stream.Collectors.joining("；"));
        return "稳健有效音域：%s。%s。高音精选：%s。限制：%s"
                .formatted(range, stability, evidence.isBlank() ? "未检测到足够可靠的相对高音片段" : evidence, scope);
    }

    private double frequencyToMidi(double frequency) {
        return 69 + 12 * Math.log(frequency / 440.0) / Math.log(2);
    }

    private String midiToNote(double midi) {
        String[] names = {"C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"};
        int value = (int) Math.round(midi);
        return names[Math.floorMod(value, 12)] + (Math.floorDiv(value, 12) - 1);
    }

    private DecodedAudio decode(MultipartFile file, int compressedTargetSampleRate) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("请选择非空 WAV 音频文件");
        }
        if (file.getSize() > MAX_FILE_BYTES) {
            throw new IllegalArgumentException("音频文件不能超过 10MB");
        }
        String fileName = file.getOriginalFilename() == null ? "audio.wav" : file.getOriginalFilename();
        String extension = fileExtension(fileName);
        if (!SUPPORTED_EXTENSIONS.contains(extension)) {
            throw new IllegalArgumentException("仅支持 WAV、MP3、M4A 音频");
        }

        if ("wav".equals(extension)) {
            return decodeAudioStream(fileName, new BufferedInputStream(new ByteArrayInputStream(file.getBytes())));
        }
        if (compressedAudioDecoder != null) {
            return compressedAudioDecoder.decode(file, fileName, extension, compressedTargetSampleRate);
        }
        return transcodeAndDecode(file, fileName, extension, compressedTargetSampleRate);
    }

    private DecodedAudio transcodeAndDecode(
            MultipartFile file,
            String originalFileName,
            String extension,
            int compressedTargetSampleRate
    )
            throws IOException {
        Path tempDirectory = Files.createTempDirectory("aoding-audio-");
        Path sourcePath = tempDirectory.resolve("source." + extension);
        Path wavPath = tempDirectory.resolve("decoded.wav");
        try {
            file.transferTo(sourcePath);
            MultimediaObject sourceAudio = ffmpegLocator == null
                    ? new MultimediaObject(sourcePath.toFile())
                    : new MultimediaObject(sourcePath.toFile(), ffmpegLocator);
            long durationMillis = sourceAudio.getInfo().getDuration();
            if (durationMillis > MAX_DURATION_SECONDS * 1000) {
                throw new IllegalArgumentException("音频时长不能超过 3 分钟");
            }
            AudioAttributes audio = new AudioAttributes();
            audio.setCodec("pcm_s16le");
            audio.setChannels(1);
            audio.setSamplingRate(compressedTargetSampleRate);

            EncodingAttributes attributes = new EncodingAttributes();
            attributes.setOutputFormat("wav");
            attributes.setAudioAttributes(audio);
            Encoder encoder = ffmpegLocator == null ? new Encoder() : new Encoder(ffmpegLocator);
            encoder.encode(
                    sourceAudio,
                    wavPath.toFile(),
                    attributes
            );
            try (BufferedInputStream input = new BufferedInputStream(Files.newInputStream(wavPath))) {
                return decodeAudioStream(originalFileName, input);
            }
        } catch (EncoderException exception) {
            throw new IllegalArgumentException("无法解码音频，请确认 MP3/M4A 文件完整且编码有效", exception);
        } finally {
            deleteQuietly(wavPath);
            deleteQuietly(sourcePath);
            deleteQuietly(tempDirectory);
        }
    }

    private DecodedAudio decodeAudioStream(String fileName, BufferedInputStream input) throws IOException {
        try (AudioInputStream source = AudioSystem.getAudioInputStream(input)) {
            AudioFormat sourceFormat = source.getFormat();
            AudioFormat pcmFormat = new AudioFormat(
                    AudioFormat.Encoding.PCM_SIGNED,
                    sourceFormat.getSampleRate(),
                    16,
                    sourceFormat.getChannels(),
                    sourceFormat.getChannels() * 2,
                    sourceFormat.getSampleRate(),
                    false
            );
            if (!AudioSystem.isConversionSupported(pcmFormat, sourceFormat)) {
                throw new IllegalArgumentException("WAV 编码不受支持，请导出为 16-bit PCM WAV");
            }
            try (AudioInputStream pcm = AudioSystem.getAudioInputStream(pcmFormat, source)) {
                byte[] bytes = readLimited(pcm, pcmFormat);
                double[] mono = decodeMono16BitLittleEndian(bytes, pcmFormat.getChannels());
                return new DecodedAudio(fileName, mono, pcmFormat.getSampleRate(), pcmFormat.getChannels());
            }
        } catch (UnsupportedAudioFileException e) {
            throw new IllegalArgumentException("无法解析音频，请确认文件编码有效", e);
        }
    }

    private String fileExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private void deleteQuietly(Path path) {
        if (path == null) return;
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // 临时文件由系统临时目录的生命周期继续兜底清理
        }
    }

    private byte[] readLimited(AudioInputStream stream, AudioFormat format) throws IOException {
        long maxPcmBytes = (long) (MAX_DURATION_SECONDS * format.getFrameRate() * format.getFrameSize());
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        long total = 0;
        while ((read = stream.read(buffer)) != -1) {
            total += read;
            if (total > maxPcmBytes) {
                throw new IllegalArgumentException("音频时长不能超过 3 分钟");
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private double[] decodeMono16BitLittleEndian(byte[] bytes, int channels) {
        int frameSize = channels * 2;
        double[] samples = new double[bytes.length / frameSize];
        for (int frame = 0; frame < samples.length; frame++) {
            double sum = 0;
            int frameOffset = frame * frameSize;
            for (int channel = 0; channel < channels; channel++) {
                int offset = frameOffset + channel * 2;
                short value = (short) ((bytes[offset] & 0xff) | (bytes[offset + 1] << 8));
                sum += value / 32768.0;
            }
            samples[frame] = sum / channels;
        }
        return samples;
    }

    AudioAnalysisResult analyzeSamples(String fileName, double[] samples, float sampleRate, int channels) {
        if (samples.length == 0) {
            throw new IllegalArgumentException("音频中没有可分析的采样数据");
        }

        double squareSum = 0;
        double peak = 0;
        int zeroCrossings = 0;
        for (int i = 0; i < samples.length; i++) {
            double sample = samples[i];
            squareSum += sample * sample;
            peak = Math.max(peak, Math.abs(sample));
            if (i > 0 && Math.signum(sample) != Math.signum(samples[i - 1])) {
                zeroCrossings++;
            }
        }
        double rms = Math.sqrt(squareSum / samples.length);
        double zcr = samples.length > 1 ? zeroCrossings / (double) (samples.length - 1) : 0;
        List<FrameFeature> frameFeatures = analyzeFrames(samples, sampleRate);
        List<Double> pitches = frameFeatures.stream()
                .map(FrameFeature::pitchHz)
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        Collections.sort(pitches);

        Double medianPitch = pitches.isEmpty() ? null : percentile(pitches, 0.5);
        Double minPitch = pitches.isEmpty() ? null : percentile(pitches, 0.05);
        Double maxPitch = pitches.isEmpty() ? null : percentile(pitches, 0.95);
        Double stability = pitches.size() < 2 ? null : pitchStandardDeviationCents(pitches, medianPitch);
        int analyzedFrames = countAnalysisFrames(samples.length, sampleRate);
        double voicedRatio = analyzedFrames == 0 ? 0 : pitches.size() / (double) analyzedFrames;

        List<String> observations = buildObservations(rms, peak, voicedRatio, stability);
        return new AudioAnalysisResult(
                fileName,
                round(samples.length / sampleRate, 2),
                sampleRate,
                channels,
                round(toDbfs(rms), 2),
                round(toDbfs(peak), 2),
                round(zcr, 4),
                nullableRound(medianPitch, 2),
                nullableRound(minPitch, 2),
                nullableRound(maxPitch, 2),
                nullableRound(stability, 2),
                round(voicedRatio, 3),
                observations,
                buildVisualization(samples, sampleRate, frameFeatures)
        );
    }

    AudioSpectrumResult analyzeSpectrumSamples(
            String fileName,
            double[] samples,
            float sampleRate,
            int channels
    ) {
        if (samples.length == 0) {
            throw new IllegalArgumentException("音频中没有可分析的采样数据");
        }
        int samplesPerSegment = Math.max(1, Math.round(sampleRate));
        List<AudioSpectrumResult.SpectrumSegment> segments = new ArrayList<>();
        double[] overallEnergy = new double[3];
        for (int start = 0; start < samples.length; start += samplesPerSegment) {
            int end = Math.min(samples.length, start + samplesPerSegment);
            SegmentSpectrum analysis = analyzeSpectrumSegment(samples, start, end, sampleRate);
            segments.add(analysis.segment());
            for (int band = 0; band < overallEnergy.length; band++) {
                overallEnergy[band] += analysis.bandEnergy()[band];
            }
        }

        double overallTotal = Arrays.stream(overallEnergy).sum();
        AudioSpectrumResult.DominantBand overallDominant = overallTotal <= 0
                ? AudioSpectrumResult.DominantBand.SILENCE
                : dominantBand(overallEnergy);
        double nyquist = sampleRate / 2.0;
        return new AudioSpectrumResult(
                fileName,
                round(samples.length / sampleRate, 2),
                sampleRate,
                round(nyquist, 2),
                new AudioSpectrumResult.BandDefinitions(
                        new AudioSpectrumResult.BandDefinition(LOW_FREQUENCY_HZ, MID_FREQUENCY_HZ),
                        new AudioSpectrumResult.BandDefinition(MID_FREQUENCY_HZ, HIGH_FREQUENCY_HZ),
                        new AudioSpectrumResult.BandDefinition(HIGH_FREQUENCY_HZ, round(nyquist, 2))
                ),
                new AudioSpectrumResult.SpectrumSummary(
                        round(ratio(overallEnergy[0], overallTotal), 4),
                        round(ratio(overallEnergy[1], overallTotal), 4),
                        round(ratio(overallEnergy[2], overallTotal), 4),
                        overallDominant),
                List.copyOf(segments)
        );
    }

    AudioSpectrogramResult analyzeSpectrogramSamples(
            String fileName,
            double[] samples,
            float sampleRate,
            int channels
    ) {
        if (samples.length == 0) {
            throw new IllegalArgumentException("音频中没有可分析的采样数据");
        }
        validateSourceSampleRate(sampleRate);

        int windowSamples = checkedDetailedSampleCount(sampleRate, DETAILED_WINDOW_MILLIS);
        int hopSamples = checkedDetailedSampleCount(sampleRate, DETAILED_HOP_MILLIS);
        int fftSize = checkedDetailedFftSize(windowSamples);
        double nyquist = sampleRate / 2.0;

        double[] bucketEdges = new double[SPECTROGRAM_BUCKETS + 1];
        List<Double> frequencies = new ArrayList<>(SPECTROGRAM_BUCKETS);
        double edgeRatio = Math.pow(nyquist / LOW_FREQUENCY_HZ, 1.0 / SPECTROGRAM_BUCKETS);
        for (int bucket = 0; bucket <= SPECTROGRAM_BUCKETS; bucket++) {
            bucketEdges[bucket] = LOW_FREQUENCY_HZ * Math.pow(edgeRatio, bucket);
        }
        bucketEdges[SPECTROGRAM_BUCKETS] = nyquist;
        for (int bucket = 0; bucket < SPECTROGRAM_BUCKETS; bucket++) {
            frequencies.add(round(Math.sqrt(bucketEdges[bucket] * bucketEdges[bucket + 1]), 2));
        }

        double[] peakDbfs = new double[SPECTROGRAM_BUCKETS];
        Arrays.fill(peakDbfs, DETAILED_MIN_DBFS);
        List<AudioSpectrogramResult.SpectrogramFrame> frames = new ArrayList<>();
        for (int start = 0; start < samples.length; start += hopSamples) {
            int realSampleCount = Math.min(windowSamples, samples.length - start);
            double mean = Arrays.stream(samples, start, start + realSampleCount).average().orElse(0);
            double[] real = new double[fftSize];
            double[] imaginary = new double[fftSize];
            double windowSquareSum = 0;
            for (int index = 0; index < realSampleCount; index++) {
                double window = realSampleCount == 1
                        ? 1
                        : 0.5 - 0.5 * Math.cos(2 * Math.PI * index / (realSampleCount - 1));
                real[index] = (samples[start + index] - mean) * window;
                windowSquareSum += window * window;
            }

            double[] bucketPower = new double[SPECTROGRAM_BUCKETS];
            if (windowSquareSum > 0) {
                fft(real, imaginary);
                double normalization = fftSize * windowSquareSum;
                int displayBucket = 0;
                for (int bin = 0; bin <= fftSize / 2; bin++) {
                    double frequency = bin * sampleRate / fftSize;
                    if (frequency < LOW_FREQUENCY_HZ) continue;
                    while (displayBucket < SPECTROGRAM_BUCKETS - 1
                            && frequency >= bucketEdges[displayBucket + 1]) {
                        displayBucket++;
                    }
                    if (frequency > nyquist) break;
                    double power = (real[bin] * real[bin] + imaginary[bin] * imaginary[bin])
                            / normalization;
                    if (bin > 0 && bin < fftSize / 2) {
                        power *= 2;
                    }
                    bucketPower[displayBucket] += power;
                }
            }

            List<Double> dbfs = new ArrayList<>(SPECTROGRAM_BUCKETS);
            for (int bucket = 0; bucket < SPECTROGRAM_BUCKETS; bucket++) {
                double rms = Math.sqrt(bucketPower[bucket]);
                double value = rms > 0 ? 20 * Math.log10(rms) : DETAILED_MIN_DBFS;
                value = round(Math.max(DETAILED_MIN_DBFS, Math.min(0, value)), 1);
                dbfs.add(value);
                peakDbfs[bucket] = Math.max(peakDbfs[bucket], value);
            }
            frames.add(new AudioSpectrogramResult.SpectrogramFrame(
                    round(start / sampleRate, 2),
                    List.copyOf(dbfs)
            ));
        }

        List<Double> peaks = Arrays.stream(peakDbfs).boxed().toList();
        return new AudioSpectrogramResult(
                fileName,
                round(samples.length / sampleRate, 2),
                sampleRate,
                round(nyquist, 2),
                fftSize,
                round(hopSamples / sampleRate, 2),
                LOW_FREQUENCY_HZ,
                round(nyquist, 2),
                DETAILED_MIN_DBFS,
                0,
                List.copyOf(frequencies),
                List.copyOf(peaks),
                List.copyOf(frames)
        );
    }

    private SegmentSpectrum analyzeSpectrumSegment(
            double[] samples,
            int start,
            int end,
            float sampleRate
    ) {
        int realSampleCount = end - start;
        double squareSum = 0;
        for (int index = start; index < end; index++) {
            squareSum += samples[index] * samples[index];
        }
        double rms = Math.sqrt(squareSum / realSampleCount);
        if (rms < SILENCE_RMS) {
            return silenceSpectrum(start, end, sampleRate);
        }

        int fftSize = nextPowerOfTwo(realSampleCount);
        double[] real = new double[fftSize];
        double[] imaginary = new double[fftSize];
        double windowSquareSum = 0;
        for (int index = 0; index < realSampleCount; index++) {
            double window = realSampleCount == 1
                    ? 1
                    : 0.5 - 0.5 * Math.cos(2 * Math.PI * index / (realSampleCount - 1));
            real[index] = samples[start + index] * window;
            windowSquareSum += window * window;
        }
        if (windowSquareSum <= 1e-12) {
            return silenceSpectrum(start, end, sampleRate);
        }
        fft(real, imaginary);

        double[] bandPower = new double[3];
        double weightedFrequency = 0;
        double totalPower = 0;
        for (int bin = 0; bin <= fftSize / 2; bin++) {
            double frequency = bin * sampleRate / fftSize;
            if (frequency < LOW_FREQUENCY_HZ) continue;
            double rawPower = real[bin] * real[bin] + imaginary[bin] * imaginary[bin];
            double power = bin == fftSize / 2 ? rawPower : 2 * rawPower;
            int band = frequency < MID_FREQUENCY_HZ ? 0 : frequency < HIGH_FREQUENCY_HZ ? 1 : 2;
            bandPower[band] += power;
            totalPower += power;
            weightedFrequency += frequency * power;
        }
        if (totalPower <= 1e-12) {
            return silenceSpectrum(start, end, sampleRate);
        }

        double[] bandMeanSquare = new double[3];
        double[] bandEnergy = new double[3];
        for (int band = 0; band < bandPower.length; band++) {
            bandMeanSquare[band] = bandPower[band] / (fftSize * windowSquareSum);
            bandEnergy[band] = bandMeanSquare[band] * realSampleCount;
        }
        AudioSpectrumResult.DominantBand dominant = dominantBand(bandPower);
        AudioSpectrumResult.SpectrumSegment segment = new AudioSpectrumResult.SpectrumSegment(
                round(start / sampleRate, 2),
                round(end / sampleRate, 2),
                round(ratio(bandPower[0], totalPower), 4),
                round(ratio(bandPower[1], totalPower), 4),
                round(ratio(bandPower[2], totalPower), 4),
                round(bandDbfs(bandMeanSquare[0]), 2),
                round(bandDbfs(bandMeanSquare[1]), 2),
                round(bandDbfs(bandMeanSquare[2]), 2),
                round(weightedFrequency / totalPower, 2),
                dominant
        );
        return new SegmentSpectrum(segment, bandEnergy);
    }

    private SegmentSpectrum silenceSpectrum(int start, int end, float sampleRate) {
        return new SegmentSpectrum(
                new AudioSpectrumResult.SpectrumSegment(
                        round(start / sampleRate, 2),
                        round(end / sampleRate, 2),
                        0, 0, 0,
                        -120.0, -120.0, -120.0,
                        null,
                        AudioSpectrumResult.DominantBand.SILENCE
                ),
                new double[3]
        );
    }

    private AudioSpectrumResult.DominantBand dominantBand(double[] bandPower) {
        if (bandPower[0] >= bandPower[1] && bandPower[0] >= bandPower[2]) {
            return AudioSpectrumResult.DominantBand.LOW;
        }
        if (bandPower[1] >= bandPower[2]) {
            return AudioSpectrumResult.DominantBand.MID;
        }
        return AudioSpectrumResult.DominantBand.HIGH;
    }

    private double bandDbfs(double meanSquare) {
        if (meanSquare <= 0) return -120.0;
        return toDbfs(Math.sqrt(meanSquare));
    }

    private double ratio(double value, double total) {
        return total <= 0 ? 0 : value / total;
    }

    private int nextPowerOfTwo(int value) {
        int result = 1;
        while (result < value) result <<= 1;
        return result;
    }

    private void validateSourceSampleRate(float sampleRate) {
        if (!Float.isFinite(sampleRate)
                || sampleRate < MIN_SOURCE_SAMPLE_RATE
                || sampleRate > MAX_SOURCE_SAMPLE_RATE) {
            throw new IllegalArgumentException("音频采样率必须在 8000 Hz 到 192000 Hz 之间");
        }
    }

    private int checkedDetailedSampleCount(float sampleRate, int durationMillis) {
        try {
            long milliHertz = Math.round((double) sampleRate * 1_000);
            long scaledSamples = Math.multiplyExact(milliHertz, durationMillis);
            long roundedSamples = Math.addExact(scaledSamples, 500_000) / 1_000_000;
            return Math.max(1, Math.toIntExact(roundedSamples));
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("音频采样率无法安全生成频谱", exception);
        }
    }

    private int checkedDetailedFftSize(int windowSamples) {
        try {
            long fftSize = windowSamples <= 1
                    ? 1
                    : Math.multiplyExact(Long.highestOneBit(windowSamples - 1L), 2L);
            if (fftSize > MAX_DETAILED_FFT_SIZE) {
                throw new IllegalArgumentException("音频采样率无法安全生成频谱");
            }
            return Math.toIntExact(fftSize);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("音频采样率无法安全生成频谱", exception);
        }
    }

    /** 委托共享 {@link Fft} 工具类，避免在多个分析模块里重复维护同一份 FFT 实现。 */
    private void fft(double[] real, double[] imaginary) {
        Fft.fft(real, imaginary);
    }

    private record SegmentSpectrum(
            AudioSpectrumResult.SpectrumSegment segment,
            double[] bandEnergy
    ) { }

    @FunctionalInterface
    interface CompressedAudioDecoder {
        DecodedAudio decode(
                MultipartFile file,
                String fileName,
                String extension,
                int compressedTargetSampleRate
        ) throws IOException;
    }

    record DecodedAudio(
            String fileName,
            double[] samples,
            float sampleRate,
            int channels
    ) { }

    private List<FrameFeature> analyzeFrames(double[] samples, float sampleRate) {
        int frameSize = Math.max(1024, Integer.highestOneBit((int) (sampleRate * 0.046)));
        int hopSize = frameSize / 2;
        List<FrameFeature> frames = new ArrayList<>();
        int minLag = Math.max(1, (int) (sampleRate / MAX_PITCH_HZ));
        int maxLag = Math.min(frameSize / 2, (int) (sampleRate / MIN_PITCH_HZ));

        for (int start = 0; start + frameSize <= samples.length; start += hopSize) {
            double mean = Arrays.stream(samples, start, start + frameSize).average().orElse(0);
            double energy = 0;
            for (int i = start; i < start + frameSize; i++) {
                double centered = samples[i] - mean;
                energy += centered * centered;
            }
            double frameRms = Math.sqrt(energy / frameSize);
            if (frameRms < SILENCE_RMS) {
                frames.add(new FrameFeature((start + frameSize / 2.0) / sampleRate, frameRms, null, 0));
                continue;
            }

            double[] correlations = new double[maxLag + 1];
            for (int lag = minLag; lag <= maxLag; lag++) {
                double numerator = 0;
                double leftEnergy = 0;
                double rightEnergy = 0;
                for (int i = 0; i < frameSize - lag; i++) {
                    double left = samples[start + i] - mean;
                    double right = samples[start + i + lag] - mean;
                    numerator += left * right;
                    leftEnergy += left * left;
                    rightEnergy += right * right;
                }
                double denominator = Math.sqrt(leftEnergy * rightEnergy);
                correlations[lag] = denominator == 0 ? 0 : numerator / denominator;
            }
            int bestLag = -1;
            for (int lag = minLag + 1; lag < maxLag; lag++) {
                if (correlations[lag] >= 0.65
                        && correlations[lag] >= correlations[lag - 1]
                        && correlations[lag] > correlations[lag + 1]) {
                    bestLag = lag;
                    break;
                }
            }
            if (bestLag > 0) {
                frames.add(new FrameFeature((start + frameSize / 2.0) / sampleRate,
                        frameRms, (double) sampleRate / bestLag, correlations[bestLag]));
            } else {
                frames.add(new FrameFeature((start + frameSize / 2.0) / sampleRate, frameRms, null, 0));
            }
        }
        return frames;
    }

    private AudioAnalysisResult.AudioVisualizationData buildVisualization(
            double[] samples, float sampleRate, List<FrameFeature> frames) {
        int waveformBins = Math.min(600, samples.length);
        List<AudioAnalysisResult.WaveformPoint> waveform = new ArrayList<>(waveformBins);
        for (int bin = 0; bin < waveformBins; bin++) {
            int start = bin * samples.length / waveformBins;
            int end = Math.max(start + 1, (bin + 1) * samples.length / waveformBins);
            double min = 1;
            double max = -1;
            for (int i = start; i < end; i++) {
                min = Math.min(min, samples[i]);
                max = Math.max(max, samples[i]);
            }
            waveform.add(new AudioAnalysisResult.WaveformPoint(
                    round((start + end) / 2.0 / sampleRate, 3), round(min, 4), round(max, 4)));
        }

        int step = Math.max(1, (int) Math.ceil(frames.size() / 900.0));
        List<AudioAnalysisResult.TimeValuePoint> rms = new ArrayList<>();
        List<AudioAnalysisResult.TimeValuePoint> pitch = new ArrayList<>();
        for (int i = 0; i < frames.size(); i += step) {
            FrameFeature frame = frames.get(i);
            rms.add(new AudioAnalysisResult.TimeValuePoint(
                    round(frame.timeSeconds(), 3), round(toDbfs(frame.rms()), 2)));
            pitch.add(new AudioAnalysisResult.TimeValuePoint(
                    round(frame.timeSeconds(), 3), nullableRound(frame.pitchHz(), 2)));
        }
        SpectralAnalysis spectral = analyzeSpectrum(samples, sampleRate);
        return new AudioAnalysisResult.AudioVisualizationData(
                List.copyOf(waveform), List.copyOf(rms), List.copyOf(pitch),
                spectral.spectrogram(), spectral.features());
    }

    private SpectralAnalysis analyzeSpectrum(double[] samples, float sampleRate) {
        int fftSize = SPECTRUM_FFT_SIZE;
        if (samples.length < fftSize) {
            return new SpectralAnalysis(
                    AudioAnalysisResult.MelSpectrogramData.empty(),
                    AudioAnalysisResult.SpectralFeatureSummary.empty());
        }

        int totalFrames = 1 + (samples.length - fftSize) / SPECTRUM_HOP_SIZE;
        int outputStep = Math.max(1, (int) Math.ceil(totalFrames / (double) MAX_SPECTROGRAM_FRAMES));
        double[][] melFilters = createMelFilterbank(sampleRate, fftSize, MEL_BANDS, 60.0, sampleRate / 2.0);
        List<Double> melFrequencies = createMelFrequencies(MEL_BANDS, 60.0, sampleRate / 2.0);
        List<double[]> melPowerFrames = new ArrayList<>();
        List<Double> frameTimes = new ArrayList<>();
        List<Double> centroids = new ArrayList<>();
        List<Double> bandwidths = new ArrayList<>();
        List<Double> rolloffs = new ArrayList<>();
        List<Double> flatnesses = new ArrayList<>();
        double peakMelPower = 1e-20;

        double[] window = new double[fftSize];
        for (int i = 0; i < fftSize; i++) {
            window[i] = 0.5 - 0.5 * Math.cos(2.0 * Math.PI * i / (fftSize - 1));
        }

        for (int frame = 0; frame < totalFrames; frame++) {
            int start = frame * SPECTRUM_HOP_SIZE;
            double[] real = new double[fftSize];
            double[] imaginary = new double[fftSize];
            double frameEnergy = 0;
            for (int i = 0; i < fftSize; i++) {
                double sample = samples[start + i];
                frameEnergy += sample * sample;
                real[i] = sample * window[i];
            }
            fft(real, imaginary);

            int bins = fftSize / 2 + 1;
            double[] magnitude = new double[bins];
            double[] power = new double[bins];
            for (int bin = 0; bin < bins; bin++) {
                power[bin] = (real[bin] * real[bin] + imaginary[bin] * imaginary[bin])
                        / (fftSize * (double) fftSize);
                magnitude[bin] = Math.sqrt(power[bin]);
            }

            if (frameEnergy / fftSize >= SILENCE_RMS * SILENCE_RMS) {
                addSpectralFeatures(magnitude, sampleRate, fftSize,
                        centroids, bandwidths, rolloffs, flatnesses);
            }

            if (frame % outputStep == 0 || frame == totalFrames - 1) {
                double[] melPower = new double[MEL_BANDS];
                for (int mel = 0; mel < MEL_BANDS; mel++) {
                    double sum = 0;
                    for (int bin = 0; bin < bins; bin++) {
                        sum += melFilters[mel][bin] * power[bin];
                    }
                    melPower[mel] = sum;
                    peakMelPower = Math.max(peakMelPower, sum);
                }
                melPowerFrames.add(melPower);
                frameTimes.add(round((start + fftSize / 2.0) / sampleRate, 3));
            }
        }

        List<List<Integer>> levels = new ArrayList<>(melPowerFrames.size());
        for (double[] melPower : melPowerFrames) {
            List<Integer> frameLevels = new ArrayList<>(MEL_BANDS);
            for (double value : melPower) {
                double db = 10.0 * Math.log10(Math.max(value, 1e-20) / peakMelPower);
                int level = (int) Math.round(255 * Math.max(0, Math.min(1,
                        (db - SPECTROGRAM_MIN_DB) / -SPECTROGRAM_MIN_DB)));
                frameLevels.add(level);
            }
            levels.add(List.copyOf(frameLevels));
        }

        AudioAnalysisResult.MelSpectrogramData spectrogram = new AudioAnalysisResult.MelSpectrogramData(
                fftSize, SPECTRUM_HOP_SIZE, SPECTROGRAM_MIN_DB, 0,
                List.copyOf(melFrequencies), List.copyOf(frameTimes), List.copyOf(levels));
        AudioAnalysisResult.SpectralFeatureSummary features = new AudioAnalysisResult.SpectralFeatureSummary(
                medianRounded(centroids), medianRounded(bandwidths),
                medianRounded(rolloffs), medianRounded(flatnesses));
        return new SpectralAnalysis(spectrogram, features);
    }

    private void addSpectralFeatures(double[] magnitude, float sampleRate, int fftSize,
                                     List<Double> centroids, List<Double> bandwidths,
                                     List<Double> rolloffs, List<Double> flatnesses) {
        double sum = 0;
        double weightedFrequency = 0;
        double logSum = 0;
        int positiveBins = 0;
        for (int bin = 1; bin < magnitude.length; bin++) {
            double value = magnitude[bin];
            double frequency = bin * sampleRate / fftSize;
            sum += value;
            weightedFrequency += frequency * value;
            logSum += Math.log(Math.max(value, 1e-12));
            positiveBins++;
        }
        if (sum <= 1e-12) return;

        double centroid = weightedFrequency / sum;
        double variance = 0;
        double cumulative = 0;
        double rolloff = 0;
        for (int bin = 1; bin < magnitude.length; bin++) {
            double frequency = bin * sampleRate / fftSize;
            variance += magnitude[bin] * Math.pow(frequency - centroid, 2);
            cumulative += magnitude[bin];
            if (rolloff == 0 && cumulative >= sum * 0.85) rolloff = frequency;
        }
        double arithmeticMean = sum / positiveBins;
        double flatness = Math.exp(logSum / positiveBins) / Math.max(arithmeticMean, 1e-12);
        centroids.add(centroid);
        bandwidths.add(Math.sqrt(variance / sum));
        rolloffs.add(rolloff);
        flatnesses.add(flatness);
    }

    private double[][] createMelFilterbank(float sampleRate, int fftSize, int bands,
                                           double minHz, double maxHz) {
        int bins = fftSize / 2 + 1;
        double minMel = hzToMel(minHz);
        double maxMel = hzToMel(maxHz);
        double[] edges = new double[bands + 2];
        for (int i = 0; i < edges.length; i++) {
            edges[i] = melToHz(minMel + (maxMel - minMel) * i / (bands + 1.0));
        }
        double[][] filters = new double[bands][bins];
        for (int mel = 0; mel < bands; mel++) {
            double left = edges[mel];
            double center = edges[mel + 1];
            double right = edges[mel + 2];
            double normalization = 2.0 / (right - left);
            for (int bin = 0; bin < bins; bin++) {
                double frequency = bin * sampleRate / fftSize;
                double weight = frequency <= center
                        ? (frequency - left) / (center - left)
                        : (right - frequency) / (right - center);
                filters[mel][bin] = Math.max(0, weight) * normalization;
            }
        }
        return filters;
    }

    private List<Double> createMelFrequencies(int bands, double minHz, double maxHz) {
        double minMel = hzToMel(minHz);
        double maxMel = hzToMel(maxHz);
        List<Double> frequencies = new ArrayList<>(bands);
        for (int i = 0; i < bands; i++) {
            frequencies.add(round(melToHz(minMel + (maxMel - minMel) * i / (bands - 1.0)), 1));
        }
        return frequencies;
    }

    private double hzToMel(double hz) {
        return 2595.0 * Math.log10(1.0 + hz / 700.0);
    }

    private double melToHz(double mel) {
        return 700.0 * (Math.pow(10.0, mel / 2595.0) - 1.0);
    }

    private Double medianRounded(List<Double> values) {
        if (values.isEmpty()) return null;
        List<Double> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        return round(percentile(sorted, 0.5), 2);
    }

    private record SpectralAnalysis(
            AudioAnalysisResult.MelSpectrogramData spectrogram,
            AudioAnalysisResult.SpectralFeatureSummary features) { }

    private record FrameFeature(double timeSeconds, double rms, Double pitchHz, double confidence) { }

    private int countAnalysisFrames(int sampleCount, float sampleRate) {
        int frameSize = Math.max(1024, Integer.highestOneBit((int) (sampleRate * 0.046)));
        if (sampleCount < frameSize) return 0;
        return 1 + (sampleCount - frameSize) / (frameSize / 2);
    }

    private double pitchStandardDeviationCents(List<Double> pitches, double median) {
        double sum = 0;
        for (double pitch : pitches) {
            double cents = 1200 * Math.log(pitch / median) / Math.log(2);
            sum += cents * cents;
        }
        return Math.sqrt(sum / pitches.size());
    }

    private List<String> buildObservations(double rms, double peak, double voicedRatio, Double stability) {
        List<String> observations = new ArrayList<>();
        if (peak >= 0.99) observations.add("检测到接近削波的峰值，录音输入增益可能偏高");
        if (toDbfs(rms) < -35) observations.add("整体录音电平较低，建议靠近麦克风或适当提高输入增益");
        if (voicedRatio < 0.25) observations.add("可稳定估计音高的片段较少，可能包含较多静音、气声或环境噪声");
        if (stability != null && stability > 120) observations.add("音高波动较明显；该指标也会受到旋律跳进和颤音影响");
        if (observations.isEmpty()) observations.add("已完成基础声学特征提取，建议结合具体练习目标和歌词进行解读");
        observations.add("结果仅用于演唱练习辅助，不构成嗓音疾病或声带状态诊断");
        return List.copyOf(observations);
    }

    private double percentile(List<Double> values, double percentile) {
        int index = (int) Math.round((values.size() - 1) * percentile);
        return values.get(index);
    }

    private double toDbfs(double amplitude) {
        return amplitude <= 1e-9 ? -120.0 : 20.0 * Math.log10(amplitude);
    }

    private Double nullableRound(Double value, int scale) {
        return value == null ? null : round(value, scale);
    }

    private double round(double value, int scale) {
        double factor = Math.pow(10, scale);
        return Math.round(value * factor) / factor;
    }
}
