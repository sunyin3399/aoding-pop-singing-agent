package com.sunyin.aodingagent.tools;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.io.IORuntimeException;
import com.sunyin.aodingagent.constant.FileConstant;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * 文件操作工具类
 * 提供文件的读写功能
 */
public class FileOperationTool {

    // 文件存储目录路径常量
    private final String FILE_DIR = FileConstant.FILE_SAVE_DIR + "/file";

    /**
     * 从文件中读取内容
     * @param fileName 要读取的文件名
     * @return 文件内容，如果读取失败则返回错误信息
     */
    @Tool(description = "Read content from a file")
    public String readFile(@ToolParam(description = "Name of a file to read") String fileName) {
        // 构建完整的文件路径
        String filePath = FILE_DIR + "/" + fileName;

        try {
            // 读取文件内容并返回
            return ToolOutputLimiter.limit(FileUtil.readUtf8String(filePath));
        } catch (IORuntimeException e) {
            // 捕获IO异常并返回错误信息
            return "Error reading file: " + e.getMessage();
        }
    }

    /**
     * 向文件写入内容
     * @param fileName 要写入的文件名
     * @param content 要写入文件的内容
     * @return 操作结果信息，成功或失败
     */
    @Tool(description = "Write content to a file")
    public String writeFile(@ToolParam(description = "Name of a file to write") String fileName,
                            @ToolParam(description = "Content to write into the file") String content) {
        // 构建完整的文件路径
        String filePath = FILE_DIR + "/" + fileName;

        try {
            // 创建目录（如果不存在）
            FileUtil.mkdir(filePath);
            // 写入内容到文件
            FileUtil.writeUtf8String(content, filePath);
            // 返回成功信息
            return "File written successfully to: " + filePath;
        } catch (IORuntimeException e) {
            // 捕获IO异常并返回错误信息
            return "Error writing file: " + e.getMessage();
        }
    }


}
