package com.sunyin.aodingagent.executor;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Component
public class ExecutorRegister {


//    @Bean(name = "llmRequestExecutor")
//    public ExecutorService llmRequestExecutor() {
//        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
//        executor.setCorePoolSize(50);
//        executor.setMaxPoolSize(100);
//        executor.setQueueCapacity(10);
//        executor.setThreadNamePrefix("AI-platform-");
//        executor.setKeepAliveSeconds(60);
//        executor.setRejectedExecutionHandler((r, e) -> {
//            throw new java.util.concurrent.RejectedExecutionException("当前AI请求服务排队人数较多，请稍后再试");
//        });
//        executor.initialize();
//        return executor.getThreadPoolExecutor();
//    }

    @Bean(name = "llmRequestExecutor")
    public ExecutorService llmRequestExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
