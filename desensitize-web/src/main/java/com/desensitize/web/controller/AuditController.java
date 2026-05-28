package com.desensitize.web.controller;

import com.desensitize.ai.AiDesensitizeUtil;
import com.desensitize.ai.AuditResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/audit")
public class AuditController {

    private static final long MAX_FILE_SIZE = 50 * 1024 * 1024;
    private static final int MAX_STRING_LENGTH = 50000;

    @PostMapping("/string")
    public ResponseEntity<Map<String, Object>> auditString(@RequestBody Map<String, String> request) {
        Map<String, Object> response = new HashMap<>();
        try {
            String content = request.get("content");
            if (content == null || content.isEmpty()) {
                response.put("success", false);
                response.put("message", "内容不能为空");
                return ResponseEntity.badRequest().body(response);
            }

            List<AuditResult> results = AiDesensitizeUtil.audit(content);

            response.put("success", true);
            response.put("data", results);
            return ResponseEntity.ok(response);
        } catch (IllegalStateException e) {
            response.put("success", false);
            response.put("message", "AI审核模块未就绪: " + e.getMessage());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("AI字符串审核失败", e);
            response.put("success", false);
            response.put("message", "AI审核失败: " + e.getMessage());
            return ResponseEntity.ok(response);
        }
    }

    @PostMapping("/file")
    public ResponseEntity<Map<String, Object>> auditFile(@RequestParam("file") MultipartFile file) {
        Map<String, Object> response = new HashMap<>();
        try {
            if (file.isEmpty()) {
                response.put("success", false);
                response.put("message", "请选择要上传的文件");
                return ResponseEntity.badRequest().body(response);
            }

            if (file.getSize() > MAX_FILE_SIZE) {
                response.put("success", false);
                response.put("message", "文件过大，最大支持50MB");
                return ResponseEntity.badRequest().body(response);
            }

            String originalFilename = sanitizeFilename(file.getOriginalFilename());
            if (originalFilename == null || originalFilename.isBlank()) {
                response.put("success", false);
                response.put("message", "文件名不合法");
                return ResponseEntity.badRequest().body(response);
            }

            String extension = originalFilename.contains(".")
                ? originalFilename.substring(originalFilename.lastIndexOf("."))
                : ".txt";

            String content;
            if (extension.equalsIgnoreCase(".xlsx") || extension.equalsIgnoreCase(".xls")) {
                content = parseExcelContent(file.getInputStream());
            } else if (extension.equalsIgnoreCase(".csv")) {
                content = new String(file.getBytes());
            } else {
                content = new String(file.getBytes());
            }

            if (content.length() > MAX_STRING_LENGTH) {
                response.put("success", false);
                response.put("message", "文件内容过长，最大支持" + MAX_STRING_LENGTH + "字符");
                return ResponseEntity.badRequest().body(response);
            }

            List<AuditResult> results = AiDesensitizeUtil.audit(content);

            Map<String, Object> data = new HashMap<>();
            data.put("originalFile", originalFilename);
            data.put("results", results);

            response.put("success", true);
            response.put("data", data);
            return ResponseEntity.ok(response);
        } catch (IllegalStateException e) {
            response.put("success", false);
            response.put("message", "AI审核模块未就绪: " + e.getMessage());
            return ResponseEntity.ok(response);
        } catch (IOException e) {
            log.error("文件审核失败", e);
            response.put("success", false);
            response.put("message", "文件处理失败: " + e.getMessage());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("文件审核失败", e);
            response.put("success", false);
            response.put("message", "审核失败: " + e.getMessage());
            return ResponseEntity.ok(response);
        }
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> checkStatus() {
        Map<String, Object> response = new HashMap<>();
        try {
            boolean available = AiDesensitizeUtil.isAvailable();
            response.put("success", true);
            response.put("available", available);
            response.put("message", available ? "AI审核模块已就绪" : "AI审核模块未就绪");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            response.put("success", false);
            response.put("available", false);
            response.put("message", "检查失败: " + e.getMessage());
            return ResponseEntity.ok(response);
        }
    }

    private String sanitizeFilename(String filename) {
        if (filename == null || filename.isBlank()) {
            return null;
        }
        String name = filename.replace("\\", "/");
        int lastSlash = name.lastIndexOf('/');
        if (lastSlash >= 0) {
            name = name.substring(lastSlash + 1);
        }
        name = name.replaceAll("[\\\\/:*?\"<>|]", "");
        if (name.isEmpty() || ".".equals(name) || "..".equals(name)) {
            return null;
        }
        return name;
    }

    private String parseExcelContent(java.io.InputStream inputStream) throws Exception {
        org.apache.poi.ss.usermodel.Workbook workbook = org.apache.poi.ss.usermodel.WorkbookFactory.create(inputStream);
        StringBuilder content = new StringBuilder();
        
        for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
            org.apache.poi.ss.usermodel.Sheet sheet = workbook.getSheetAt(i);
            content.append("Sheet[").append(i + 1).append("]: ").append(sheet.getSheetName()).append("\n");
            
            for (int rowIndex = 0; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                org.apache.poi.ss.usermodel.Row row = sheet.getRow(rowIndex);
                if (row != null) {
                    StringBuilder rowContent = new StringBuilder();
                    rowContent.append("行").append(rowIndex + 1).append(": ");
                    
                    for (int colIndex = 0; colIndex < row.getLastCellNum(); colIndex++) {
                        org.apache.poi.ss.usermodel.Cell cell = row.getCell(colIndex);
                        if (cell != null) {
                            rowContent.append(getCellValueAsString(cell));
                        }
                        if (colIndex < row.getLastCellNum() - 1) {
                            rowContent.append(" | ");
                        }
                    }
                    content.append(rowContent).append("\n");
                }
            }
            content.append("\n");
        }
        workbook.close();
        return content.toString();
    }

    private String getCellValueAsString(org.apache.poi.ss.usermodel.Cell cell) {
        if (cell == null) {
            return "";
        }
        switch (cell.getCellType()) {
            case STRING:
                return cell.getStringCellValue();
            case NUMERIC:
                if (org.apache.poi.ss.usermodel.DateUtil.isCellDateFormatted(cell)) {
                    return cell.getLocalDateTimeCellValue().toString();
                }
                double numValue = cell.getNumericCellValue();
                if (numValue == Math.floor(numValue)) {
                    return String.valueOf((long) numValue);
                }
                return String.valueOf(numValue);
            case BOOLEAN:
                return String.valueOf(cell.getBooleanCellValue());
            case FORMULA:
                try {
                    return cell.getStringCellValue();
                } catch (Exception e) {
                    return String.valueOf(cell.getNumericCellValue());
                }
            case BLANK:
                return "";
            default:
                return "";
        }
    }
}
