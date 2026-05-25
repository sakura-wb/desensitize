package com.desensitize.runner;

import com.desensitize.core.engine.DesensitizeUtil;
import com.opencsv.CSVReader;
import com.opencsv.CSVWriter;
import com.opencsv.exceptions.CsvValidationException;

import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

@Slf4j
public class FileProcessor {

    private static final Map<String, String> HEADER_TYPE_MAP = new LinkedHashMap<>();

    static {
        HEADER_TYPE_MAP.put("姓名", "name");
        HEADER_TYPE_MAP.put("name", "name");
        HEADER_TYPE_MAP.put("手机号", "phone");
        HEADER_TYPE_MAP.put("phone", "phone");
        HEADER_TYPE_MAP.put("身份证号", "id_card");
        HEADER_TYPE_MAP.put("id_card", "id_card");
        HEADER_TYPE_MAP.put("银行卡号", "bank_card");
        HEADER_TYPE_MAP.put("bank_card", "bank_card");
        HEADER_TYPE_MAP.put("地址", "address");
        HEADER_TYPE_MAP.put("address", "address");
        HEADER_TYPE_MAP.put("国家", "nationality");
        HEADER_TYPE_MAP.put("nationality", "nationality");
    }

    private static final Set<String> TEXT_EXTENSIONS = Set.of("txt", "md", "json", "xml", "csv");

    public static void processDocumentFile(String filePath) {
        Path path = Paths.get(filePath);
        validateFile(path);

        String extension = getFileExtension(path);
        if (!TEXT_EXTENSIONS.contains(extension.toLowerCase())) {
            System.err.println("错误: 不支持的文件格式 ." + extension + "，支持的格式: " + TEXT_EXTENSIONS);
            System.exit(1);
        }

        try {
            String content = Files.readString(path, StandardCharsets.UTF_8);
            String masked = DesensitizeUtil.maskLongText(content);
            writeResult(path, masked, extension);
            log.info("文档文件脱敏完成: {} -> result/", path.getFileName());
            System.out.println("脱敏完成，结果保存在: result/" + getOutputFileName(path));
        } catch (IOException e) {
            System.err.println("文件处理失败: " + e.getMessage());
            System.exit(1);
        }
    }

    public static void processTableFile(String filePath) throws CsvValidationException {
        Path path = Paths.get(filePath);
        validateFile(path);

        String extension = getFileExtension(path).toLowerCase();

        try {
            switch (extension) {
                case "csv":
                    processCsvFile(path);
                    break;
                case "xlsx":
                    processXlsxFile(path);
                    break;
                default:
                    System.err.println("错误: 不支持的表格格式 ." + extension + "，支持: csv, xlsx");
                    System.exit(1);
            }
        } catch (IOException e) {
            System.err.println("表格文件处理失败: " + e.getMessage());
            System.exit(1);
        }
    }

    public static void processTableFile(String inputPath, String outputPath) throws CsvValidationException, IOException {
        Path path = Paths.get(inputPath);
        validateFile(path);

        Path outPath = Paths.get(outputPath);
        Path outDir = outPath.getParent();
        if (outDir != null && !Files.exists(outDir)) {
            Files.createDirectories(outDir);
        }

        String extension = getFileExtension(path).toLowerCase();

        switch (extension) {
            case "csv":
                processCsvFileTo(path, outPath);
                break;
            case "xlsx":
                processXlsxFileTo(path, outPath);
                break;
            default:
                throw new IllegalArgumentException("不支持的表格格式 ." + extension + "，支持: csv, xlsx");
        }
    }

