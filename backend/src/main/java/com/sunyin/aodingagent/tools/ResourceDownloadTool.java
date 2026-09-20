package com.sunyin.aodingagent.tools;

import cn.hutool.core.io.FileUtil;
import cn.hutool.http.HttpUtil;
import com.sunyin.aodingagent.constant.FileConstant;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.io.File;

/**
 * 资源下载工具类
 * 提供从指定URL下载资源并保存到本地文件系统的功能
 */
public class ResourceDownloadTool {

    /**
     * 从指定URL下载资源并保存到本地文件系统
     *
     * @param url 要下载的资源的URL地址
     * @param fileName 保存下载资源的文件名
     * @return 返回下载结果信息，成功时显示保存路径，失败时显示错误信息
     */
    @Tool(description = "Download a resource from a given URL")
    public String downloadResource(@ToolParam(description = "URL of the resource to download") String url, @ToolParam(description = "Name of the file to save the downloaded resource") String fileName) {
        // 构建文件保存目录路径，使用FileConstant中定义的基础目录加上"/download"子目录
        String fileDir = FileConstant.FILE_SAVE_DIR + "/download";
        // 构建完整的文件保存路径
        String filePath = fileDir + "/" + fileName;
        try {
            // 创建目录
            FileUtil.mkdir(fileDir);
            // 使用 Hutool 的 downloadFile 方法下载资源
            HttpUtil.downloadFile(url, new File(filePath));
            return "Resource downloaded successfully to: " + filePath;
        } catch (Exception e) {
            return "Error downloading resource: " + e.getMessage();
        }
    }
}
