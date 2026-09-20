package com.sunyin.aodingagent.agent;

import com.sunyin.aodingagent.tools.WebSearchTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;

import com.sunyin.aodingagent.agent.model.AgentState;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * ReAct (Reasoning and Acting) 模式的代理抽象类
 * 实现了思考-行动的循环模式
 * <p>
 * 每一步先通过 {@link #think()} 让大模型决定是否需要调用工具；需要时执行 {@link #act()}，
 * 不需要时把模型文本作为最终答案并结束。循环总次数由父类 {@link BaseAgent} 限制。
 */
@EqualsAndHashCode(callSuper = true)
@Data
public abstract class ReActAgent extends BaseAgent {

    private static final Logger logger = LoggerFactory.getLogger(ReActAgent.class);

    /**
     * 处理当前状态并决定下一步行动
     * 
     * @return 是否需要执行行动，true表示需要执行，false表示不需要执行
     */
    public abstract boolean think();

    /**
     * 执行决定的行动
     * 
     * @return 行动执行结果
     */
    public abstract String act();

    /**
     * 执行单个步骤：思考和行动
     * <p>
     * 如果模型没有选择工具并已经给出文本，本方法会保存最终答案并把状态改为 FINISHED；
     * 如果模型选择了工具，则把执行交给 act，下一轮再根据工具结果继续思考。
     * 
     * @return 步骤执行结果
     */
    @Override
    public String step() {
        clearStepTrace();
        try {
            boolean shouldAct = think();
            if (!shouldAct) {
                if (!getMessageList().isEmpty()) {
                    Message lastMessage = getMessageList().get(getMessageList().size() - 1);
                    if (lastMessage instanceof org.springframework.ai.chat.messages.AssistantMessage) {
                        String answer = lastMessage.getText();
                        setFinalAnswer(finalizeAnswer(answer));
                        setState(AgentState.FINISHED);
                        traceStep("已获得最终答案，结束执行并总结回复");
                        return "任务结束，进行最终总结";
                    }
                }
                traceStep("判断无需调用工具，准备直接回复");
                return "想了想，决定无需行动";
            }
            return act();
        } catch (Exception e) {
            // 记录异常日志
            logger.error("步骤执行失败", e);
            traceStep("步骤执行失败：" + e.getMessage());
            return "步骤执行失败: " + e.getMessage();
        }
    }
}
