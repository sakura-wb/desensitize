package com.desensitize.ai;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AuditResult {

    private String type;          // 敏感类型（兼容旧版）

    private String category;      // 敏感类别

    private String content;       // 敏感内容

    private String position;      // 位置信息

    private String severity;      // 严重程度

    private String suggestion;    // 处理建议

    public String getCategory() {
        // 兼容旧版，优先返回 category，如果为空则返回 type
        return (category != null && !category.isBlank()) ? category : type;
    }
}