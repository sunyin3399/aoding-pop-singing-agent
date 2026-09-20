package com.sunyin.aodingagent.conversation;

import com.sunyin.aodingagent.conversation.ConversationModels.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 新版前端使用的用户隔离会话和画像接口。 */
@RestController
@RequestMapping("/ai")
public final class ConversationController {

    private final RedisConversationStore store;
    private final UserProfileService profileService;

    public ConversationController(RedisConversationStore store, UserProfileService profileService) {
        this.store = store;
        this.profileService = profileService;
    }

    @GetMapping("/conversations")
    public List<ConversationMetadata> list(@RequestParam String userId,
                                           @RequestParam ConversationMode mode) {
        return store.list(userId, mode);
    }

    @GetMapping("/conversations/{conversationId}")
    public ConversationDetail detail(@PathVariable String conversationId,
                                     @RequestParam String userId,
                                     @RequestParam ConversationMode mode) {
        return store.get(userId, mode, conversationId);
    }

    @DeleteMapping("/conversations/{conversationId}")
    public ResponseEntity<Void> delete(@PathVariable String conversationId,
                                       @RequestParam String userId,
                                       @RequestParam ConversationMode mode) {
        store.delete(userId, mode, conversationId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/users/{userId}/profile")
    public UserProfile profile(@PathVariable String userId) {
        return profileService.get(userId);
    }

    @DeleteMapping("/users/{userId}/profile")
    public ResponseEntity<Void> clearProfile(@PathVariable String userId) {
        profileService.clear(userId);
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler(ConversationNotFoundException.class)
    public ResponseEntity<Void> notFound() {
        return ResponseEntity.notFound().build();
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<String> badRequest(IllegalArgumentException exception) {
        return ResponseEntity.badRequest().body(exception.getMessage());
    }
}
