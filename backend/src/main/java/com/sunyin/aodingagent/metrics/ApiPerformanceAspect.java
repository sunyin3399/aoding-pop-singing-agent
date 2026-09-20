package com.sunyin.aodingagent.metrics;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;
import lombok.extern.slf4j.Slf4j;

@Aspect
@Component
@Slf4j
public class ApiPerformanceAspect {

    @Around("execution(* com.sunyin.aodingagent.controller.*.*(..))")
    public Object trackApiLatency(ProceedingJoinPoint joinPoint) throws Throwable {
        long start = System.currentTimeMillis();
        Object result = joinPoint.proceed();
        long latency = System.currentTimeMillis() - start;
        
        log.info("[API 指标] 接口: {} | 总耗时: {} ms", joinPoint.getSignature().getName(), latency);
        return result;
    }
}