package com.sunyin.aodingagent.controller;

import com.sunyin.aodingagent.audio.AudioAnalysisResult;
import com.sunyin.aodingagent.audio.AudioAnalysisService;
import com.sunyin.aodingagent.audio.AudioPitchTrackResult;
import com.sunyin.aodingagent.audio.AudioSpectrumResult;
import com.sunyin.aodingagent.audio.AudioSpectrogramResult;
import com.sunyin.aodingagent.audio.VocalScoreResult;
import com.sunyin.aodingagent.audio.VocalScoringService;
import com.sunyin.aodingagent.audio.VocalProductionResult;
import com.sunyin.aodingagent.audio.VocalProductionMemoryFormatter;
import com.sunyin.aodingagent.conversation.ConversationModels.ConversationMode;
import com.sunyin.aodingagent.conversation.ConversationTurnCoordinator;
import org.springframework.http.ResponseEntity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;

@RestController
@RequestMapping("/ai/audio")
public class AudioController {

    private final AudioAnalysisService audioAnalysisService;
    private final VocalScoringService vocalScoringService;
    private final ConversationTurnCoordinator conversations;

    @Autowired
    public AudioController(AudioAnalysisService audioAnalysisService, VocalScoringService vocalScoringService,
                           ConversationTurnCoordinator conversations) {
        this.audioAnalysisService = audioAnalysisService;
        this.vocalScoringService = vocalScoringService;
        this.conversations = conversations;
    }

    public AudioController(AudioAnalysisService audioAnalysisService, VocalScoringService vocalScoringService) {
        this(audioAnalysisService, vocalScoringService, null);
    }

    @PostMapping(value = "/analyze", consumes = "multipart/form-data")
    public AudioAnalysisResult analyze(@RequestPart("file") MultipartFile file) throws IOException {
        return audioAnalysisService.analyze(file);
    }

    @PostMapping(value = "/spectrum", consumes = "multipart/form-data")
    public AudioSpectrumResult spectrum(@RequestPart("file") MultipartFile file) throws IOException {
        return audioAnalysisService.analyzeSpectrum(file);
    }

    @PostMapping(value = "/spectrogram", consumes = "multipart/form-data")
    public AudioSpectrogramResult spectrogram(@RequestPart("file") MultipartFile file) throws IOException {
        return audioAnalysisService.analyzeSpectrogram(file);
    }

    @PostMapping(value = "/pitch-track", consumes = "multipart/form-data")
    public AudioPitchTrackResult pitchTrack(@RequestPart("file") MultipartFile file) throws IOException {
        return audioAnalysisService.analyzePitchTrack(file);
    }

    /** 人声"发声行为"分析：频段分布 + 按音区聚合的发声机制指标 + 行为推断。 */
    @PostMapping(value = "/vocal-analysis", consumes = "multipart/form-data")
    public Object vocalAnalysis(@RequestPart("file") MultipartFile file,
                                                @RequestParam(required = false) String userId,
                                                @RequestParam(required = false) ConversationMode mode,
                                                @RequestParam(required = false) String conversationId) throws IOException {
        VocalProductionResult result = audioAnalysisService.analyzeVocalProduction(file);
        if (conversations == null || userId == null || mode == null) return result;
        var turn = conversations.begin(userId, mode, conversationId, "上传音频：" + result.fileName());
        String summary = VocalProductionMemoryFormatter.format(result);
        conversations.completeWithArtifact(turn, userId, mode, "上传音频：" + result.fileName(), summary,
                java.util.List.of(), "VOCAL_PRODUCTION_ANALYSIS", null, summary);
        return new VocalAnalysisResponse(turn.conversationId(), result);
    }

    public record VocalAnalysisResponse(String conversationId, VocalProductionResult result) { }

    @PostMapping(value = "/score", consumes = "multipart/form-data")
    public VocalScoreResult score(@RequestPart("file") MultipartFile file) throws IOException {
        return vocalScoringService.score(audioAnalysisService.analyze(file));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleBadAudio(IllegalArgumentException exception) {
        return ResponseEntity.badRequest().body(Map.of("message", exception.getMessage()));
    }
}