    private static void processCsvFile(Path path) throws IOException, CsvValidationException {
        List<String[]> allRows = new ArrayList<>();
        try (CSVReader reader = new CSVReader(new InputStreamReader(new FileInputStream(path.toFile()), StandardCharsets.UTF_8))) {
            String[] line;
            while ((line = reader.readNext()) != null) {
                allRows.add(line);
            }
        }

        if (allRows.isEmpty()) {
            System.err.println("错误: 表格文件为空");
            System.exit(1);
        }

        String[] headers = allRows.get(0);
        String[] typeMapping = resolveColumnTypes(headers);

        List<String[]> outputRows = new ArrayList<>();
        outputRows.add(headers);

        for (int i = 1; i < allRows.size(); i++) {
            String[] row = allRows.get(i);
            String[] maskedRow = new String[row.length];
            for (int col = 0; col < row.length; col++) {
                String cellValue = row[col] != null ? row[col] : "";
                if (typeMapping[col] != null) {
                    maskedRow[col] = DesensitizeUtil.mask(cellValue, typeMapping[col]);
                } else {
                    maskedRow[col] = DesensitizeUtil.maskLongText(cellValue);
                }
            }
            outputRows.add(maskedRow);
        }

        Path resultPath = ensureResultDir().resolve(getOutputFileName(path));
        try (CSVWriter writer = new CSVWriter(
                new OutputStreamWriter(new FileOutputStream(resultPath.toFile()), StandardCharsets.UTF_8))) {
            writer.writeAll(outputRows);
        }

        System.out.println("CSV脱敏完成，结果保存在: " + resultPath);
    }

    private static void processCsvFileTo(Path path, Path outputPath) throws IOException, CsvValidationException {
        List<String[]> allRows = new ArrayList<>();
        try (CSVReader reader = new CSVReader(new InputStreamReader(new FileInputStream(path.toFile()), StandardCharsets.UTF_8))) {
            String[] line;
            while ((line = reader.readNext()) != null) {
                allRows.add(line);
            }
        }

        if (allRows.isEmpty()) {
            throw new IllegalArgumentException("表格文件为空");
        }

        String[] headers = allRows.get(0);
        String[] typeMapping = resolveColumnTypes(headers);

        List<String[]> outputRows = new ArrayList<>();
        outputRows.add(headers);

        for (int i = 1; i < allRows.size(); i++) {
            String[] row = allRows.get(i);
            String[] maskedRow = new String[row.length];
            for (int col = 0; col < row.length; col++) {
                String cellValue = row[col] != null ? row[col] : "";
                if (typeMapping[col] != null) {
                    maskedRow[col] = DesensitizeUtil.mask(cellValue, typeMapping[col]);
                } else {
                    maskedRow[col] = DesensitizeUtil.maskLongText(cellValue);
                }
            }
            outputRows.add(maskedRow);
        }

        try (CSVWriter writer = new CSVWriter(
                new OutputStreamWriter(new FileOutputStream(outputPath.toFile()), StandardCharsets.UTF_8))) {
            writer.writeAll(outputRows);
        }
    }

    private static void processXlsxFile(Path path) throws IOException {
        List<List<String>> allRows = new ArrayList<>();
        try (Workbook workbook = new XSSFWorkbook(new FileInputStream(path.toFile()))) {
            Sheet sheet = workbook.getSheetAt(0);

            for (Row row : sheet) {
                List<String> rowData = new ArrayList<>();
                for (Cell cell : row) {
                    rowData.add(getCellValueAsString(cell));
                }
                allRows.add(rowData);
            }
        }

        if (allRows.isEmpty()) {
            System.err.println("错误: 表格文件为空");
            System.exit(1);
        }

        List<String> headerRow = allRows.get(0);
        String[] headers = headerRow.toArray(new String[0]);
        String[] typeMapping = resolveColumnTypes(headers);

        Path resultPath = ensureResultDir().resolve(getOutputFileName(path));
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Sheet1");

            Row headerRowOut = sheet.createRow(0);
            for (int col = 0; col < headers.length; col++) {
                Cell cell = headerRowOut.createCell(col);
                cell.setCellValue(headers[col]);
            }

            for (int i = 1; i < allRows.size(); i++) {
                List<String> row = allRows.get(i);
                Row dataRow = sheet.createRow(i);
                for (int col = 0; col < row.size(); col++) {
                    String cellValue = row.get(col) != null ? row.get(col) : "";
                    String masked;
                    if (typeMapping[col] != null) {
                        masked = DesensitizeUtil.mask(cellValue, typeMapping[col]);
                    } else {
                        masked = DesensitizeUtil.maskLongText(cellValue);
                    }
                    Cell cell = dataRow.createCell(col);
                    cell.setCellValue(masked);
                }
            }

            try (FileOutputStream fos = new FileOutputStream(resultPath.toFile())) {
                workbook.write(fos);
            }
        }

