package com.desensitize.runner.controller;

import com.desensitize.core.engine.DesensitizeUtil;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/desensitize")
@CrossOrigin(origins = "*")
public class DesensitizeController {

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StringRequest {
        private String content;
        private String mode = "long_text";
        private String type;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ResponseResult {
        private boolean success;
        private String message;
        private Object data;

        public static ResponseResult success(Object data) {
            return new ResponseResult(true, "操作成功", data);
        }

        public static ResponseResult error(String message) {
            return new ResponseResult(false, message, null);
        }
    }

    @PostMapping("/string")
    public ResponseEntity<ResponseResult> desensitizeString(@RequestBody StringRequest request) {
        try {
            String content = request.getContent();
            String mode = request.getMode() != null ? request.getMode() : "long_text";
            
            String result;
            switch (mode) {
                case "single":
                    if (request.getType() == null || request.getType().isBlank()) {
                        return ResponseEntity.badRequest().body(ResponseResult.error("单一类型脱敏必须指定type参数"));
                    }
                    result = DesensitizeUtil.mask(content, request.getType());
                    break;
                case "ai":
                    try {
                        result = com.desensitize.ai.AiDesensitizeUtil.mask(content);
                    } catch (Exception e) {
                        log.error("AI脱敏失败", e);
                        return ResponseEntity.ok(ResponseResult.error("AI脱敏失败: " + e.getMessage()));
                    }
                    break;
                case "long_text":
                default:
                    result = DesensitizeUtil.maskLongText(content);
                    break;
            }

            Map<String, String> data = new HashMap<>();
            data.put("original", content);
            data.put("masked", result);
            return ResponseEntity.ok(ResponseResult.success(data));
        } catch (Exception e) {
            log.error("字符串脱敏失败", e);
            return ResponseEntity.ok(ResponseResult.error("脱敏失败: " + e.getMessage()));
        }
    }

    @PostMapping("/file")
    public ResponseEntity<ResponseResult> desensitizeFile(@RequestParam("file") MultipartFile file) {
        try {
            if (file.isEmpty()) {
                return ResponseEntity.badRequest().body(ResponseResult.error("请选择要上传的文件"));
            }

            String originalFilename = file.getOriginalFilename();
            String extension = originalFilename != null && originalFilename.contains(".") 
                ? originalFilename.substring(originalFilename.lastIndexOf(".")) 
                : ".txt";

            Path resultDir = Paths.get("result");
            if (!Files.exists(resultDir)) {
                Files.createDirectories(resultDir);
            }

            String content = new String(file.getBytes(), "UTF-8");
            String maskedContent = DesensitizeUtil.maskLongText(content);

            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            String outputFilename = "masked_" + timestamp + extension;
            Path outputPath = resultDir.resolve(outputFilename);
            Files.writeString(outputPath, maskedContent, java.nio.charset.StandardCharsets.UTF_8);

            Map<String, String> data = new HashMap<>();
            data.put("originalFile", originalFilename);
            data.put("outputFile", outputFilename);
            data.put("outputPath", outputPath.toString());
            data.put("preview", maskedContent.length() > 500 ? maskedContent.substring(0, 500) + "..." : maskedContent);

            return ResponseEntity.ok(ResponseResult.success(data));
        } catch (IOException e) {
            log.error("文件脱敏失败", e);
            return ResponseEntity.ok(ResponseResult.error("文件处理失败: " + e.getMessage()));
        } catch (Exception e) {
            log.error("文件脱敏失败", e);
            return ResponseEntity.ok(ResponseResult.error("脱敏失败: " + e.getMessage()));
        }
    }

    @PostMapping("/table")
    public ResponseEntity<ResponseResult> desensitizeTable(@RequestParam("file") MultipartFile file) {
        try {
            if (file.isEmpty()) {
                return ResponseEntity.badRequest().body(ResponseResult.error("请选择要脱敏的表格文件"));
            }

            String originalFilename = file.getOriginalFilename();
            if (originalFilename == null || 
                (!originalFilename.endsWith(".xlsx") && !originalFilename.endsWith(".xls") && !originalFilename.endsWith(".csv"))) {
                return ResponseEntity.badRequest().body(ResponseResult.error("仅支持xlsx、xls和csv格式的表格文件"));
            }

            Path basePath = Paths.get(".").toAbsolutePath().normalize();
            Path tempDir = basePath.resolve("temp");
            Path resultDir = basePath.resolve("result");
            if (!Files.exists(tempDir)) {
                Files.createDirectories(tempDir);
            }
            if (!Files.exists(resultDir)) {
                Files.createDirectories(resultDir);
            }

            Path tempInputPath = tempDir.resolve("input_" + System.currentTimeMillis() + "_" + originalFilename);
            file.transferTo(tempInputPath.toFile());

            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            String outputFilename = "masked_" + timestamp + originalFilename.substring(originalFilename.lastIndexOf("."));
            Path outputPath = resultDir.resolve(outputFilename);

            com.desensitize.runner.FileProcessor.processTableFile(tempInputPath.toAbsolutePath().toString(), outputPath.toAbsolutePath().toString());

            Map<String, String> data = new HashMap<>();
            data.put("originalFile", originalFilename);
            data.put("outputFile", outputFilename);
            data.put("outputPath", outputPath.toString());

            Files.deleteIfExists(tempInputPath);

            return ResponseEntity.ok(ResponseResult.success(data));
        } catch (IOException e) {
            log.error("表格脱敏失败", e);
            return ResponseEntity.ok(ResponseResult.error("文件处理失败: " + e.getMessage()));
        } catch (Exception e) {
            log.error("表格脱敏失败", e);
            return ResponseEntity.ok(ResponseResult.error("脱敏失败: " + e.getMessage()));
        }
    }

    @GetMapping("/types")
    public ResponseEntity<ResponseResult> getSensitiveTypes() {
        try {
            com.desensitize.core.registry.DesensitizeRuleRegistry registry = 
                new com.desensitize.core.registry.DesensitizeRuleRegistry();
            
            Map<String, Object> data = new HashMap<>();
            for (com.desensitize.core.model.SensitiveTypeConfig config : registry.getAllTypes()) {
                Map<String, Object> typeInfo = new HashMap<>();
                typeInfo.put("typeId", config.getTypeId());
                typeInfo.put("name", config.getName());
                typeInfo.put("maskFlag", config.isMaskFlag());
                typeInfo.put("strategyType", config.isStrategyType());
                typeInfo.put("maskFormat", config.getMaskFormat());
                data.put(config.getTypeId(), typeInfo);
            }

            return ResponseEntity.ok(ResponseResult.success(data));
        } catch (Exception e) {
            log.error("获取敏感类型失败", e);
            return ResponseEntity.ok(ResponseResult.error("获取敏感类型失败: " + e.getMessage()));
        }
    }

    @PostMapping("/multi-type")
    public ResponseEntity<ResponseResult> desensitizeMultiType(@RequestBody Map<String, String> inputs) {
        try {
            Map<String, String> results = new HashMap<>();
            Map<String, String> errors = new HashMap<>();

            java.util.regex.Pattern phonePattern = java.util.regex.Pattern.compile("1[3-9]\\d{9}");
            java.util.regex.Pattern idCardPattern = java.util.regex.Pattern.compile("[1-9]\\d{5}(?:18|19|20)\\d{2}(?:0[1-9]|1[0-2])(?:0[1-9]|[12]\\d|3[01])\\d{3}[\\dXx]");
            java.util.regex.Pattern emailPattern = java.util.regex.Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
            java.util.regex.Pattern bankCardPattern = java.util.regex.Pattern.compile("\\d{16,19}");
            java.util.regex.Pattern ipPattern = java.util.regex.Pattern.compile("(?:(?:25[0-5]|2[0-4]\\d|[01]?\\d\\d?)\\.){3}(?:25[0-5]|2[0-4]\\d|[01]?\\d\\d?)");
            java.util.regex.Pattern macPattern = java.util.regex.Pattern.compile("([0-9A-Fa-f]{2}[:-]){5}([0-9A-Fa-f]{2})");
            java.util.regex.Pattern datePattern = java.util.regex.Pattern.compile("\\d{4}[-/]\\d{1,2}[-/]\\d{1,2}");
            java.util.regex.Pattern platePattern = java.util.regex.Pattern.compile("[京津沪渝冀豫云辽黑湘皖鲁新苏浙赣鄂桂甘晋蒙陕吉闽贵粤青川藏琼][A-HJ-NP-Z][A-HJ-NP-Z0-9]{4,5}[A-HJ-NP-Z0-9挂学警]");
            java.util.regex.Pattern passportPattern = java.util.regex.Pattern.compile("[A-Z]\\d{8}|[A-Z]{2}\\d{7}");
            java.util.regex.Pattern landlinePattern = java.util.regex.Pattern.compile("\\d{3,4}-\\d{7,8}");
            java.util.regex.Pattern internationalPattern = java.util.regex.Pattern.compile("\\+?[1-9]\\d{1,14}");

            if (inputs.containsKey("phone")) {
                String phone = inputs.get("phone");
                if (phone != null && !phone.trim().isEmpty()) {
                    if (phonePattern.matcher(phone.trim()).matches()) {
                        results.put("phone", DesensitizeUtil.mask(phone.trim(), "phone"));
                    } else {
                        errors.put("phone", "手机号格式不正确");
                    }
                }
            }

            if (inputs.containsKey("name")) {
                String name = inputs.get("name");
                if (name != null && !name.trim().isEmpty()) {
                    if (name.trim().matches("[\\u4e00-\\u9fff]{2,8}")) {
                        results.put("name", DesensitizeUtil.mask(name.trim(), "name"));
                    } else {
                        errors.put("name", "姓名格式不正确");
                    }
                }
            }

            if (inputs.containsKey("id_card")) {
                String idCard = inputs.get("id_card");
                if (idCard != null && !idCard.trim().isEmpty()) {
                    if (idCardPattern.matcher(idCard.trim()).matches()) {
                        results.put("id_card", DesensitizeUtil.mask(idCard.trim(), "id_card"));
                    } else {
                        errors.put("id_card", "身份证号格式不正确");
                    }
                }
            }

            if (inputs.containsKey("email")) {
                String email = inputs.get("email");
                if (email != null && !email.trim().isEmpty()) {
                    if (emailPattern.matcher(email.trim()).matches()) {
                        results.put("email", DesensitizeUtil.mask(email.trim(), "email"));
                    } else {
                        errors.put("email", "邮箱格式不正确");
                    }
                }
            }

            if (inputs.containsKey("bank_card")) {
                String bankCard = inputs.get("bank_card");
                if (bankCard != null && !bankCard.trim().isEmpty()) {
                    if (bankCardPattern.matcher(bankCard.trim()).matches()) {
                        results.put("bank_card", DesensitizeUtil.mask(bankCard.trim(), "bank_card"));
                    } else {
                        errors.put("bank_card", "银行卡号格式不正确");
                    }
                }
            }

            if (inputs.containsKey("address")) {
                String address = inputs.get("address");
                if (address != null && !address.trim().isEmpty()) {
                    results.put("address", DesensitizeUtil.mask(address.trim(), "address"));
                }
            }

            if (inputs.containsKey("license_plate")) {
                String plate = inputs.get("license_plate");
                if (plate != null && !plate.trim().isEmpty()) {
                    if (platePattern.matcher(plate.trim()).matches()) {
                        results.put("license_plate", DesensitizeUtil.mask(plate.trim(), "license_plate"));
                    } else {
                        errors.put("license_plate", "车牌号格式不正确");
                    }
                }
            }

            if (inputs.containsKey("passport")) {
                String passport = inputs.get("passport");
                if (passport != null && !passport.trim().isEmpty()) {
                    if (passportPattern.matcher(passport.trim()).matches()) {
                        results.put("passport", DesensitizeUtil.mask(passport.trim(), "passport"));
                    } else {
                        errors.put("passport", "护照号格式不正确");
                    }
                }
            }

            if (inputs.containsKey("driver_license")) {
                String driverLicense = inputs.get("driver_license");
                if (driverLicense != null && !driverLicense.trim().isEmpty()) {
                    if (driverLicense.trim().length() == 18 && driverLicense.matches("^[1-9]\\d{5}(18|19|20)\\d{2}(0[1-9]|1[0-2])(0[1-9]|[12]\\d|3[01])\\d{3}[\\dXx]$")) {
                        results.put("driver_license", DesensitizeUtil.mask(driverLicense.trim(), "driver_license"));
                    } else {
                        errors.put("driver_license", "驾驶证号格式不正确");
                    }
                }
            }

            if (inputs.containsKey("military_id")) {
                String militaryId = inputs.get("military_id");
                if (militaryId != null && !militaryId.trim().isEmpty()) {
                    if (militaryId.trim().matches("[A-Za-z0-9]{10,18}")) {
                        results.put("military_id", DesensitizeUtil.mask(militaryId.trim(), "military_id"));
                    } else {
                        errors.put("military_id", "军官证号格式不正确");
                    }
                }
            }

            if (inputs.containsKey("deposit_book")) {
                String depositBook = inputs.get("deposit_book");
                if (depositBook != null && !depositBook.trim().isEmpty()) {
                    if (depositBook.trim().matches("\\d{16,20}")) {
                        results.put("deposit_book", DesensitizeUtil.mask(depositBook.trim(), "deposit_book"));
                    } else {
                        errors.put("deposit_book", "存折号格式不正确");
                    }
                }
            }

            if (inputs.containsKey("ip_address")) {
                String ip = inputs.get("ip_address");
                if (ip != null && !ip.trim().isEmpty()) {
                    if (ipPattern.matcher(ip.trim()).matches()) {
                        results.put("ip_address", DesensitizeUtil.mask(ip.trim(), "ip_address"));
                    } else {
                        errors.put("ip_address", "IP地址格式不正确");
                    }
                }
            }

            if (inputs.containsKey("mac_address")) {
                String mac = inputs.get("mac_address");
                if (mac != null && !mac.trim().isEmpty()) {
                    if (macPattern.matcher(mac.trim()).matches()) {
                        results.put("mac_address", DesensitizeUtil.mask(mac.trim(), "mac_address"));
                    } else {
                        errors.put("mac_address", "MAC地址格式不正确");
                    }
                }
            }

            if (inputs.containsKey("birthday")) {
                String birthday = inputs.get("birthday");
                if (birthday != null && !birthday.trim().isEmpty()) {
                    if (datePattern.matcher(birthday.trim()).matches()) {
                        results.put("birthday", DesensitizeUtil.mask(birthday.trim(), "birthday"));
                    } else {
                        errors.put("birthday", "生日格式不正确，应为YYYY-MM-DD或YYYY/MM/DD");
                    }
                }
            }

            if (inputs.containsKey("landline")) {
                String landline = inputs.get("landline");
                if (landline != null && !landline.trim().isEmpty()) {
                    if (landlinePattern.matcher(landline.trim()).matches()) {
                        results.put("landline", DesensitizeUtil.mask(landline.trim(), "landline_domestic"));
                    } else {
                        errors.put("landline", "固定电话格式不正确，应为XXX-XXXXXXX");
                    }
                }
            }

            if (inputs.containsKey("international")) {
                String international = inputs.get("international");
                if (international != null && !international.trim().isEmpty()) {
                    if (internationalPattern.matcher(international.trim()).matches()) {
                        results.put("international", DesensitizeUtil.mask(international.trim(), "international_phone"));
                    } else {
                        errors.put("international", "国际电话格式不正确");
                    }
                }
            }

            if (inputs.containsKey("rmb")) {
                String rmb = inputs.get("rmb");
                if (rmb != null && !rmb.trim().isEmpty()) {
                    if (rmb.trim().matches("(?:¥)?\\d{1,10}(?:\\.\\d{1,2})?")) {
                        results.put("rmb", DesensitizeUtil.mask(rmb.trim(), "rmb_amount"));
                    } else {
                        errors.put("rmb", "人民币金额格式不正确");
                    }
                }
            }

            if (inputs.containsKey("foreign")) {
                String foreign = inputs.get("foreign");
                if (foreign != null && !foreign.trim().isEmpty()) {
                    if (foreign.trim().matches("[$€£]?\\d{1,10}(?:\\.\\d{1,2})?")) {
                        results.put("foreign", DesensitizeUtil.mask(foreign.trim(), "foreign_amount"));
                    } else {
                        errors.put("foreign", "外币金额格式不正确");
                    }
                }
            }

            if (inputs.containsKey("verification")) {
                String verification = inputs.get("verification");
                if (verification != null && !verification.trim().isEmpty()) {
                    if (verification.trim().matches("\\d{4,8}")) {
                        results.put("verification", DesensitizeUtil.mask(verification.trim(), "verification_code"));
                    } else {
                        errors.put("verification", "验证码格式不正确，应为4-8位数字");
                    }
                }
            }

            if (inputs.containsKey("english")) {
                String english = inputs.get("english");
                if (english != null && !english.trim().isEmpty()) {
                    if (english.trim().matches("[A-Z][a-z]+(?:\\s[A-Z][a-z]+)?")) {
                        results.put("english", DesensitizeUtil.mask(english.trim(), "english_name"));
                    } else {
                        errors.put("english", "英文姓名格式不正确，应为名 姓格式");
                    }
                }
            }

            Map<String, Object> data = new HashMap<>();
            data.put("results", results);
            if (!errors.isEmpty()) {
                data.put("errors", errors);
            }

            return ResponseEntity.ok(ResponseResult.success(data));
        } catch (Exception e) {
            log.error("多类型脱敏失败", e);
            return ResponseEntity.ok(ResponseResult.error("脱敏失败: " + e.getMessage()));
        }
    }

    @GetMapping("/download/{filename}")
    public ResponseEntity<byte[]> downloadFile(@PathVariable String filename) {
        try {
            Path filePath = Paths.get("result").resolve(filename).normalize();
            
            if (!Files.exists(filePath)) {
                return ResponseEntity.notFound().build();
            }

            byte[] fileContent = Files.readAllBytes(filePath);
            
            String encodedFilename = URLEncoder.encode(filename, "UTF-8").replace("+", "%20");
            
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.parseMediaType("application/octet-stream"));
            headers.setContentDispositionFormData("attachment", encodedFilename);
            headers.setContentLength(fileContent.length);
            headers.set("Content-Encoding", "UTF-8");
            
            return ResponseEntity.ok()
                    .headers(headers)
                    .body(fileContent);
        } catch (IOException e) {
            log.error("下载文件失败", e);
            return ResponseEntity.internalServerError().build();
        }
    }
}
