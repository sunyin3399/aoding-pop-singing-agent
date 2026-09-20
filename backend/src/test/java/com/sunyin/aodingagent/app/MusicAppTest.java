package com.sunyin.aodingagent.app;

import cn.hutool.core.lang.UUID;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class MusicAppTest {

    @Resource
    private MusicApp musicApp;

    @Test
    void testChat() {
        String chatId = UUID.randomUUID().toString();
        // 第一轮
        String message = "你好，我是奥丁";
        String answer = musicApp.doChat(message, chatId);
        Assertions.assertNotNull(answer);
        // 第二轮
        message = "我想学习声乐相关技巧，我现在演唱时声音发虚，闭合不好";
        answer = musicApp.doChat(message, chatId);
        Assertions.assertNotNull(answer);
        // 第三轮
        message = "我的问题是什么？你帮我回忆一下";
        answer = musicApp.doChat(message, chatId);
        Assertions.assertNotNull(answer);
    }

    @Test
    void doChatWithReport() {
        String chatId = UUID.randomUUID().toString();
        // 第一轮
        String message = "你好，我是奥丁，我想让我的高音更有力，但我总是会破音，我该怎么做";
        MusicApp.MusicReport loveReport = musicApp.doChatWithReport(message, chatId);
        Assertions.assertNotNull(loveReport);
    }

    @Test
    void doChatWithRag() {
        String chatId = UUID.randomUUID().toString();
        String message = "我是初学者，给我一些基础气息训练练习";
        String answer =  musicApp.doChatWithCloudRag(message, chatId);
        Assertions.assertNotNull(answer);
    }

    @Test
    void doChatWithTools() {
        // 测试联网搜索问题的答案
        testMessage("林俊杰最经典的几个现场");

        // 测试网页抓取：本月最火的歌手TOP3
        testMessage("看看QQ音乐网站里（https://y.qq.com/?ADTAG=myqq#type=index）本月最火的歌手TOP3都是谁");

        // 测试资源下载：图片下载
        testMessage("直接下载一张腹式呼吸解析图");

        // 测试终端操作：执行代码
        testMessage("执行 Python3 脚本来生成数据分析报告");

        // 测试文件操作：保存用户档案
        testMessage("保存我的学习档案为文件");

        // 测试 PDF 生成
        testMessage("生成一份‘初学者声乐7天训练计划’PDF，包含学习目标、理论原理和错误示例");
    }

    private void testMessage(String message) {
        String chatId = UUID.randomUUID().toString();
        String answer = musicApp.doChatWithTools(message, chatId);
        Assertions.assertNotNull(answer);
    }

}
