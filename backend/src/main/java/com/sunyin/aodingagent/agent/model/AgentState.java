package com.sunyin.aodingagent.agent.model;

/**
 * 代理执行状态的枚举类  
 */  
public enum AgentState {  
  
    /**  
     * 空闲状态  
     */  
    IDLE,  
  
    /**  
     * 运行中状态  
     */  
    RUNNING,  
  
    /**
     * 总结中状态（调用doTerminate后进入此状态生成最终总结）
     */
    SUMMARIZING,
    
    /**
     * 已完成状态
     */
    FINISHED,  
  
    /**  
     * 错误状态  
     */  
    ERROR  
}
