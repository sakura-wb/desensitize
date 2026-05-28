package com.desensitize.ai;

import com.desensitize.core.config.DesensitizeConfigProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Slf4j
public class AiDesensitizeUtil {

    private static volatile ChatClient chatClient;

    private static volatile String promptTemplate;

    private static volatile String auditPromptTemplate;

    private static volatile String stringAuditPromptTemplate;

    private static volatile Duration timeout = Duration.ofSeconds(60);

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final int MAX_CONTENT_LENGTH = 50000;

    private AiDesensitizeUtil() {
    }

    public static void initialize(ChatClient client, DesensitizeConfigProperties properties) {
        chatClient = client;
        if (properties.getAi() != null && properties.getAi().getPromptTemplate() != null) {
            promptTemplate = properties.getAi().getPromptTemplate();
        } else {
            throw new IllegalStateException("AI脱敏提示词模板未配置，请在 desensitize-config.yml 的 ai.prompt-template 中配置");
        }
        if (properties.getAi() != null && properties.getAi().getAuditPromptTemplate() != null) {
            auditPromptTemplate = properties.getAi().getAuditPromptTemplate();
        } else {
            log.warn("AI审核提示词模板未配置，文件审核功能将不可用");
        }
        if (properties.getAi() != null && properties.getAi().getStringAuditPromptTemplate() != null) {
            stringAuditPromptTemplate = properties.getAi().getStringAuditPromptTemplate();
        } else {
            log.warn("AI字符串审核提示词模板未配置，字符串审核功能将不可用");
        }
        log.info("AI脱敏模块初始化完成，提示词模板已加载");
    }

    public static String mask(String content) {
        if (content == null || content.isEmpty()) {
            return content;
        }

        if (!isAvailable()) {
            throw new IllegalStateException("AI脱敏模块未初始化，请先调用initialize方法");
        }

        if (content.length() > MAX_CONTENT_LENGTH) {
            throw new IllegalArgumentException("待脱敏内容过长，最大支持" + MAX_CONTENT_LENGTH + "字符");
        }

        try {
            log.info("AI脱敏提示词模板:\n{}", promptTemplate);
            log.info("待脱敏内容:\n{}", content);

            String userMessage = promptTemplate + "\n\n待脱敏内容：\n" + content;

            String result = chatClient.prompt()
                    .user(userMessage)
                    .call()
                    .content();

            log.info("AI远程处理结果:\n{}", result);

            return result != null ? result : content;
        } catch (Exception e) {
            log.error("AI脱敏调用失败: {}", e.getMessage());
            throw new RuntimeException("AI脱敏调用失败: " + e.getMessage(), e);
        }
    }

    public static List<AuditResult> auditFile(String filePath) {
        if (filePath == null || filePath.isBlank()) {
            throw new IllegalArgumentException("文件路径不能为空");
        }

        if (chatClient == null) {
            throw new IllegalStateException("AI脱敏模块未初始化，请先调用initialize方法");
        }

        if (auditPromptTemplate == null || auditPromptTemplate.isBlank()) {
            throw new IllegalStateException("AI审核提示词模板未配置，请在 desensitize-config.yml 的 ai.audit-prompt-template 中配置");
        }

        Path path = Paths.get(filePath).toAbsolutePath().normalize();
        Path workDir = Paths.get(".").toAbsolutePath().normalize();
        if (!path.startsWith(workDir) && !path.startsWith(Paths.get(System.getProperty("java.io.tmpdir")).normalize())) {
            log.warn("拒绝非法文件路径: {}", filePath);
            throw new IllegalArgumentException("非法的文件路径");
        }

        if (!Files.exists(path)) {
            throw new IllegalArgumentException("文件不存在: " + filePath);
        }
        if (!Files.isReadable(path)) {
            throw new IllegalArgumentException("文件不可读: " + filePath);
        }
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException("不是一个有效的文件: " + filePath);
        }

        long fileSize;
        try {
            fileSize = Files.size(path);
        } catch (IOException e) {
            throw new RuntimeException("无法获取文件大小: " + filePath, e);
        }
        if (fileSize > MAX_CONTENT_LENGTH) {
            throw new IllegalArgumentException("文件过大，最大支持" + MAX_CONTENT_LENGTH + "字节，当前文件: " + fileSize + "字节");
        }

