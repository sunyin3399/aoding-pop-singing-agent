package com.sunyin.aodingagent.controller;

import com.sunyin.aodingagent.metrics.MockChatModel;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/stress")
@Profile("stress")
public class StressTestController {

    /**
     * 在压测进行时，动态调整大模型的模拟响应延迟（单位毫秒）
     * 示例：POST http://localhost:8080/admin/stress/config/delay?delayMs=5000
     */
    @PostMapping("/config/delay")
    public String updateLlmDelay(@RequestParam long delayMs) {
        MockChatModel.setLlmDelayMs(delayMs);
        return "【压测控制台】成功将大模型模拟延迟动态调整为: " + delayMs + " ms";
    }
}