        System.out.println("XLSX脱敏完成，结果保存在: " + resultPath);
    }

    private static void processXlsxFileTo(Path path, Path outputPath) throws IOException {
        List<List<String>> allRows = new ArrayList<>();
        try (Workbook workbook = new XSSFWorkbook(new FileInputStream(path.toFile()))) {
            Sheet sheet = workbook.getSheetAt(0);

            for (Row row : sheet) {
                List<String> rowData = new ArrayList<>();
                for (Cell cell : row) {
                    rowData.add(getCellValueAsString(cell));
                }
                allRows.add(rowData);
            }
        }

        if (allRows.isEmpty()) {
            throw new IllegalArgumentException("表格文件为空");
        }

        List<String> headerRow = allRows.get(0);
        String[] headers = headerRow.toArray(new String[0]);
        String[] typeMapping = resolveColumnTypes(headers);

        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Sheet1");

            Row headerRowOut = sheet.createRow(0);
            for (int col = 0; col < headers.length; col++) {
                Cell cell = headerRowOut.createCell(col);
                cell.setCellValue(headers[col]);
            }

            for (int i = 1; i < allRows.size(); i++) {
                List<String> row = allRows.get(i);
                Row dataRow = sheet.createRow(i);
                for (int col = 0; col < row.size(); col++) {
                    String cellValue = row.get(col) != null ? row.get(col) : "";
                    String masked;
                    if (typeMapping[col] != null) {
                        masked = DesensitizeUtil.mask(cellValue, typeMapping[col]);
                    } else {
                        masked = DesensitizeUtil.maskLongText(cellValue);
                    }
                    Cell cell = dataRow.createCell(col);
                    cell.setCellValue(masked);
                }
            }

            try (FileOutputStream fos = new FileOutputStream(outputPath.toFile())) {
                workbook.write(fos);
            }
        }
    }

    private static String[] resolveColumnTypes(String[] headers) {
        String[] mapping = new String[headers.length];
        for (int i = 0; i < headers.length; i++) {
            String header = headers[i];
            if (header != null) {
                String normalized = header.trim();
                mapping[i] = HEADER_TYPE_MAP.get(normalized);
                if (mapping[i] == null) {
                    mapping[i] = HEADER_TYPE_MAP.get(normalized.toLowerCase());
                }
            }
        }
        return mapping;
    }

    private static String getCellValueAsString(Cell cell) {
        if (cell == null) return "";
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue();
            case NUMERIC -> {
                if (DateUtil.isCellDateFormatted(cell)) {
                    yield cell.getLocalDateTimeCellValue().toString();
                }
                double val = cell.getNumericCellValue();
                if (val == Math.floor(val) && !Double.isInfinite(val)) {
                    yield String.valueOf((long) val);
                }
                yield String.valueOf(val);
            }
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            case FORMULA -> {
                try {
                    yield cell.getStringCellValue();
                } catch (Exception e) {
                    yield String.valueOf(cell.getNumericCellValue());
                }
            }
            default -> "";
        };
    }

    private static void validateFile(Path path) {
        if (!Files.exists(path)) {
            System.err.println("错误: 文件不存在 - " + path);
            System.exit(1);
        }
        if (!Files.isReadable(path)) {
            System.err.println("错误: 文件不可读 - " + path);
            System.exit(1);
        }
    }

    private static String getFileExtension(Path path) {
        String fileName = path.getFileName().toString();
        int dotIndex = fileName.lastIndexOf('.');
        return dotIndex > 0 ? fileName.substring(dotIndex + 1) : "";
    }

    private static String getOutputFileName(Path path) {
        String fileName = path.getFileName().toString();
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex > 0) {
            return fileName.substring(0, dotIndex) + "_masked." + fileName.substring(dotIndex + 1);
        }
        return fileName + "_masked";
    }

    private static Path ensureResultDir() throws IOException {
        Path resultPath = Paths.get("result");
        if (!Files.exists(resultPath)) {
            Files.createDirectories(resultPath);
        }
        return resultPath;
    }

    private static void writeResult(Path sourcePath, String content, String extension) throws IOException {
        Path resultPath = ensureResultDir().resolve(getOutputFileName(sourcePath));
        Files.writeString(resultPath, content, StandardCharsets.UTF_8);
    }
}
