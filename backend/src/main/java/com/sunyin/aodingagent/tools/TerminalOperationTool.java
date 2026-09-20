package com.sunyin.aodingagent.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

/**
 * TerminalOperationTool 类提供终端命令执行的功能
 * 该类包含一个工具方法，用于在终端中执行命令并获取输出结果
 */
public class TerminalOperationTool {

    /**
     * 在终端中执行命令并返回输出结果
     * @param command 要在终端中执行的命令字符串
     * @return 命令执行后的输出结果，包括标准输出和错误信息
     */
    @Tool(description = "Execute a command in the terminal")
    public String executeTerminalCommand(@ToolParam(description = "Command to execute in the terminal") String command) {
        // 用于存储命令执行输出的字符串构建器
        StringBuilder output = new StringBuilder();
        try {
            // 使用ProcessBuilder构建进程，执行cmd命令
            // 使用cmd.exe /c来执行命令，这样可以正确处理Windows命令
            ProcessBuilder builder = new ProcessBuilder("cmd.exe", "/c", command);
            // 注释掉的代码是另一种执行命令的方式，使用Runtime.exec()
//            Process process = Runtime.getRuntime().exec(command);
            // 启动进程
            Process process = builder.start();
            // 使用try-with-resources语句确保BufferedReader被正确关闭
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                // 逐行读取进程的输出
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }
            // 等待进程执行完成，获取退出码
            int exitCode = process.waitFor();
            // 如果退出码不为0，表示命令执行失败，添加错误信息
            if (exitCode != 0) {
                output.append("Command execution failed with exit code: ").append(exitCode);
            }
        } catch (IOException | InterruptedException e) {
            // 捕获并处理可能发生的IO异常或中断异常
            output.append("Error executing command: ").append(e.getMessage());
        }
        return ToolOutputLimiter.limit(output.toString());
    }
}