        try {
            Resource fileResource = createUtf8Resource(path);
            log.info("开始AI文件审核，文件: {}，大小: {}字节", path.getFileName(), fileResource.contentLength());
            log.info("AI审核提示词模板:\n{}", auditPromptTemplate);

            String result = chatClient.prompt()
                    .user(userSpec -> userSpec
                            .text(auditPromptTemplate)
                            .text(fileResource))
                    .call()
                    .content();

            log.info("AI文件审核结果:\n{}", result);

            if (result == null || result.isBlank()) {
                return Collections.emptyList();
            }

            String trimmed = result.trim();
            if ("NO_SENSITIVE_DATA".equalsIgnoreCase(trimmed)) {
                return Collections.emptyList();
            }

            return parseAuditResults(trimmed);
        } catch (Exception e) {
            log.error("AI文件审核失败: {}", e.getMessage());
            throw new RuntimeException("AI文件审核失败: " + e.getMessage(), e);
        }
    }

    public static List<AuditResult> auditString(String content) {
        return audit(content);
    }

    public static List<AuditResult> audit(String content) {
        if (content == null || content.isEmpty()) {
            return Collections.emptyList();
        }

        if (chatClient == null) {
            throw new IllegalStateException("AI脱敏模块未初始化，请先调用initialize方法");
        }

        if (stringAuditPromptTemplate == null || stringAuditPromptTemplate.isBlank()) {
            throw new IllegalStateException("AI字符串审核提示词模板未配置，字符串审核功能将不可用");
        }

        if (content.length() > MAX_CONTENT_LENGTH) {
            throw new IllegalArgumentException("待审核内容过长，最大支持" + MAX_CONTENT_LENGTH + "字符");
        }

        try {
            log.info("开始AI字符串审核，内容长度: {}", content.length());
            log.info("AI字符串审核提示词模板:\n{}", stringAuditPromptTemplate);

            String userMessage = stringAuditPromptTemplate + "\n\n待审核内容：\n" + content;

            String result = chatClient.prompt()
                    .user(userMessage)
                    .call()
                    .content();

            log.info("AI字符串审核结果:\n{}", result);

            if (result == null || result.isBlank()) {
                return Collections.emptyList();
            }

            String trimmed = result.trim();
            if ("NO_SENSITIVE_DATA".equalsIgnoreCase(trimmed)) {
                return Collections.emptyList();
            }

            return parseAuditResults(trimmed);
        } catch (Exception e) {
            log.error("AI字符串审核失败: {}", e.getMessage());
            throw new RuntimeException("AI字符串审核失败: " + e.getMessage(), e);
        }
    }

    private static Resource createUtf8Resource(Path path) throws IOException {
        byte[] rawBytes = Files.readAllBytes(path);
        String content;
        try {
            content = new String(rawBytes, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("UTF-8解码失败，尝试使用系统默认编码: {}", Charset.defaultCharset().name());
            content = new String(rawBytes, Charset.defaultCharset());
        }
        byte[] utf8Bytes = content.getBytes(StandardCharsets.UTF_8);
        return new ByteArrayResource(utf8Bytes) {
            @Override
            public String getFilename() {
                return path.getFileName().toString();
            }
        };
    }

    private static List<AuditResult> parseAuditResults(String aiResponse) {
        List<AuditResult> results = new ArrayList<>();
        String[] lines = aiResponse.split("\\r?\\n");
        for (String line : lines) {
            String trimmedLine = line.trim();
            if (trimmedLine.isEmpty()) {
                continue;
            }
            if ("NO_SENSITIVE_DATA".equalsIgnoreCase(trimmedLine)) {
                continue;
            }
            try {
                AuditResult item = OBJECT_MAPPER.readValue(trimmedLine, AuditResult.class);
                if (item.getCategory() != null && !item.getCategory().isBlank()
                        && item.getContent() != null && !item.getContent().isBlank()) {
                    results.add(item);
                }
            } catch (JsonProcessingException e) {
                log.warn("解析审核结果JSON失败，跳过该行: {}", trimmedLine);
            }
        }
        return results;
    }

    public static boolean isAvailable() {
        return chatClient != null && promptTemplate != null && !promptTemplate.isBlank();
    }

    public static boolean isAuditAvailable() {
        return chatClient != null && auditPromptTemplate != null && !auditPromptTemplate.isBlank();
    }

    public static boolean isStringAuditAvailable() {
        return chatClient != null && stringAuditPromptTemplate != null && !stringAuditPromptTemplate.isBlank();
    }
}
