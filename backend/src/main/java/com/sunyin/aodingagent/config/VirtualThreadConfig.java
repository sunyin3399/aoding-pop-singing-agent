package com.sunyin.aodingagent.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration
public class VirtualThreadConfig {

    /**
     * 定义一个基于虚拟线程的异步任务执行器
     * 为 Agent 的底层思考和调用提供无上限的高并发支持y
     */
    @Bean("aiVirtualExecutor")
    public ExecutorService aiVirtualExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}