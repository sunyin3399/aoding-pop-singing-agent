package com.sunyin.aodingagent.metrics;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import lombok.extern.slf4j.Slf4j;

@Aspect
@Component
@Profile("stress")
@Slf4j
public class MockToolInterceptor {

    /**
     * 拦截你项目中所有 Tool 回调或工具类的执行
     * 1. 匹配你自定义工具的包名 (例如：com.yourpackage.tool)
     * 2. 匹配所有实现了 Spring AI 官方函数回调接口的类 (FunctionCallback)
     */
    @Around("execution(* com.sunyin.aodingagent.tools..*.*(..)) || this(org.springframework.ai.model.function.FunctionCallback) || this(org.springframework.ai.tool.ToolCallback)")
    public Object mockToolLatency(ProceedingJoinPoint joinPoint) throws Throwable {
        long start = System.currentTimeMillis();

        try {
            // 模拟本地执行 SQL 或调用外部轻量级 API 导致的 100ms I/O 阻塞
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // 放行（你可以选择拦截直接返回假数据，或者让它执行无害的本地逻辑）
        Object result = joinPoint.proceed();

        log.info("[Mock Tool] 工具: {} 拦截成功，注入 100ms 延迟", joinPoint.getSignature().getName());
        return result;
    }
}